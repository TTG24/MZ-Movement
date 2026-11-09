package com.mzcod.tacticalmovement.movement;

public enum MovementAction {
	ENTER_PRONE,
	EXIT_PRONE,
	DOLPHIN_DIVE,
	ENTER_CROUCH,
	EXIT_CROUCH,
	// Sent once on join so a companion server plugin knows this client has the mod (no state change).
	HELLO
}
