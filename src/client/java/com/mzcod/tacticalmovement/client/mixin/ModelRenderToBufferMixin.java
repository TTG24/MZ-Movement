package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.animation.TacticalAnimations;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.player.PlayerModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The 26.3 render path (ModelFeatureRenderer.prepareModel) calls setupAnim() and
// then renderToBuffer(). Because Model.setupAnim's base implementation calls
// resetPose() (which snaps every part back to its baked offset), and a second
// setupAnim can run for the render layers, a pose written at the TAIL of
// setupAnim can be clobbered before the model is actually drawn. Re-applying the
// tactical pose at the HEAD of renderToBuffer — immediately before root.render() —
// guarantees it is the final state the vertex builder sees. reapply() writes the
// exact pose cached at setupAnim, so it is idempotent and never re-blends a
// transition twice.
@Mixin(Model.class)
public abstract class ModelRenderToBufferMixin {

	@Inject(method = "renderToBuffer", at = @At("HEAD"))
	private void tacticalMovement$reapplyPose(
		PoseStack poseStack,
		VertexConsumer buffer,
		int lightCoords,
		int overlayCoords,
		int color,
		CallbackInfo ci
	) {
		if ((Object) this instanceof PlayerModel playerModel) {
			TacticalAnimations.reapply(playerModel);
		}
	}
}
