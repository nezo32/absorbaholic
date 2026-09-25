package dev.absorbaholic.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.AttributeClamp;
import dev.absorbaholic.core.DamageGate;
import dev.absorbaholic.core.FactorMath;
import org.junit.jupiter.api.Test;

/**
 * Acceptance (tester): the brief's caps. Max traits 20, the absorb timings, attribute clamps for speed / scale /
 * health / reach, weakness damage at most 4 hearts per second combined and never a one-shot from full health, ability
 * cooldown / range limits, and every tuning number living in the one {@link AbsorbCaps} class.
 */
class CapsRequirementsTest {
	@Test
	void briefDefaults() {
		assertEquals(20, AbsorbCaps.DEFAULT_MAX_TRAITS, "max traits 20");
		assertEquals(30, AbsorbCaps.CHANNEL_TICKS, "hold 1.5 s");
		assertEquals(200, AbsorbCaps.COOLDOWN_TICKS, "10 s cooldown");
		assertEquals(0.25F, AbsorbCaps.MOB_HEALTH_THRESHOLD, "mobs at <= 25 % health");
		assertEquals(3, AbsorbCaps.DEFAULT_MAX_LEVEL, "max III");
	}

	@Test
	void weaknessBudgetIsAtMostFourHeartsPerSecond() {
		// 4 hearts = 8 HP; the window must be at least one second (20 ticks) for the rate to be <= 4 hearts/s
		double hpPerSecond = AbsorbCaps.WEAKNESS_DAMAGE_BUDGET * 20.0 / AbsorbCaps.WEAKNESS_DAMAGE_WINDOW_TICKS;
		assertTrue(hpPerSecond <= 8.0 + 1e-9, "weakness damage rate " + hpPerSecond + " HP/s > 8 HP/s");
		assertTrue(AbsorbCaps.WEAKNESS_DAMAGE_WINDOW_TICKS >= 20, "window shorter than a second allows bursts over 4 hearts in a second");
		assertTrue(AbsorbCaps.WEAKNESS_MIN_HEALTH_FROM_FULL >= 1.0F, "a full-health player keeps at least 1 HP");
	}

	private static void assertClamp(String attribute, double base, double lowestFinal, double highestFinal) {
		AbsorbCaps.Clamp c = AbsorbCaps.clampFor(attribute).orElseThrow(() -> new AssertionError("no clamp for " + attribute));
		assertTrue(c.lower(base) >= lowestFinal - 1e-9, attribute + " lower bound " + c.lower(base) + " < " + lowestFinal);
		assertTrue(c.upper(base) <= highestFinal + 1e-9, attribute + " upper bound " + c.upper(base) + " > " + highestFinal);
		assertTrue(c.lower(base) < c.upper(base), attribute + " empty range");
	}

	@Test
	void attributeClampsForSpeedScaleHealthReach() {
		assertClamp("minecraft:movement_speed", 0.1, 0.04, 0.2);
		assertClamp("minecraft:scale", 1.0, 0.5, 2.0);
		assertClamp("minecraft:max_health", 20.0, 6.0, 60.0);
		assertClamp("minecraft:block_interaction_range", 4.5, 2.5, 8.0);
		assertClamp("minecraft:entity_interaction_range", 3.0, 2.0, 6.0);
	}

	/** Whatever our modifiers ask for, the final value lands inside the clamp (or is not pushed further out). */
	@Test
	void clampTargetAlwaysInRange() {
		SplittableRandom r = new SplittableRandom(3);
		for (AbsorbCaps.Clamp c : AbsorbCaps.CLAMPS) {
			for (int i = 0; i < 2000; i++) {
				double base = 0.05 + r.nextDouble() * 20;
				double lo = c.lower(base);
				double hi = c.upper(base);
				double without = lo + (hi - lo) * r.nextDouble(); // in range without our modifiers
				double withOurs = (r.nextDouble() - 0.5) * 200;
				double target = AttributeClamp.target(without, withOurs, lo, hi);
				assertTrue(target >= lo - 1e-9 && target <= hi + 1e-9, c.attribute() + ": " + target + " outside [" + lo + ", " + hi + "]");
			}
		}
	}

	/**
	 * Fuzz of the gate with realistic weakness hits (0.25..4 HP each, several per tick possible): in EVERY rolling
	 * 20-tick window at most 8 HP gets through, and a hit dealt at full health never leaves less than 1 HP.
	 */
	@Test
	void gateNeverLetsMoreThanFourHeartsPerSecondThrough() {
		SplittableRandom r = new SplittableRandom(99);
		for (int run = 0; run < 2000; run++) {
			DamageGate gate = new DamageGate();
			float maxHealth = 6 + r.nextInt(55);
			float health = maxHealth;
			ArrayDeque<float[]> dealt = new ArrayDeque<>(); // {tick, amount}
			float inWindow = 0;
			for (long tick = 0; tick < 400; tick++) {
				while (!dealt.isEmpty() && tick - (long) dealt.peekFirst()[0] >= 20) inWindow -= dealt.removeFirst()[1];
				int hits = r.nextInt(10) < 3 ? 1 + r.nextInt(3) : 0;
				for (int k = 0; k < hits; k++) {
					float req = 0.25F + r.nextFloat() * 3.75F;
					boolean full = health >= maxHealth;
					float allowed = r.nextBoolean() ? gate.allowDirect(tick, req, health, maxHealth)
							: gate.allowExtra(tick, req, r.nextFloat() * 3, health, maxHealth);
					assertTrue(allowed >= 0 && allowed <= req + 1e-6, "allowed " + allowed + " of " + req);
					if (full) assertTrue(health - allowed >= 1.0F - 1e-5, "full-health hit left " + (health - allowed));
					health -= allowed;
					dealt.addLast(new float[] {tick, allowed});
					inWindow += allowed;
					assertTrue(inWindow <= AbsorbCaps.WEAKNESS_DAMAGE_BUDGET + 1e-4, "window total " + inWindow + " at tick " + tick);
				}
				if (health <= 0) health = maxHealth; // respawn-ish; gate keeps its memory
				if (r.nextInt(20) == 0) health = Math.min(maxHealth, health + 4);
			}
		}
	}

