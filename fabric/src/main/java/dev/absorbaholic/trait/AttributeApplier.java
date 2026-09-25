package dev.absorbaholic.trait;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.AttributeClamp;
import dev.absorbaholic.player.PlayerTraits;
import dev.absorbaholic.player.TraitEntry;
import dev.absorbaholic.registry.SourceDefinition;
import dev.absorbaholic.registry.SourceRegistry;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jspecify.annotations.Nullable;

/**
 * Applies the attribute parts of a player's active traits and weaknesses: per (attribute, operation) one combined
 * transient modifier with id {@code absorbaholic:<operation>} (add_value and add_multiplied_base amounts are summed,
 * add_multiplied_total factors are multiplied, exactly as vanilla would combine separate modifiers), so removal is by
 * id and never touches other mods. Then, per clamped attribute ({@code AbsorbCaps.clampFor}), one correcting
 * add_multiplied_total modifier {@code absorbaholic:clamp} computed with {@code AttributeClamp}, so the FINAL value
 * (including other sources such as sprinting or effects) stays in range. Removes all of ours when dormant. Keeps
 * health &lt;= max health after max_health changes. {@link #reclamp} recomputes only the clamp modifiers (periodic).
 */
public final class AttributeApplier {
	static final Identifier ADD_VALUE = Absorbaholic.id("add_value");
	static final Identifier ADD_MULTIPLIED_BASE = Absorbaholic.id("add_multiplied_base");
	static final Identifier ADD_MULTIPLIED_TOTAL = Absorbaholic.id("add_multiplied_total");
	static final Identifier CLAMP = Absorbaholic.id("clamp");

	/** Attributes the player entity lacks (warned once per attribute id). */
	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
	private static final Map<Holder<Attribute>, Optional<AbsorbCaps.Clamp>> CLAMPS = new ConcurrentHashMap<>();

	private AttributeApplier() {}

