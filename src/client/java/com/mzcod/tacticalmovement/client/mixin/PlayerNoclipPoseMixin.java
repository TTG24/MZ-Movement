package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.NoclipClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Noclip: Minecraft drops a player whose standing body doesn't fit (under a ceiling, inside blocks) into the crawling
// pose. Spectators are exempt from that; noclip is too, so you stay upright while flying through blocks.
@Mixin(Player.class)
public abstract class PlayerNoclipPoseMixin {
	@Inject(method = "canPlayerFitWithinBlocksAndEntitiesWhen", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$noclipFits(Pose pose, CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof LocalPlayer && NoclipClient.active) {
			cir.setReturnValue(true);
		}
	}
}
