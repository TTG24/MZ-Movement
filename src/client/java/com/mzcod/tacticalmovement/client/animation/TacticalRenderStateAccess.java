package com.mzcod.tacticalmovement.client.animation;

import com.mzcod.tacticalmovement.movement.MovementStance;

public interface TacticalRenderStateAccess {
	MovementStance tacticalMovement$getStance();

	void tacticalMovement$setStance(MovementStance stance);

	boolean tacticalMovement$isHoldingSword();

	void tacticalMovement$setHoldingSword(boolean holdingSword);

	float tacticalMovement$getProneBlend();

	void tacticalMovement$setProneBlend(float blend);

	float tacticalMovement$getProneMove();

	void tacticalMovement$setProneMove(float move);

	// Eased 0..1 tactical-crouch blend and 0..1 crouch-walk weight, captured per render state.
	float tacticalMovement$getCrouchBlend();

	void tacticalMovement$setCrouchBlend(float blend);

	float tacticalMovement$getCrouchMove();

	void tacticalMovement$setCrouchMove(float move);

	// True when this render state belongs to the LOCAL player's model (not another player).
	// Captured during AvatarRenderer.extractRenderState so the model mixin can gate
	// local-camera effects (e.g. the prone head pitch tracking) to the local player only.
	boolean tacticalMovement$isLocalPlayer();

	void tacticalMovement$setLocalPlayer(boolean isLocalPlayer);

	// Smoothed 0..1 sprint / vanilla-sneak (shift) weights for the custom standing animations.
	float tacticalMovement$getSprintBlend();

	void tacticalMovement$setSprintBlend(float blend);

	float tacticalMovement$getSneakBlend();

	void tacticalMovement$setSneakBlend(float blend);
}
