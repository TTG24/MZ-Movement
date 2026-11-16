package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// No first-person hand or held item during the game-over cutscene (the camera is in the body on the ground), except in
// the MISSED scene while you're still standing with the sword. The server puts players in spectator for the game-over,
// and vanilla never draws hands for spectators, so for that scene the spectator check is passed as if not spectating.
@Mixin(GameRenderer.class)
public abstract class GameRendererCutsceneHandMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$noHandInCutscene(CallbackInfo ci) {
		if (GameOverCutscene.isActive() && !GameOverCutscene.showsHands()) {
			ci.cancel();
		}
	}

	@Redirect(method = "renderItemInHand", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;getPlayerMode()Lnet/minecraft/world/level/GameType;"))
	private GameType tacticalMovement$handsWhileSpectating(MultiPlayerGameMode gameMode) {
		GameType mode = gameMode.getPlayerMode();
		return mode == GameType.SPECTATOR && GameOverCutscene.showsHands() ? GameType.ADVENTURE : mode;
	}
}
