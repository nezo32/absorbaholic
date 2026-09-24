package dev.absorbaholic.trait;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * The overridable hooks of {@link Behavior}, by method name. {@link BehaviorType} detects once, by reflection, which
 * hooks a behavior class overrides, so the engine keeps one array of entries per hook and never calls no-op defaults.
 */
public enum Hook {
	TICK("tick"),
	ACTIVATE("onActivate"),
	DEACTIVATE("onDeactivate"),
	MOVEMENT("movement"),
	IMMUNITY("isImmuneTo"),
	INCOMING_DAMAGE("incomingDamageFactor"),
	OUTGOING_DAMAGE("outgoingDamageFactor"),
	DEALT_DAMAGE("onDealtDamage"),
	ATTACKED("onAttacked"),
	KILL("onKill"),
	JUMP("onJump"),
	SNEAK_JUMP("onSneakJump"),
	AIR_JUMP("onAirJump"),
	SNEAK_DOUBLE_TAP("onSneakDoubleTap"),
	SNEAK_SWING("onSneakSwing"),
	SNEAK_ATTACK("onSneakAttack"),
	LAND("onLand"),
	DEATH("onDeath"),
	WIPES_ON_DEATH("wipesOnDeath"),
	HEAL("healFactor"),
	EXHAUSTION("exhaustionFactor"),
	ALLOW_EFFECT("allowEffect"),
	MODIFY_EFFECT("modifyEffect"),
	PREVENT_TARGETING("preventsTargeting"),
	VISIBILITY("visibilityFactor"),
	BLOCK_BREAK("onBlockBreak"),
	HIT_BY_PROJECTILE("onHitByProjectile"),
	MODIFY_FOOD("modifyFood"),
	ITEM_CONSUMED("onItemConsumed"),
	EXPERIENCE("experienceFactor"),
	DURABILITY("durabilityFactor");

	public final String methodName;
	private final Method method;

	Hook(String methodName) {
		this.methodName = methodName;
		this.method = Arrays.stream(Behavior.class.getMethods())
				.filter(m -> m.getName().equals(methodName))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("Behavior has no method " + methodName));
	}

	/** True iff {@code impl}'s class overrides this hook (does not inherit Behavior's default). */
	public boolean isOverriddenBy(Behavior<?> impl) {
		try {
			return impl.getClass().getMethod(method.getName(), method.getParameterTypes()).getDeclaringClass() != Behavior.class;
		} catch (NoSuchMethodException e) {
			return false;
		}
	}
}
