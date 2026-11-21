package com.mzcod.tacticalmovement.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mzcod.tacticalmovement.client.animation.SwordSway;
import com.mzcod.tacticalmovement.client.debug.TacticalPoseDebug;
import com.mzcod.tacticalmovement.movement.MovementStance;
import com.mzcod.tacticalmovement.movement.MovementStateAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsRendererMixin {
	@Shadow
	protected abstract void renderPlayerArm(
		PoseStack poseStack,
		SubmitNodeCollector submitNodeCollector,
		int lightCoords,
		float inverseArmHeight,
		float attackValue,
		HumanoidArm arm,
		PlayerRenderState playerState
	);

	// The MinecraftZombies guns: held in both hands and animated (GunFirstPerson / GunAnim)
	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$gun(
		PlayerRenderState playerState,
		FirstPersonHandsAndItemsRenderState state,
		float partialTicks,
		float xRot,
		InteractionHand hand,
		float attack,
		ItemStack itemStack,
		float inverseArmHeight,
		PoseStack poseStack,
		SubmitNodeCollector submitNodeCollector,
		int lightCoords,
		CallbackInfo ci
	) {
		// drinking a perk: the bottle in the gun hand, nothing in the other (the bottle sits in the off hand)
		if (net.minecraft.client.Minecraft.getInstance().player != null
			&& com.mzcod.tacticalmovement.client.animation.GunAnim.drink(net.minecraft.client.Minecraft.getInstance().player.getId()) >= 0) {
			if (hand == InteractionHand.MAIN_HAND) {
				com.mzcod.tacticalmovement.client.animation.GunFirstPerson.renderDrink(playerState, itemStack, poseStack, submitNodeCollector,
					lightCoords, (ps, arm) -> this.renderPlayerArm(ps, submitNodeCollector, lightCoords, inverseArmHeight, 0.0F, arm, playerState));
			}
			ci.cancel();
			return;
		}
		if (hand == InteractionHand.MAIN_HAND && !state.isScoping
			&& com.mzcod.tacticalmovement.client.animation.GunFirstPerson.render(playerState, itemStack, poseStack, submitNodeCollector,
				lightCoords, (ps, arm) -> this.renderPlayerArm(ps, submitNodeCollector, lightCoords, inverseArmHeight, 0.0F, arm, playerState))) {
			ci.cancel();
		}
	}

	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$tacticalSword(
		PlayerRenderState playerState,
		FirstPersonHandsAndItemsRenderState state,
		float partialTicks,
		float xRot,
		InteractionHand hand,
		float attack,
		ItemStack itemStack,
		float inverseArmHeight,
		PoseStack poseStack,
		SubmitNodeCollector submitNodeCollector,
		int lightCoords,
		CallbackInfo ci
	) {
		// A relaxed elbow for anything else held (the sword path below sets its own)
		com.mzcod.tacticalmovement.client.cutscene.body.PlayerRig.fpElbow = 0.22F;
		if (hand == InteractionHand.MAIN_HAND
			&& itemStack.is(com.mzcod.tacticalmovement.TacticalItems.MELEE)
			&& !state.isScoping
			&& playerState.avatarRenderState != null
			&& Minecraft.getInstance().player != null) {
			HumanoidArm mainArm = Minecraft.getInstance().player.getMainArm();
			int side = mainArm == HumanoidArm.RIGHT ? 1 : -1;
			// Horizontal slash (Black Ops/WaW-style knife melee): the forearm sweeps across the view from
			// the outside edge toward the center while the blade tilts. env fades the whole motion in/out so
			// the arm leaves and returns to its fparm rest pose smoothly; u runs +1 (start) -> -1 (end).
			float p = com.mzcod.tacticalmovement.client.animation.SmoothSwing.of(com.mzcod.tacticalmovement.client.animation.SmoothSwing.FIRST_PERSON,
				Mth.clamp(attack, 0.0F, 1.0F)); // the smooth slash (wind-up, cut, follow-through)
			float t = Mth.clamp(Mth.sin(p * Mth.PI) * 3.0F, 0.0F, 1.0F);
			float env = t * t * (3.0F - 2.0F * t);
			float u = (1.0F - 2.0F * p) * env;
			float push = Mth.sin(p * Mth.PI);

			// Sword-only CRAWL animation (separate from the generic bob): while PRONE the arm rolls about its
			// own axis and reaches forward/back with each step, so the player reads as crawling forward.
			// Does nothing when not prone; scaled by crawlMult, faded out during an attack, driven by the
			// shared clock.
			SwordSway.update();
			com.mzcod.tacticalmovement.client.animation.FpRigAnim.update();
			float sprint = com.mzcod.tacticalmovement.client.animation.FpRigAnim.sprint() * (1.0F - env);
			float walk = SwordSway.speed() * (1.0F - env);
			MovementStance fpStance = ((MovementStateAccess) Minecraft.getInstance().player).tacticalMovement$getStance();
			com.mzcod.tacticalmovement.client.animation.FpCrawl.update();
			if (fpStance == MovementStance.PRONE) {
				walk = 0.0F; // the crawl (FpCrawl) moves the arms instead
			} else if (fpStance == MovementStance.STANDING) {
				// Walking on your feet: a gentler version of the same sway (the arm rolls and reaches with each step)
				walk *= 0.45F * (1.0F - sprint);
			} else {
				walk = 0.0F;
			}
			float walkRoll = side * TacticalPoseDebug.fpWalkRoll * Mth.sin(SwordSway.phase()) * walk;
			float walkReach = -TacticalPoseDebug.fpWalkReach * Mth.cos(SwordSway.phase()) * walk;

			poseStack.pushPose();
			// Prone: the sword arm crawls (and pushes you up when you get up)
			com.mzcod.tacticalmovement.client.animation.FpCrawl.applyArm(poseStack, side, true, env);
			// Sprinting: the sword arm drops and swings down and in, the blade trailing low, bouncing with the stride;
			// the elbow bends more (the arm pumps). Walking bends it with each step; standing still it breathes.
			float ph = SwordSway.phase();
			if (sprint > 0.001F) {
				// Sprinting: the arm swings side to side across the body with each stride (no lifting), dipping a little
				// at each footfall
				float sway = Mth.sin(ph);
				float dip = Math.abs(Mth.cos(ph)) * 0.025F * sprint;
				poseStack.translate(side * 0.02F * sprint - sway * 0.07F * sprint, -0.04F * sprint - dip, 0.0F);
				poseStack.rotateDegrees(Axis.YP, -sway * 11.0F * sprint);
				poseStack.rotateDegrees(Axis.ZP, side * Mth.cos(ph) * 4.0F * sprint);
			}
			com.mzcod.tacticalmovement.client.cutscene.body.PlayerRig.fpElbow = 0.22F + Mth.sin(ph) * 0.08F * walk
				+ Mth.sin(Minecraft.getInstance().player.tickCount * 0.09F) * 0.02F;
			com.mzcod.tacticalmovement.client.cutscene.body.PlayerRig.fpElbow = com.mzcod.tacticalmovement.client.animation.FpCrawl.elbow(
				com.mzcod.tacticalmovement.client.cutscene.body.PlayerRig.fpElbow, true, env);
			// Crouch: pull the sword arm in toward the chest (scaled by the crouch blend).
			com.mzcod.tacticalmovement.client.animation.FpStanceAnim.applyCrouchSwordArm(poseStack, side);
			com.mzcod.tacticalmovement.client.animation.SwordInspect.applyArm(poseStack, side);
			// Sweep as an arc around the camera's vertical axis (like the third-person swing): the arm AND
			// the sword (parented below) rotate together, so the blade keeps its orientation to the arm
			// instead of being distorted by a big sideways slide. Start = outside, end = across the center.
			poseStack.rotateDegrees(Axis.YP, -side * TacticalPoseDebug.fpSlashYaw * u);
			poseStack.translate(
				side * (-0.46F + TacticalPoseDebug.fpArmX + TacticalPoseDebug.fpSlashX * u),
				0.16F + TacticalPoseDebug.fpArmY + TacticalPoseDebug.fpSlashLift * env,
				0.10F + TacticalPoseDebug.fpArmZ + TacticalPoseDebug.fpSlashForward * push + walkReach
			);
			poseStack.rotateDegrees(Axis.XP, -12.0F + TacticalPoseDebug.fpArmXR);
			poseStack.rotateDegrees(Axis.YP, side * (-12.0F + TacticalPoseDebug.fpArmYR));
			poseStack.rotateDegrees(Axis.ZP, side * (42.0F + TacticalPoseDebug.fpArmZR) + walkRoll);
			// renderPlayerArm has no push/pop of its own: it leaves vanilla's internal first-person arm
			// transform on the stack, i.e. the stack is now exactly in the arm model's frame. Keep going
			// from there, the same way the third-person ItemInHandLayer does, so the sword is attached to
			// the model arm's hand and follows every arm movement.
			this.renderPlayerArm(poseStack, submitNodeCollector, lightCoords, inverseArmHeight, 0.0F, mainArm, playerState);
			var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(playerState.avatarRenderState);
			((PlayerModel) (Object) renderer.getModel()).translateToHand(playerState.avatarRenderState, mainArm, poseStack);
			// Same grip transform as ItemInHandLayer (3p): XP -90, YP 180, then the hand offset.
			poseStack.rotateDegrees(Axis.XP, -90.0F);
			poseStack.rotateDegrees(Axis.YP, 180.0F);
			poseStack.translate(side / 16.0F, 0.125F, -0.625F);

			// Live-tunable offset from the grip (/tacticaledbg fpsword). All 0 = the 3p grip placement.
			poseStack.translate(
				side * TacticalPoseDebug.fpSwordX,
				TacticalPoseDebug.fpSwordY,
				TacticalPoseDebug.fpSwordZ
			);
			poseStack.rotateDegrees(Axis.YP, side * TacticalPoseDebug.fpSwordYaw);
			poseStack.rotateDegrees(Axis.XP, TacticalPoseDebug.fpSwordPitch);
			poseStack.rotateDegrees(Axis.ZP, side * (TacticalPoseDebug.fpSwordRoll + TacticalPoseDebug.fpSlashRoll * u));
			// Slide the item along its blade axis (item-local diagonal).
			poseStack.translate(TacticalPoseDebug.fpSwordSlide * 0.7071F, TacticalPoseDebug.fpSwordSlide * 0.7071F, 0.0F);
			state.mainHandRenderState.submit(poseStack, submitNodeCollector, lightCoords, OverlayTexture.NO_OVERLAY, 0);
			poseStack.popPose();
			// Prone: the off hand crawls too (and pushes you up), mirrored from the sword arm's resting place
			if (com.mzcod.tacticalmovement.client.animation.FpCrawl.showOffHand()
				&& com.mzcod.tacticalmovement.client.animation.SwordInspect.offWeight() <= 0.001F
				&& Minecraft.getInstance().player.getOffhandItem().isEmpty()) {
				int o = -side;
				poseStack.pushPose();
				com.mzcod.tacticalmovement.client.animation.FpCrawl.applyArm(poseStack, o, false, 0.0F);
				poseStack.translate(o * (-0.46F + TacticalPoseDebug.fpArmX), 0.16F + TacticalPoseDebug.fpArmY, 0.10F + TacticalPoseDebug.fpArmZ);
				poseStack.rotateDegrees(Axis.XP, -12.0F + TacticalPoseDebug.fpArmXR);
				poseStack.rotateDegrees(Axis.YP, o * (-12.0F + TacticalPoseDebug.fpArmYR));
				poseStack.rotateDegrees(Axis.ZP, o * (42.0F + TacticalPoseDebug.fpArmZR));
				com.mzcod.tacticalmovement.client.cutscene.body.PlayerRig.fpElbow = com.mzcod.tacticalmovement.client.animation.FpCrawl.elbow(0.22F, false, 0.0F);
				this.renderPlayerArm(poseStack, submitNodeCollector, lightCoords, inverseArmHeight, 0.0F, mainArm.getOpposite(), playerState);
				poseStack.popPose();
				com.mzcod.tacticalmovement.client.cutscene.body.PlayerRig.fpElbow = 0.22F;
			}
			// Inspect: the other arm comes up to hold the tip of the blade.
			if (com.mzcod.tacticalmovement.client.animation.SwordInspect.offWeight() > 0.001F) {
				poseStack.pushPose();
				com.mzcod.tacticalmovement.client.animation.SwordInspect.applyOffArm(poseStack, side);
				this.renderPlayerArm(poseStack, submitNodeCollector, lightCoords, inverseArmHeight, 0.0F, mainArm.getOpposite(), playerState);
				poseStack.popPose();
			}
			ci.cancel();
		}
	}
}