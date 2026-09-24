package dev.absorbaholic.trait;

/**
 * Bits of client-side movement physics a behavior can grant (players move on their own client, so these are synced to
 * the owning client and read by common mixins on both sides via {@link MovementFlagsHolder}). Parameters travel in
 * {@link MovementState}.
 */
public final class MovementFlags {
	/** Stand / walk on water surfaces (LivingEntity#canStandOnFluid). Sneak to sink. */
	public static final int WALK_ON_WATER = 1;
	/** Stand / walk on lava surfaces. Sneak to sink. */
	public static final int WALK_ON_LAVA = 1 << 1;
	/** Walk on powder snow like with leather boots. */
	public static final int WALK_ON_POWDER_SNOW = 1 << 2;
	/** Climb any wall while pushing against it (LivingEntity#onClimbable). */
	public static final int CLIMB_WALLS = 1 << 3;
	/** Glide like with an elytra, without one (Fabric EntityElytraEvents.CUSTOM). */
	public static final int GLIDE = 1 << 4;
	/** Sink in water ({@code MovementState.sinkSpeed}); jump does not swim up. Client prediction smooths it. */
	public static final int SINK_IN_WATER = 1 << 5;

	private MovementFlags() {}

	public static boolean has(int flags, int flag) {
		return (flags & flag) != 0;
	}
}
