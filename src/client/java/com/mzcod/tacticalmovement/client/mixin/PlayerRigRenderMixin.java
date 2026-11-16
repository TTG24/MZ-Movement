package com.mzcod.tacticalmovement.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mzcod.tacticalmovement.client.animation.TacticalAnimations;
import com.mzcod.tacticalmovement.client.cutscene.body.PlayerRig;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.player.PlayerModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Every player model (you in third person, other players, the cutscene copies) is drawn with the bending body: the model is
// posed exactly as before, then drawn from its joints with smooth elbows and knees instead of as boxes.
@Mixin(value = Model.class, priority = 1500)
public abstract class PlayerRigRenderMixin {
	@Inject(method = "renderToBuffer", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$drawRigged(PoseStack poseStack, VertexConsumer buffer, int lightCoords, int overlayCoords, int color, CallbackInfo ci) {
		if ((Object) this instanceof PlayerModel model) {
			TacticalAnimations.reapply(model); // the final tactical pose (idempotent)
			if (PlayerRig.draw(model, poseStack, buffer, lightCoords, overlayCoords, color)) {
				ci.cancel();
			}
		}
	}
}
