package com.mzcod.tacticalmovement.mixin;

import com.mzcod.tacticalmovement.movement.MovementStance;
import com.mzcod.tacticalmovement.movement.MovementStateAccess;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerMovementStateMixin implements MovementStateAccess {
	@Unique
	private MovementStance tacticalMovement$stance = MovementStance.STANDING;

	@Unique
	private int tacticalMovement$diveTicks;

	@Unique
	private float tacticalMovement$proneBlend;

	@Override
	public MovementStance tacticalMovement$getStance() {
		return this.tacticalMovement$stance;
	}

	@Override
	public void tacticalMovement$setStance(MovementStance stance) {
		this.tacticalMovement$stance = stance;
	}

	@Override
	public int tacticalMovement$getDiveTicks() {
		return this.tacticalMovement$diveTicks;
	}

	@Override
	public void tacticalMovement$setDiveTicks(int ticks) {
		this.tacticalMovement$diveTicks = ticks;
	}

	@Unique
	private float tacticalMovement$stamina = com.mzcod.tacticalmovement.movement.StaminaConfig.max;

	@Unique
	private boolean tacticalMovement$exhausted;

	@Unique
	private int tacticalMovement$staminaDelay;

	@Unique
	private int tacticalMovement$staminaSent = -1;

	@Override
	public float tacticalMovement$getStamina() {
		return this.tacticalMovement$stamina;
	}

	@Override
	public void tacticalMovement$setStamina(float stamina) {
		this.tacticalMovement$stamina = stamina;
	}

	@Override
	public boolean tacticalMovement$isExhausted() {
		return this.tacticalMovement$exhausted;
	}

	@Override
	public void tacticalMovement$setExhausted(boolean exhausted) {
		this.tacticalMovement$exhausted = exhausted;
	}

	@Override
	public int tacticalMovement$getStaminaDelay() {
		return this.tacticalMovement$staminaDelay;
	}

	@Override
	public void tacticalMovement$setStaminaDelay(int ticks) {
		this.tacticalMovement$staminaDelay = ticks;
	}

	@Override
	public int tacticalMovement$getStaminaSent() {
		return this.tacticalMovement$staminaSent;
	}

	@Override
	public void tacticalMovement$setStaminaSent(int sent) {
		this.tacticalMovement$staminaSent = sent;
	}

	@Unique
	private float tacticalMovement$crouchBlend;

	@Override
	public float tacticalMovement$getCrouchBlend() {
		return this.tacticalMovement$crouchBlend;
	}

	@Override
	public void tacticalMovement$setCrouchBlend(float blend) {
		this.tacticalMovement$crouchBlend = blend;
	}

	@Override
	public float tacticalMovement$getProneBlend() {
		return this.tacticalMovement$proneBlend;
	}

	@Override
	public void tacticalMovement$setProneBlend(float blend) {
		this.tacticalMovement$proneBlend = blend;
	}

	@Inject(method = "updatePlayerPose", at = @At("TAIL"))
	private void tacticalMovement$preserveVisualPose(CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (this.tacticalMovement$stance.isLowProfile()) {
			self.setPose(Pose.SWIMMING);
		}
	}
}