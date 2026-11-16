package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// During a game-over cutscene clicking does nothing: no attacking, swinging, using or picking (the scene moves the arm).
@Mixin(Minecraft.class)
public abstract class CutsceneInputMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$noAttack(CallbackInfoReturnable<Boolean> cir) {
		if (GameOverCutscene.locksInput()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$noHeldAttack(boolean down, CallbackInfo ci) {
		if (GameOverCutscene.locksInput()) {
			ci.cancel();
		}
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$noUse(CallbackInfo ci) {
		if (GameOverCutscene.locksInput()) {
			ci.cancel();
		}
	}

	@Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$noPick(CallbackInfo ci) {
		if (GameOverCutscene.locksInput()) {
			ci.cancel();
		}
	}
}
