package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.debug.TacticalPoseDebug;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerMixin {
	/** The model's space when the arm's item started (for turning a gun along the player's look). */
	@org.spongepowered.asm.mixin.Unique
	private static final org.joml.Matrix4f tacticalMovement$model = new org.joml.Matrix4f();

	@Inject(method = "submitArmWithItem", at = @At("HEAD"))
	private void tacticalMovement$rememberModel(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack itemStack,
		HumanoidArm arm, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords, CallbackInfo ci) {
		tacticalMovement$model.set(poseStack.last().pose());
	}

	// A MinecraftZombies gun: pointed along the player's look (or across the body when sprinting), with its animations
	// (GunAnim); the arms are posed for it in PlayerModelMixin
	@Inject(
		method = "submitArmWithItem",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"
		)
	)
	private void tacticalMovement$aimGun(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack itemStack,
		HumanoidArm arm, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords, CallbackInfo ci) {
		if (!(state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState avatar) || arm != state.mainArm) {
			return;
		}
		com.mzcod.tacticalmovement.client.animation.GunAnim.Style style = com.mzcod.tacticalmovement.client.animation.GunAnim.style(itemStack);
		if (style == null || !com.mzcod.tacticalmovement.client.animation.GunAnim.posed(avatar)) {
			return;
		}
		float sprint = ((com.mzcod.tacticalmovement.client.animation.TacticalRenderStateAccess) avatar).tacticalMovement$getSprintBlend();
		org.joml.Vector3f forward = new org.joml.Vector3f(), up = new org.joml.Vector3f();
		com.mzcod.tacticalmovement.client.animation.GunAnim.thirdPersonAim(avatar, style, sprint, forward, up);
		tacticalMovement$model.transformDirection(forward);
		tacticalMovement$model.transformDirection(up);
		com.mzcod.tacticalmovement.client.animation.GunAnim.align(poseStack, forward, up);
	}
	// Flips the blade edge of swords held by players so the sword appears gripped
	// backwards (reverse grip): rotate the item render 180 degrees about the blade
	// axis (item-local-Y, which maps to +Z after the layer's base XP -90 / YP 180
	// rotations). During an attack the blade is also swept about the render-frame X
	// axis (world left-right), so the cut is horizontal (side to side) rather than
	// downwards; the arm pose itself is unchanged.
	@Inject(
		method = "submitArmWithItem",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"
		)
	)
	private void tacticalMovement$flipSwordBlade(
		ArmedEntityRenderState state,
		ItemStackRenderState item,
		ItemStack itemStack,
		HumanoidArm arm,
		PoseStack poseStack,
		SubmitNodeCollector submitNodeCollector,
		int lightCoords,
		CallbackInfo ci
	) {
		if (state.entityType == EntityTypes.PLAYER && itemStack.is(com.mzcod.tacticalmovement.TacticalItems.MELEE)) {
			poseStack.rotateDegrees(Axis.ZP, 180.0F);
			// Live-tunable sword hold offset (move the handle onto the arm). Applied AFTER the
			// reverse-grip flip + swing so it sits in the sword's local frame. All-zero = vanilla.
			poseStack.translate(TacticalPoseDebug.swordHoldX, TacticalPoseDebug.swordHoldY, TacticalPoseDebug.swordHoldZ);
			poseStack.rotateDegrees(Axis.XP, TacticalPoseDebug.swordHoldXR);
			poseStack.rotateDegrees(Axis.YP, TacticalPoseDebug.swordHoldYR);
			poseStack.rotateDegrees(Axis.ZP, TacticalPoseDebug.swordHoldZR);
		}
	}
}
