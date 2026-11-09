package com.mzcod.tacticalmovement.mixin;

import com.mzcod.tacticalmovement.movement.MovementStateAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Sneaking is suppressed while a player is prone/low-profile, so holding shift cannot push the model
// down (crouch offset/pose) or change the camera. Covers every non-LocalPlayer reader (server, remote
// players); LocalPlayer overrides these methods and is handled by the client mixin.
@Mixin(Entity.class)
public abstract class EntityShiftMixin {
	@Inject(method = "isShiftKeyDown", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$noShiftWhileProne(CallbackInfoReturnable<Boolean> cir) {
		if (tacticalMovement$isLowProfilePlayer()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "isCrouching", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$noCrouchWhileProne(CallbackInfoReturnable<Boolean> cir) {
		if (tacticalMovement$isLowProfilePlayer()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "isDiscrete", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$notDiscreteWhileProne(CallbackInfoReturnable<Boolean> cir) {
		if (tacticalMovement$isLowProfilePlayer()) {
			cir.setReturnValue(false);
		}
	}

	private boolean tacticalMovement$isLowProfilePlayer() {
		return (Object) this instanceof Player player
			&& ((MovementStateAccess) player).tacticalMovement$getStance().suppressesShift();
	}
}
