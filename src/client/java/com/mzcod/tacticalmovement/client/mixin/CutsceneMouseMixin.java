package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// During a game-over cutscene the mouse doesn't turn you (the scene has the camera), so nothing tied to where you look,
// like the first-person arm, swings about when it moves.
@Mixin(MouseHandler.class)
public abstract class CutsceneMouseMixin {
	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$noTurning(double dt, CallbackInfo ci) {
		if (GameOverCutscene.locksInput()) {
			ci.cancel();
		}
	}
}
