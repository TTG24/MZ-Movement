package com.mzcod.tacticalmovement.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Adds an ELBOW to the player model, the same way PlayerModelKneeMixin adds knees: each 12px arm becomes an upper arm (the
// original part, so everything that uses rightArm/leftArm still works) and a forearm child pivoting at the elbow (6px down),
// its texture the lower half of the arm's, so a straight arm looks exactly like vanilla. Sleeves split the same way. Held
// items follow the forearm (translateToHand), so a bent elbow carries the sword with it.
@Mixin(PlayerModel.class)
public abstract class PlayerModelElbowMixin implements com.mzcod.tacticalmovement.client.cutscene.body.PlayerRig.SlimAccess {
	@org.spongepowered.asm.mixin.Shadow
	@org.spongepowered.asm.mixin.Final
	private boolean slim;

	@Override
	public boolean tacticalMovement$slim() {
		return this.slim;
	}

	@Inject(method = "createMesh", at = @At("RETURN"))
	private static void tacticalMovement$addElbows(CubeDeformation deformation, boolean slim, CallbackInfoReturnable<MeshDefinition> cir) {
		PartDefinition root = cir.getReturnValue().getRoot();
		float w = slim ? 3.0F : 4.0F;
		// right arm: box x from -3 (wide) / -2 (slim); left arm from -1; skin (40,16)/(32,48), sleeves (40,32)/(48,48)
		tacticalMovement$split(root, deformation, "right_arm", "right_sleeve", "right_forearm", "right_forearm_sleeve", slim ? -2.0F : -3.0F, w,
			-5.0F, slim ? 2.5F : 2.0F, 40, 16, 40, 32);
		tacticalMovement$split(root, deformation, "left_arm", "left_sleeve", "left_forearm", "left_forearm_sleeve", -1.0F, w, 5.0F,
			slim ? 2.5F : 2.0F, 32, 48, 48, 48);
	}

	private static void tacticalMovement$split(PartDefinition root, CubeDeformation deformation, String armName, String sleeveName, String foreName,
		String foreSleeveName, float x0, float w, float px, float py, int u, int v, int su, int sv) {
		CubeDeformation sleeveDef = deformation.extend(0.25F);
		PartDefinition arm = root.addOrReplaceChild(armName,
			CubeListBuilder.create().texOffs(u, v).addBox(x0, -2.0F, -2.0F, w, 6.0F, 4.0F, deformation), PartPose.offset(px, py, 0.0F));
		arm.addOrReplaceChild(sleeveName, CubeListBuilder.create().texOffs(su, sv).addBox(x0, -2.0F, -2.0F, w, 6.0F, 4.0F, sleeveDef), PartPose.ZERO);
		PartDefinition fore = arm.addOrReplaceChild(foreName,
			CubeListBuilder.create().texOffs(u, v + 6).addBox(x0, 0.0F, -2.0F, w, 6.0F, 4.0F, deformation), PartPose.offset(0.0F, 4.0F, 0.0F));
		fore.addOrReplaceChild(foreSleeveName, CubeListBuilder.create().texOffs(su, sv + 6).addBox(x0, 0.0F, -2.0F, w, 6.0F, 4.0F, sleeveDef),
			PartPose.ZERO);
	}

	// Held items go to the hand at the end of the FOREARM: through the elbow, then back up the 4px the forearm's pivot sits
	// below the arm's, so the item's usual offset (measured from the shoulder) lands it in the bent hand.
	@Inject(method = "translateToHand(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
		at = @At("TAIL"))
	private void tacticalMovement$handOnForearm(AvatarRenderState state, HumanoidArm side, PoseStack poseStack, CallbackInfo ci) {
		ModelPart arm = ((PlayerModel) (Object) this).getArm(side);
		String name = side == HumanoidArm.RIGHT ? "right_forearm" : "left_forearm";
		if (arm.hasChild(name)) {
			arm.getChild(name).translateAndRotate(poseStack);
			poseStack.translate(0.0F, -0.25F, 0.0F);
		}
	}
}
