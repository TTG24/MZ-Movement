package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.NoclipClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Noclip: Player.tick has just set noPhysics from the gamemode (spectator only); while noclip is on and the player is
// flying it is set again here, before the movement, so they pass through blocks in creative. noPhysics also stops the
// push out of blocks and the "inside a block" screen overlay. Noclip keeps them flying the whole time.
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerNoclipMixin {
	@Inject(method = "aiStep", at = @At("HEAD"))
	private void tacticalMovement$noclip(CallbackInfo ci) {
		LocalPlayer self = (LocalPlayer) (Object) this;
		if (NoclipClient.active && self.isCreative()) {
			// Noclip always flies (until it's switched off): no double-tap or landing turns flight off
			self.getAbilities().mayfly = true;
			self.getAbilities().flying = true;
			self.noPhysics = true;
			// With noPhysics, moving never updates onGround, so it stayed true from before noclip went on: aiStep then
			// switched flying off each tick (creative landing) before moving, and the player walked instead of flying
			self.setOnGround(false);
		}
	}

	@Inject(method = "aiStep", at = @At("TAIL"))
	private void tacticalMovement$keepFlying(CallbackInfo ci) {
		LocalPlayer self = (LocalPlayer) (Object) this;
		if (NoclipClient.active && self.isCreative() && !self.getAbilities().flying) {
			self.getAbilities().flying = true;
			self.onUpdateAbilities();
		}
	}
}
