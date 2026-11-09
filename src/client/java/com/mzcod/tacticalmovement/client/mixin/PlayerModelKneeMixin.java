package com.mzcod.tacticalmovement.client.mixin;

import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.player.PlayerModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Adds a KNEE joint to the player model without changing how the skin looks.
//
// Each 4x12x4 leg cube is replaced by two stacked 4x6x4 cubes: a thigh (the original leg part, so every
// existing reference to rightLeg/leftLeg keeps working) and a shin that is a CHILD part pivoting at the
// knee (y=6). The shin's texture offset is the thigh's, moved down 6 pixels, which makes each of its side
// faces read exactly the lower half of the original leg texture -- so the unbent leg is pixel-identical
// to vanilla. The pants overlay is split the same way (the lower half is a child of the shin so it bends
// with it). With the legs straight (the default) nothing looks different; the tactical crouch rotates the
// shin to bend the knee.
//
// Only the faces that open up inside a bent knee can look slightly off (they sample a neighbouring part
// of the leg texture); that is the unavoidable cost of keeping the skin mapping untouched.
@Mixin(PlayerModel.class)
public abstract class PlayerModelKneeMixin {
	@Inject(method = "createMesh", at = @At("RETURN"))
	private static void tacticalMovement$addKnees(CubeDeformation deformation, boolean slim, CallbackInfoReturnable<MeshDefinition> cir) {
		PartDefinition root = cir.getReturnValue().getRoot();
		// right leg: skin (0,16), pants (0,32); left leg: skin (16,48), pants (0,48)
		tacticalMovement$splitLeg(root, deformation, "right_leg", "right_pants", "right_shin", "right_shin_pants", -1.9F, 0, 16, 0, 32);
		tacticalMovement$splitLeg(root, deformation, "left_leg", "left_pants", "left_shin", "left_shin_pants", 1.9F, 16, 48, 0, 48);
	}

	private static void tacticalMovement$splitLeg(
		PartDefinition root,
		CubeDeformation deformation,
		String legName,
		String pantsName,
		String shinName,
		String shinPantsName,
		float hipX,
		int legU,
		int legV,
		int pantsU,
		int pantsV
	) {
		CubeDeformation pantsDeformation = deformation.extend(0.25F);
		PartDefinition leg = root.addOrReplaceChild(
			legName,
			CubeListBuilder.create().texOffs(legU, legV).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 6.0F, 4.0F, deformation),
			PartPose.offset(hipX, 12.0F, 0.0F)
		);
		leg.addOrReplaceChild(
			pantsName,
			CubeListBuilder.create().texOffs(pantsU, pantsV).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 6.0F, 4.0F, pantsDeformation),
			PartPose.ZERO
		);
		PartDefinition shin = leg.addOrReplaceChild(
			shinName,
			CubeListBuilder.create().texOffs(legU, legV + 6).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 6.0F, 4.0F, deformation),
			PartPose.offset(0.0F, 6.0F, 0.0F)
		);
		shin.addOrReplaceChild(
			shinPantsName,
			CubeListBuilder.create().texOffs(pantsU, pantsV + 6).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 6.0F, 4.0F, pantsDeformation),
			PartPose.ZERO
		);
	}
}
