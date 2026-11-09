package com.mzcod.tacticalmovement.movement;

public interface MovementStateAccess {
	MovementStance tacticalMovement$getStance();

	void tacticalMovement$setStance(MovementStance stance);

	int tacticalMovement$getDiveTicks();

	void tacticalMovement$setDiveTicks(int ticks);

	// Client-only eased 0..1 blend between the upright pose (0) and the low-profile
	// prone pose (1). Drives the animated drop-in / get-up transition. Eased per
	// client tick toward 1 while stance.isLowProfile(), toward 0 otherwise.
	float tacticalMovement$getProneBlend();

	void tacticalMovement$setProneBlend(float blend);

	// Client-only eased 0..1 blend between the upright pose (0) and the tactical crouch pose (1).
	float tacticalMovement$getCrouchBlend();

	void tacticalMovement$setCrouchBlend(float blend);

	// Stamina (server-authoritative, synced to the owning client): current value, whether the player ran dry and is
	// locked out of sprint/dive until they recover, ticks until regeneration starts, and the last value sent.
	float tacticalMovement$getStamina();

	void tacticalMovement$setStamina(float stamina);

	boolean tacticalMovement$isExhausted();

	void tacticalMovement$setExhausted(boolean exhausted);

	int tacticalMovement$getStaminaDelay();

	void tacticalMovement$setStaminaDelay(int ticks);

	int tacticalMovement$getStaminaSent();

	void tacticalMovement$setStaminaSent(int sent);
}