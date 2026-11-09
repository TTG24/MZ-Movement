package com.mzcod.tacticalmovement.movement;

public enum MovementStance {
	STANDING,
	PRONE,
	DIVING,
	// Tactical crouch: knees bent, torso leaned in, arms out for balance. Separate from vanilla
	// shift-sneaking. Appended LAST so the network ordinals of the older stances don't change.
	CROUCHING,
	// Last stand (downed by zombies): set by the server plugin, never by the player. Rendered as a prone-based pose.
	DOWNED;

	// Vanilla shift-sneaking is ignored in every tactical stance except plain standing.
	public boolean suppressesShift() {
		return this != STANDING;
	}

	public boolean isLowProfile() {
		return this == PRONE || this == DIVING || this == DOWNED;
	}
}
