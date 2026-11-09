package com.mzcod.tacticalmovement.mixin;

import com.mzcod.tacticalmovement.movement.MovementStateAccess;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityJumpMixin {
	// Cancel the vanilla jump when a player is in a low-profile stance (prone/dive) or crouching (space stands up).
	// Space is repurposed as the "exit prone" trigger, so the default jump must be
	// suppressed to avoid the player launching into the air.
	@Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$cancelJumpInProne(CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self instanceof Player player && player instanceof MovementStateAccess state) {
			if (state.tacticalMovement$getStance().isLowProfile()
				|| state.tacticalMovement$getStance() == com.mzcod.tacticalmovement.movement.MovementStance.CROUCHING) {
				ci.cancel();
			}
		}
	}

	// No sprinting while crouching, prone or diving, or while exhausted (out of stamina): any request to start sprinting is turned into "stop sprinting".
	@Inject(method = "setSprinting", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$noSprintInStance(boolean sprinting, CallbackInfo ci) {
		if (sprinting && (Object) this instanceof Player player && player instanceof MovementStateAccess state
			&& (state.tacticalMovement$getStance() != com.mzcod.tacticalmovement.movement.MovementStance.STANDING
				|| state.tacticalMovement$isExhausted()
				|| (player.level().isClientSide() && com.mzcod.tacticalmovement.movement.SprintLock.locked))) {
			player.setSprinting(false);
			ci.cancel();
		}
	}
}