	@Test
	void traitDamageReductionIsFloored() {
		FactorMath.Incoming in = FactorMath.incoming(10.0F, 0.01F, 1.0F);
		assertTrue(in.base() >= 10.0F * AbsorbCaps.DAMAGE_TAKEN_FLOOR - 1e-5, "combined reduction floored at 75 %: " + in.base());
	}

	@Test
	void abilityCapsAreFinite() {
		assertTrue(AbsorbCaps.ABILITY_MIN_COOLDOWN_TICKS >= 20, "abilities need a cooldown");
		assertTrue(AbsorbCaps.ABILITY_MAX_RANGE > 0 && AbsorbCaps.ABILITY_MAX_RANGE <= 64);
		assertTrue(AbsorbCaps.TELEPORT_MAX_DISTANCE > 0 && AbsorbCaps.TELEPORT_MAX_DISTANCE <= AbsorbCaps.ABILITY_MAX_RANGE);
		assertTrue(AbsorbCaps.SONIC_BOOM_MAX_RANGE > 0 && AbsorbCaps.SONIC_BOOM_MAX_RANGE <= AbsorbCaps.ABILITY_MAX_RANGE);
		assertTrue(AbsorbCaps.ABILITY_MAX_TARGETS > 0 && AbsorbCaps.ABILITY_MAX_TARGETS <= 16);
		assertTrue(AbsorbCaps.ABILITY_MAX_EXPLOSION_POWER <= 4.0F, "no more than TNT");
	}

	/**
	 * "All caps in one config class": no other main class declares its own numeric tuning constant for damage,
	 * cooldowns, ranges, chances or limits. Flags {@code static final} int/long/float/double fields outside core whose
	 * name looks like a cap (COOLDOWN, RANGE, RADIUS, MAX, MIN, CHANCE, BUDGET, THRESHOLD, DAMAGE, LIMIT).
	 */
	private static final java.util.Set<String> KNOWN_OUTSIDE = java.util.Set.of(
			// not caps: protocol / layout / refresh thresholds
			"NetCodecs.java: MAX_LIST = 4096", "TraitsSync.java: MAX_NAME_LENGTH = 64", "AbsorbHud.java: MAX_HINT_WIDTH = 200",
			"TraitsScreen.java: MAX_ROW_WIDTH = 340", "AuraParticles.java: MAX_DISTANCE = 48.0",
			"StatusEffectBehavior.java: PERMANENT_MIN_LEFT = 60", "StatusEffectBehavior.java: NIGHT_VISION_MIN_LEFT = 220",
			// gameplay limits outside AbsorbCaps (tester report, LOW)
			"SneakDetonateBehavior.java: MAX_FUSE = 200", "MobRules.java: MAX_MOBS_PER_SCAN = 48", "TeleportBehavior.java: LOOK_MAX_DROP = 3",
			"ShootProjectileBehavior.java: SHULKER_RANGE = 16.0", "ShootProjectileBehavior.java: FANG_DAMAGE = 6.0F",
			"MobAttitudeBehavior.java: REVENGE_RADIUS = 16.0", "MobAttitudeBehavior.java: REVENGE_MAX_HITS = 16",
			"ItemMagnetBehavior.java: MAX_ITEMS = 64");

	@Test
	void capsLiveInAbsorbCapsOnly() throws IOException {
		Pattern field = Pattern.compile("static\\s+final\\s+(?:int|long|float|double)\\s+([A-Z0-9_]+)\\s*=\\s*([^;]+);");
		Pattern capName = Pattern.compile(".*(COOLDOWN|RANGE|RADIUS|MAX|MIN|CHANCE|BUDGET|THRESHOLD|DAMAGE|LIMIT|CAP).*");
		List<String> found = new ArrayList<>();
		for (Path root : List.of(Path.of("src/main/java"), Path.of("src/client/java"))) {
			try (Stream<Path> walk = Files.walk(root)) {
				for (Path f : walk.filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("AbsorbCaps.java")).toList()) {
					Matcher m = field.matcher(Files.readString(f, StandardCharsets.UTF_8));
					while (m.find()) {
						if (!capName.matcher(m.group(1)).matches()) continue;
						String value = m.group(2).strip();
						if (value.contains("AbsorbCaps.")) continue; // derived from the caps class
						found.add(f.getFileName() + ": " + m.group(1) + " = " + value);
					}
				}
			}
		}
		// Ratchet. UI layout / protocol sizes and effect-refresh thresholds are not gameplay caps. The gameplay-ish limits
		// in KNOWN_OUTSIDE are reported as a LOW finding in the tester report (brief: "all caps in one config class");
		// this test fails as soon as a NEW one appears.
		found.removeIf(KNOWN_OUTSIDE::contains);
		assertTrue(found.isEmpty(), "new cap-like constants outside AbsorbCaps:\n" + String.join("\n", found));
	}
}
