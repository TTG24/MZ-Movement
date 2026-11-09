package com.mzcod.tacticalmovement.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mzcod.tacticalmovement.client.animation.SwordSway;
import com.mzcod.tacticalmovement.client.debug.TacticalPoseDebug;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Replaces vanilla's walking view-bob with the custom bob for EVERYTHING (any item or empty hand),
// when TacticalPoseDebug.customBob is on (toggle: /tacticaledbg custombob on|off). Off = vanilla bob.
//  - renderItemInHand (first-person hand/item pose): vanilla bob skipped; the custom sway is applied
//    to the whole hand pose instead, so arm + item (including the tactical sword) move together.
//  - renderLevel (world camera): swapped for a small custom bob on the same clock (SwordSway) so the
//    view and the hand move in step.
@Mixin(GameRenderer.class)
public abstract class GameRendererBobMixin {
	@Shadow
	protected abstract void bobView(CameraRenderState camera, PoseStack poseStack);

	@Redirect(
		method = "renderItemInHand",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"
		)
	)
	private void tacticalMovement$customHandBob(GameRenderer self, CameraRenderState camera, PoseStack poseStack) {
		if (!TacticalPoseDebug.customBob) {
			this.bobView(camera, poseStack);
			com.mzcod.tacticalmovement.client.animation.FpStanceAnim.applyHands(poseStack);
			return;
		}
		com.mzcod.tacticalmovement.client.animation.FpStanceAnim.applyHands(poseStack);
		Minecraft mc = Minecraft.getInstance();
		SwordSway.update();
		int side = mc.player != null && mc.player.getMainArm() == HumanoidArm.LEFT ? -1 : 1;
		// Fade the sway out during an attack swing so it doesn't fight the attack animation.
		float attackFade = 1.0F;
		if (mc.player != null) {
			attackFade = 1.0F - Mth.clamp(Mth.sin(mc.player.getSwingAnimation(1.0F) * Mth.PI) * 3.0F, 0.0F, 1.0F);
		}
		float s = SwordSway.speed() * TacticalPoseDebug.fpSwayAmp * attackFade;
		float a = Mth.sin(SwordSway.phase());
		float b = Mth.sin(SwordSway.phase() * 2.0F);
		poseStack.translate(side * 0.012F * a * s, 0.010F * b * s, 0.0F);
		poseStack.rotateDegrees(Axis.ZP, side * 2.0F * a * s);
		poseStack.rotateDegrees(Axis.XP, 1.2F * b * s);
	}

	@Redirect(
		method = "renderLevel",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"
		)
	)
	private void tacticalMovement$customCameraBob(GameRenderer self, CameraRenderState camera, PoseStack poseStack) {
		com.mzcod.tacticalmovement.client.animation.FpStanceAnim.applyCamera(poseStack);
		Minecraft mcd = Minecraft.getInstance();
		if (mcd.player instanceof com.mzcod.tacticalmovement.movement.MovementStateAccess st
			&& st.tacticalMovement$getStance() == com.mzcod.tacticalmovement.movement.MovementStance.DOWNED) {
			// Downed: the view drifts in a slow, uneven figure-eight, as if the player is barely holding on.
			float time = (mcd.player.tickCount + mcd.getDeltaTracker().getGameTimeDeltaPartialTick(false)) * 0.07F;
			float x = Mth.sin(time) + 0.3F * Mth.sin(time * 2.3F + 1.0F);
			float y = Mth.sin(time) * Mth.cos(time) * 2.0F + 0.2F * Mth.sin(time * 1.7F);
			poseStack.translate(0.035F * x, 0.025F * y, 0.0F);
			poseStack.rotateDegrees(Axis.ZP, 1.6F * x);
			poseStack.rotateDegrees(Axis.XP, 0.9F * y);
			return;
		}
		if (!TacticalPoseDebug.customBob) {
			this.bobView(camera, poseStack);
			return;
		}
		SwordSway.update();
		float s = SwordSway.speed() * TacticalPoseDebug.fpCameraBobAmp;
		float phase = SwordSway.phase();
		// Gentle side-to-side drift (once per sway cycle) with a small step bounce (twice per cycle),
		// matching the hand sway's rhythm.
		poseStack.translate(0.02F * Mth.sin(phase) * s, -0.02F * (0.5F + 0.5F * Mth.cos(phase * 2.0F)) * s, 0.0F);
		poseStack.rotateDegrees(Axis.ZP, 0.8F * Mth.sin(phase) * s);
		poseStack.rotateDegrees(Axis.XP, 0.6F * (0.5F + 0.5F * Mth.cos(phase * 2.0F)) * s);
	}
}
