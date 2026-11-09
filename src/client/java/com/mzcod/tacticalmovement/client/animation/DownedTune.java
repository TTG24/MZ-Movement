package com.mzcod.tacticalmovement.client.animation;

// Live-tunable offsets (radians) of the last-stand pose, layered on the prone pose. Tune in game with
// /tacticaledbg downed <rarm|larm|rleg|rshin> <rad>; the defaults are baked in here once they look right.
public final class DownedTune {
	// Added to the right (gun) arm's xRot.
	public static float rightArm = -0.9F;
	// Added to the left arm's xRot (small: it stays down as the support arm).
	public static float leftArm = 0.15F;
	// SUBTRACTED from the right leg's xRot: the prone legs sit flat, so this draws the knee up.
	public static float rightLeg = 0.7F;
	// Added to the right shin's xRot (0 = untouched).
	public static float rightShin = -0.9F;

	private DownedTune() {
	}
}
