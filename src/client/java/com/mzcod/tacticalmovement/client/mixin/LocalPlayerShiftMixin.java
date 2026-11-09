package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.movement.MovementStateAccess;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// LocalPlayer overrides isShiftKeyDown/isCrouching (reading the live key state), so the common
// Entity mixin never sees them. Suppress sneaking here while the local player is prone/low-profile.
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerShiftMixin {
	@Inject(method = "isShiftKeyDown", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$noShiftWhileProne(CallbackInfoReturnable<Boolean> cir) {
		if (((MovementStateAccess) this).tacticalMovement$getStance().suppressesShift()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "isCrouching", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$noCrouchWhileProne(CallbackInfoReturnable<Boolean> cir) {
		if (((MovementStateAccess) this).tacticalMovement$getStance().suppressesShift()) {
			cir.setReturnValue(false);
		}
	}
}
