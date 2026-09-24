package dev.absorbaholic.trait;

import java.util.Optional;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.player.PlayerData;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * The only way behaviors deal direct weakness damage: {@code absorbaholic:weakness} damage type
 * (data/absorbaholic/damage_type/weakness.json: message_id absorbaholic.weakness, scaling never, exhaustion 0; tags
 * bypasses_armor, bypasses_shield, no_knockback; death message
 * {@code death.attack.absorbaholic.weakness}), amount limited by the player's {@code DamageGate} (at most 8 HP per
 * rolling 20 ticks, never below 1 HP from full health in one hit). The engine skips weakness multipliers for this
 * damage type, so it is never amplified twice. Does nothing while the player is not
 * {@linkplain TraitEngine#isActive active} (creative / spectator players are never hurt by weaknesses).
 */
public final class WeaknessDamage {
	/** The {@code absorbaholic:weakness} damage type. */
	public static final ResourceKey<DamageType> TYPE = ResourceKey.create(Registries.DAMAGE_TYPE, Absorbaholic.id("weakness"));

	private static boolean warnedMissing;

	private WeaknessDamage() {}

	/** Deals up to {@code amount} (after the gate); returns the amount actually requested from vanilla. */
	public static float hurt(ServerPlayer player, float amount) {
		return hurt(player, amount, null);
	}

	/**
	 * Same as {@link #hurt(ServerPlayer, float)}, credited to {@code cause} (e.g. the owner of the projectile that
	 * triggered {@code struck_by}; death message {@code death.attack.absorbaholic.weakness.player}).
	 */
	public static float hurt(ServerPlayer player, float amount, @Nullable Entity cause) {
		if (!(amount > 0.0F) || !TraitEngine.isActive(player)) return 0.0F;
		DamageSource source = source(player, cause);
		if (source == null) return 0.0F;
		long now = player.level().getServer().getTickCount();
		float allowed = PlayerData.runtime(player).damageGate.allowDirect(now, amount, player.getHealth(), player.getMaxHealth());
		if (allowed > 0.0F) player.hurtServer(player.level(), source, allowed);
		return allowed;
	}

	/** A weakness damage source (optionally credited to {@code cause}); null if a datapack removed the damage type. */
	public static @Nullable DamageSource source(ServerPlayer player, @Nullable Entity cause) {
		Optional<Holder.Reference<DamageType>> type = player.level().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).get(TYPE);
		if (type.isEmpty()) {
			if (!warnedMissing) {
				warnedMissing = true;
				Absorbaholic.LOGGER.warn("Absorbaholic: damage type {} is missing; weakness damage is disabled", TYPE.identifier());
			}
			return null;
		}
		return cause == null ? new DamageSource(type.get()) : new DamageSource(type.get(), cause);
	}
}
