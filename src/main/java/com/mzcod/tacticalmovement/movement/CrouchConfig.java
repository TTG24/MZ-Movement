package com.mzcod.tacticalmovement.movement;

// Live-tunable crouch hitbox values (client debug command /tacticaledbg ceye). Defaults match the pose.
public final class CrouchConfig {
	public static volatile float eyeHeight = 1.08F;
	public static volatile float boxHeight = 1.35F;

	private CrouchConfig() {
	}
}