	/** {@code traits} null or dormant player → remove all our modifiers. */
	public static void apply(ServerPlayer player, @Nullable PlayerTraits traits, boolean active) {
		Map<Holder<Attribute>, double[]> sums = active && traits != null ? collect(traits) : Map.of();
		Set<Holder<Attribute>> touched = TraitEngine.state(player).attributes;
		boolean maxHealth = false;
		for (Iterator<Holder<Attribute>> it = touched.iterator(); it.hasNext(); ) {
			Holder<Attribute> h = it.next();
			if (sums.containsKey(h)) continue;
			AttributeInstance inst = player.getAttribute(h);
			if (inst != null) {
				inst.removeModifier(ADD_VALUE);
				inst.removeModifier(ADD_MULTIPLIED_BASE);
				inst.removeModifier(ADD_MULTIPLIED_TOTAL);
				inst.removeModifier(CLAMP);
			}
			maxHealth |= isMaxHealth(h);
			it.remove();
		}
		for (Map.Entry<Holder<Attribute>, double[]> e : sums.entrySet()) {
			Holder<Attribute> h = e.getKey();
			AttributeInstance inst = player.getAttribute(h);
			if (inst == null) {
				String id = h.getRegisteredName();
				if (WARNED.add(id)) Absorbaholic.LOGGER.warn("Absorbaholic: players have no attribute {}; its trait / weakness parts are ignored", id);
				continue;
			}
			double[] s = e.getValue();
			set(inst, ADD_VALUE, AttributeModifier.Operation.ADD_VALUE, s[0]);
			set(inst, ADD_MULTIPLIED_BASE, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, s[1]);
			set(inst, ADD_MULTIPLIED_TOTAL, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, s[2] - 1.0);
			clamp(inst);
			touched.add(h);
			maxHealth |= isMaxHealth(h);
		}
		if (maxHealth && player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
	}

	/** Recomputes the clamp modifiers of the attributes that carry ours (other modifiers may have changed). */
	public static void reclamp(ServerPlayer player) {
		boolean maxHealth = false;
		for (Holder<Attribute> h : TraitEngine.state(player).attributes) {
			AttributeInstance inst = player.getAttribute(h);
			if (inst == null) continue;
			clamp(inst);
			maxHealth |= isMaxHealth(h);
		}
		if (maxHealth && player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
	}

	/** Per attribute: {sum add_value, sum add_multiplied_base, product of (1 + add_multiplied_total)}. */
	private static Map<Holder<Attribute>, double[]> collect(PlayerTraits traits) {
		Map<Holder<Attribute>, double[]> sums = new LinkedHashMap<>();
		for (TraitEntry e : traits.entries()) {
			Optional<SourceDefinition> def = SourceRegistry.byId(e.source());
			if (def.isEmpty()) continue;
			SourceDefinition d = def.get();
			add(sums, d.trait(), Math.min(e.traitLevel(), d.maxLevel()));
			add(sums, d.weakness(), Math.min(e.weaknessLevel(), d.maxLevel()));
		}
		return sums;
	}

	private static void add(Map<Holder<Attribute>, double[]> sums, SourceDefinition.Side side, int level) {
		if (level <= 0) return;
		for (AttributeEntry a : side.attributes()) {
			double amount = a.amount().at(level);
			if (!Double.isFinite(amount)) continue;
			double[] s = sums.computeIfAbsent(a.attribute(), k -> new double[] {0.0, 0.0, 1.0});
			switch (a.operation()) {
				case ADD_VALUE -> s[0] += amount;
				case ADD_MULTIPLIED_BASE -> s[1] += amount;
				case ADD_MULTIPLIED_TOTAL -> s[2] *= 1.0 + amount;
			}
		}
	}

	/** Adds / updates / removes one of our modifiers, touching the instance only when the amount changes. */
	private static void set(AttributeInstance inst, Identifier id, AttributeModifier.Operation op, double amount) {
		AttributeModifier current = inst.getModifier(id);
		if (amount == 0.0 || !Double.isFinite(amount)) {
			if (current != null) inst.removeModifier(id);
			return;
		}
		if (current != null && current.amount() == amount && current.operation() == op) return;
		inst.addOrUpdateTransientModifier(new AttributeModifier(id, amount, op));
	}

	/** Recomputes the clamp modifier of one attribute from the modifiers currently on it. */
	private static void clamp(AttributeInstance inst) {
		Optional<AbsorbCaps.Clamp> clamp = CLAMPS.computeIfAbsent(inst.getAttribute(), h -> AbsorbCaps.clampFor(h.getRegisteredName()));
		if (clamp.isEmpty()) {
			if (inst.getModifier(CLAMP) != null) inst.removeModifier(CLAMP);
			return;
		}
		double base = inst.getBaseValue();
		double otherAdd = 0.0, otherBase = 0.0, otherTotal = 1.0;
		double ourAdd = 0.0, ourBase = 0.0, ourTotal = 1.0;
		for (AttributeModifier m : inst.getModifiers()) {
			if (m.is(CLAMP)) continue;
			boolean ours = m.is(ADD_VALUE) || m.is(ADD_MULTIPLIED_BASE) || m.is(ADD_MULTIPLIED_TOTAL);
			switch (m.operation()) {
				case ADD_VALUE -> {
					if (ours) ourAdd += m.amount();
					else otherAdd += m.amount();
				}
				case ADD_MULTIPLIED_BASE -> {
					if (ours) ourBase += m.amount();
					else otherBase += m.amount();
				}
				case ADD_MULTIPLIED_TOTAL -> {
					if (ours) ourTotal *= 1.0 + m.amount();
					else otherTotal *= 1.0 + m.amount();
				}
			}
		}
		AbsorbCaps.Clamp c = clamp.get();
		Solution s = solve(base, otherAdd, otherBase, otherTotal, ourAdd, ourBase, ourTotal, c.lower(base), c.upper(base));
		if (s.scale() < 1.0) {
			// degenerate (our modifiers zero or flip the value): shrink ours instead of correcting multiplicatively
			set(inst, ADD_VALUE, AttributeModifier.Operation.ADD_VALUE, ourAdd * s.scale());
			set(inst, ADD_MULTIPLIED_BASE, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, ourBase * s.scale());
			set(inst, ADD_MULTIPLIED_TOTAL, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, (ourTotal - 1.0) * s.scale());
		}
		set(inst, CLAMP, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, s.correction());
	}

	private static boolean isMaxHealth(Holder<Attribute> h) {
		return h.value() == Attributes.MAX_HEALTH.value();
	}

	/**
	 * How to keep an attribute's final value in range: scale our modifiers by {@code scale} (1 = unchanged) and add a
	 * clamp add_multiplied_total modifier of {@code correction} (0 = none).
	 */
	record Solution(double scale, double correction) {}

	/**
	 * Pure clamp solver. {@code other*}: everyone else's combined modifiers; {@code our*}: ours (add sum, multiplied
	 * base sum, multiplied total product). The allowed final value follows {@link AttributeClamp#target}; normally one
	 * multiplicative correction reaches it. If our modifiers make the value 0 or flip its sign (no factor can fix
	 * that), our modifiers are scaled down instead until the value is inside the range (scale 0 = without ours, which
	 * the target rule always accepts).
	 */
	static Solution solve(double base, double otherAdd, double otherBase, double otherTotal,
			double ourAdd, double ourBase, double ourTotal, double lower, double upper) {
		double without = AttributeClamp.finalValue(base, otherAdd, otherBase, otherTotal);
		double withOurs = AttributeClamp.finalValue(base, otherAdd + ourAdd, otherBase + ourBase, otherTotal * ourTotal);
		double target = AttributeClamp.target(without, withOurs, lower, upper);
		if (withOurs == target) return new Solution(1.0, 0.0);
		if (withOurs != 0.0 && target / withOurs > 0.0) return new Solution(1.0, AttributeClamp.correction(withOurs, target));
		double lo = Math.min(lower, without);
		double hi = Math.max(upper, without);
		for (int step = 19; step > 0; step--) {
			double s = step / 20.0;
			double v = AttributeClamp.finalValue(base, otherAdd + ourAdd * s, otherBase + ourBase * s, otherTotal * (1.0 + (ourTotal - 1.0) * s));
			if (v >= lo && v <= hi) return new Solution(s, 0.0);
			double t = Math.clamp(v, lo, hi);
			if (v != 0.0 && t / v > 0.0) return new Solution(s, AttributeClamp.correction(v, t));
		}
		return new Solution(0.0, 0.0);
	}
}
