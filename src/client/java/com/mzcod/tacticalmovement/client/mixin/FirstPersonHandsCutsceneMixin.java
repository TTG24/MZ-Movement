package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// No first-person hand during the game-over cutscene, however it is drawn: shader packs (Iris) draw the hands
// themselves instead of through GameRenderer.renderItemInHand, but both end up here.
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsCutsceneMixin {
	@Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$noHandsInCutscene(CallbackInfo ci) {
		if (GameOverCutscene.isActive() && !GameOverCutscene.showsHands()) {
			ci.cancel();
		}
	}
}
