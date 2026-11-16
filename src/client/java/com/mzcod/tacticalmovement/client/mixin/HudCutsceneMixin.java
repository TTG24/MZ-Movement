package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// During the game-over cutscene the normal HUD is hidden; only the cutscene's own overlay is drawn.
@Mixin(Hud.class)
public abstract class HudCutsceneMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$cutsceneHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (GameOverCutscene.isActive()) {
			GameOverCutscene.renderOverlay(graphics, deltaTracker);
			ci.cancel();
		}
	}
}
