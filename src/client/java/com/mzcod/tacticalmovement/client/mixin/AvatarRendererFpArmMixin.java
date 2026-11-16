package com.mzcod.tacticalmovement.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mzcod.tacticalmovement.client.cutscene.body.PlayerRig;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The first-person arm with an elbow that bends smoothly (PlayerRig.fpElbow), drawn with the skin and sleeve the same way
// vanilla draws the arm box. Everything that places the arm (the sword pose, sway, inspect) works as before.
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererFpArmMixin {
	@Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$bentArm(PoseStack poseStack, SubmitNodeCollector collector, int light, Identifier skin, ModelPart arm, boolean sleeve,
		CallbackInfo ci) {
		PlayerModel model = (PlayerModel) ((AvatarRenderer<?>) (Object) this).getModel();
		boolean right = arm == model.rightArm;
		// As vanilla: the arm at rest, tilted out a touch
		arm.resetPose();
		arm.zRot = right ? 0.1F : -0.1F;
		PlayerRig.ArmShape shape = PlayerRig.armShape(model, arm, right);
		if (shape == null) {
			return;
		}
		collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(skin), (pose, vc) -> shape.draw(pose, vc, light, sleeve));
		ci.cancel();
	}
}
