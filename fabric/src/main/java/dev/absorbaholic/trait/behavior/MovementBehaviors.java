package dev.absorbaholic.trait.behavior;

/**
 * WP-BEH-C. Behavior group: movement. Client-physics flags (walk_on_fluid solid, climb_walls, glide, sink_in_water)
 * synced through the engine's MovementState, their server halves (frost walking, the glide gate, the sink push for
 * vanilla clients), and the movement abilities air_jump and flight. Touching each TYPE registers it.
 */
public final class MovementBehaviors {
	private MovementBehaviors() {}

	public static void register() {
		WalkOnFluidBehavior.TYPE.id();
		ClimbWallsBehavior.TYPE.id();
		SinkInWaterBehavior.TYPE.id();
		AirJumpBehavior.TYPE.id();
		GlideBehavior.TYPE.id();
		GlideBehavior.registerEvents();
		FlightBehavior.TYPE.id();
	}
}
