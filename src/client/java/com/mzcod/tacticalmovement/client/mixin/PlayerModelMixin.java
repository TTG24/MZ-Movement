package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.TacticalMovement;
import com.mzcod.tacticalmovement.client.animation.TacticalAnimations;
import com.mzcod.tacticalmovement.client.animation.TacticalRenderStateAccess;
import com.mzcod.tacticalmovement.client.debug.TacticalCrouchDebug;
import com.mzcod.tacticalmovement.client.debug.TacticalPoseDebug;
import com.mzcod.tacticalmovement.movement.MovementStance;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {
	private static net.minecraft.world.entity.LivingEntity.SwingDescription tacticalMovement$lastSwing;

	// Debug slow-motion: replace the swing progress BEFORE vanilla's setupAnim reads it, so vanilla's
	// own attack motion, the body twist and the tactical sword swing all play at the same slowed speed.
	// Local player holding a sword only; swordSwingSpeed = 1 leaves the value untouched.
	@Inject(method = "setupAnim", at = @At("HEAD"))
	private void tacticalMovement$slowSwing(AvatarRenderState state, CallbackInfo ci) {
		TacticalRenderStateAccess access = (TacticalRenderStateAccess) state;
		if (access.tacticalMovement$isLocalPlayer() && access.tacticalMovement$isHoldingSword()) {
			// Vanilla only runs its attack pose while currentSwing is set, and clears it when the
			// real (fast) swing ends. Remember it and keep it set while the slowed swing plays.
			if (state.currentSwing != null) {
				tacticalMovement$lastSwing = state.currentSwing;
			}
			state.swingAnimation = TacticalAnimations.slowSwing(state.swingAnimation);
			if (state.swingAnimation > 0.0F && state.currentSwing == null) {
				state.currentSwing = tacticalMovement$lastSwing;
			}
		}
	}

	// Sets the tactical arm/body rotations for the current stance and whether the
	// player is holding a sword. The vanilla arm cubes render normally (no custom
	// geometry); TacticalAnimations simply rotates them into the ready pose.
	@Inject(method = "setupAnim", at = @At("TAIL"))
	private void tacticalMovement$applyAnimation(AvatarRenderState state, CallbackInfo ci) {
		PlayerModel self = (PlayerModel) (Object) this;
		TacticalRenderStateAccess tacticalState = (TacticalRenderStateAccess) state;
		MovementStance stance = tacticalState.tacticalMovement$getStance();
		// Last stand reuses the prone pose, then raises the gun arm and bends a knee on top of it.
		boolean downed = stance == MovementStance.DOWNED;
		if (downed) {
			stance = MovementStance.PRONE;
		}
		float move = tacticalState.tacticalMovement$getProneMove();

		// Prone transition blend: 0 = standing (vanilla), 1 = full sculpted prone pose. It is
		// eased per client tick (tickStanceVisual) toward 1 while the stance is low-profile and
		// toward 0 otherwise, so entering/exiting prone animates smoothly instead of snapping.
		float blend = TacticalPoseDebug.enabled ? 1.0F : tacticalState.tacticalMovement$getProneBlend();
		// Apply the (blended) sculpted prone pose in the editor, while PRONE, or while fading
		// back out to standing. DIVING keeps its separate dive pose (handled in the branch below).
		boolean doPronePose = TacticalPoseDebug.enabled
			|| stance == MovementStance.PRONE
			|| (stance == MovementStance.STANDING && blend > 0.001F);

		// Capture the vanilla pose for the transition's start point. Since swimAmount is kept at 0
		// for the entire render pipeline (see AvatarRendererMixin), setupAnim uses the normal
		// walking/idle animation instead of the swimming animation. This means the vanilla pose
		// is always a natural, live-animating pose that we can use directly as the lerp target.
		// Layer the custom standing animation onto the vanilla pose FIRST, so the capture below (the blend's
		// "standing" endpoint) is the custom standing pose: entering/leaving crouch and prone then blends
		// to/from it instead of default Minecraft. Skipped for diving and the prone pose editor.
		if (stance != MovementStance.DIVING && !TacticalPoseDebug.enabled) {
			com.mzcod.tacticalmovement.client.debug.TacticalStandingDebug.apply(self, state,
				tacticalState.tacticalMovement$isHoldingSword());
		}
		TacticalPoseDebug.captureVanilla(self);
		if (doPronePose) {
			TacticalAnimations.reapplyEnabled = false;
			// The head billboards toward the camera whenever we're (nearly) prone in production,
			// so it tracks the camera smoothly through the transition. It must NOT depend on the
			// debug editor's headFollowsCamera toggle (which /tacticaledbg on + copy flips off).
			boolean forceHeadFollow = stance == MovementStance.PRONE || (!TacticalPoseDebug.enabled && blend > 0.001F);
			// Phase drives WHICH transition animation plays: DROP (entering prone) -> crouch,
			// GETUP (exiting prone) -> pull-up get-up. The editor forces blend=1 but stays NONE so
			// sculpting shows the pure sculpted pose (no transition motion).
			int phase = TacticalPoseDebug.PHASE_NONE;
			if (!TacticalPoseDebug.enabled) {
				if (stance == MovementStance.PRONE) {
					phase = TacticalPoseDebug.PHASE_DROP;
				} else if (stance == MovementStance.STANDING && blend > 0.001F) {
					phase = TacticalPoseDebug.PHASE_GETUP;
				}
			}
			// Crouch -> prone: the crouch blend is still fading out. Build the (blended) crouch pose first and
			// make it the start point of the prone blend, so the two stances cross-fade instead of snapping.
			float fadingCrouch = tacticalState.tacticalMovement$getCrouchBlend();
			if (stance == MovementStance.PRONE && fadingCrouch > 0.001F && !TacticalPoseDebug.enabled) {
				TacticalCrouchDebug.apply(self, fadingCrouch, tacticalState.tacticalMovement$getCrouchMove());
				TacticalPoseDebug.captureVanilla(self);
				// The DROP animation starts from its own standing pose, which would snap away from the crouch
				// we just captured: use the plain blend from the captured crouch pose instead.
				phase = TacticalPoseDebug.PHASE_NONE;
			}
			TacticalPoseDebug.apply(self, move, forceHeadFollow, blend, phase);
			// Straighten the knees as the prone blend takes over (the crouch bend was set at full strength).
			if (stance == MovementStance.PRONE && fadingCrouch > 0.001F && !TacticalPoseDebug.enabled) {
				float keep = 1.0F - Mth.clamp(blend, 0.0F, 1.0F);
				if (self.rightLeg.hasChild("right_shin")) {
					self.rightLeg.getChild("right_shin").xRot *= keep;
				}
				if (self.leftLeg.hasChild("left_shin")) {
					self.leftLeg.getChild("left_shin").xRot *= keep;
				}
			}
			// The head's YAW (turning left/right) already tracks the player's look via the live
			// vanilla rotation applied above. Its PITCH (tilting up/down) does not in the flat
			// prone body, so feed the live camera pitch in, SCALED BY THE BLEND so it fades in as
			// the player prones and out as they rise (local player only).
			this.tacticalMovement$trackHeadPitch(state, self, blend);
			if (downed) {
				this.tacticalMovement$applyDownedPose(self, blend);
			}
			// Prone sword attack: only while in the PRONE stance (a brief swing; transition edge
			// cases are rare enough to ignore).
			if (stance == MovementStance.PRONE
				&& tacticalState.tacticalMovement$isHoldingSword() && state.swingAnimation > 0.0F) {
				this.tacticalMovement$applyProneSwordSwing(state, self);
			}
			// Standing sword attack: only while in the STANDING stance (fully upright or during
			// the get-up transition so the arm follows the slash as it rises).
			if (stance == MovementStance.STANDING
				&& tacticalState.tacticalMovement$isHoldingSword() && state.swingAnimation > 0.0F) {
				this.tacticalMovement$applyStandingSwordSwing(state, self);
			}
			return;
		}

		// Tactical crouch: blend from the vanilla pose (captured above) to the sculpted crouch pose, and
		// keep blending back to vanilla after the stance flips to STANDING until the eased blend reaches 0.
		// reapplyEnabled is off so the cached production pose can't clobber it before render, exactly like
		// the prone path. A held-sword attack still plays on top of the crouch pose.
		float crouchBlend = tacticalState.tacticalMovement$getCrouchBlend();
		if (stance == MovementStance.CROUCHING || (stance == MovementStance.STANDING && crouchBlend > 0.001F)) {
			TacticalAnimations.reapplyEnabled = false;
			// Prone -> crouch: the prone blend is still fading out. Build the (blended) prone pose first and use
			// it as the start point of the crouch blend so the two stances cross-fade instead of snapping.
			float fadingProne = tacticalState.tacticalMovement$getProneBlend();
			if (stance == MovementStance.CROUCHING && fadingProne > 0.001F) {
				TacticalPoseDebug.apply(self, move, true, fadingProne, TacticalPoseDebug.PHASE_NONE);
				// Fade the prone head pitch out as the crouch takes over, so it doesn't pop when the prone blend is dropped.
				this.tacticalMovement$trackHeadPitch(state, self, fadingProne * (1.0F - Mth.clamp(crouchBlend, 0.0F, 1.0F)));
				TacticalPoseDebug.captureVanilla(self);
			}
			TacticalCrouchDebug.apply(self, crouchBlend, tacticalState.tacticalMovement$getCrouchMove());
			if (tacticalState.tacticalMovement$isHoldingSword() && state.swingAnimation > 0.0F) {
				// Crouch-specific swing (the standing swing assumes hanging arms and overshoots here).
				TacticalCrouchDebug.applySwing(self, state.mainArm, state.swingAnimation);
			}
			return;
		}

		// Standing / diving: production animation (vanish-to-vanilla + sword slash, dive leap).
		TacticalAnimations.reapplyEnabled = true;
		TacticalAnimations.apply(self, stance,
			tacticalState.tacticalMovement$isHoldingSword(), state.mainArm, state.swingAnimation, state.ageInTicks,
			tacticalState.tacticalMovement$getProneBlend(), move);
	}

	// Last stand, layered on the prone pose: the gun arm is raised off the ground and aimed ahead, the support arm stays
	// down, and one knee is drawn up (offsets in DownedTune, scaled by the transition blend so they ease in).
	private void tacticalMovement$applyDownedPose(PlayerModel model, float blend) {
		float b = Mth.clamp(blend, 0.0F, 1.0F);
		model.rightArm.xRot += com.mzcod.tacticalmovement.client.animation.DownedTune.rightArm * b;
		model.rightArm.zRot -= 0.05F * b;
		model.leftArm.xRot += com.mzcod.tacticalmovement.client.animation.DownedTune.leftArm * b;
		model.rightLeg.xRot -= com.mzcod.tacticalmovement.client.animation.DownedTune.rightLeg * b;
		if (com.mzcod.tacticalmovement.client.animation.DownedTune.rightShin != 0.0F && model.rightLeg.hasChild("right_shin")) {
			model.rightLeg.getChild("right_shin").xRot += com.mzcod.tacticalmovement.client.animation.DownedTune.rightShin * b;
		}
	}

	// Add the live camera PITCH to the prone head's pitch rotation so the head tilts up/down with
	// the player's view. The head's yaw already follows the player's look (via the vanilla
	// headcam rotation applied in TacticalPoseDebug.apply); only the up/down (pitch) axis is
	// missing, because in the prone pose the body is locked flat and can't carry it. This is the
	// local player's model only -- other players keep their own look direction. The factor is
	// live-tunable via /tacticaledbg headpitch <f> (1=full, 0=off, -1=inverted) so the correct
	// sign/magnitude can be dialed in without recompiling.
	private void tacticalMovement$trackHeadPitch(AvatarRenderState state, PlayerModel model, float blend) {
		TacticalRenderStateAccess access = (TacticalRenderStateAccess) state;
		if (!access.tacticalMovement$isLocalPlayer()) {
			return;
		}
		// Scale by the transition blend so the camera-pitch + resting-offset fade in as the
		// player prones and out as they rise (0 when standing, full when prone).
		float b = blend < 0.0F ? 0.0F : (blend > 1.0F ? 1.0F : blend);
		if (b < 0.001F) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameRenderer == null || mc.level == null) {
			return;
		}
		Camera cam = mc.gameRenderer.mainCamera();
		if (cam == null) {
			return;
		}
		float degToRad = (float) (Math.PI / 180.0);
		float factor = TacticalPoseDebug.headPitchFactor;
		float offsetDeg = TacticalPoseDebug.headPitchOffset;
		// Tracking (follows the camera up/down) + resting offset (shifts the base pitch), both
		// scaled by b so they fade with the transition into/out of prone.
		model.head.xRot += (cam.xRot() * factor + offsetDeg) * degToRad * b;
	}

	// Prone sword swing: sweeps the sword arm from OUTWARD (out to the side) to INWARD (across the
	// body) over the attack swing. The arm is a child of the (flat) body, so its zRot rotates it
	// around the world vertical -- the same "outward splay" channel the prone pose already uses --
	// giving a clean side-to-side sweep. Driven by the per-player swingAnimation (0 -> 1), so it
	// only plays while that player is attacking. A small mid-swing xRot lift adds arc readability.
	// Tuned live via /tacticaledbg swordamp <rad> (sweep) and /tacticaledbg swordlift <rad> (lift).
	private void tacticalMovement$applyProneSwordSwing(AvatarRenderState state, PlayerModel model) {
		float swing = Mth.clamp(state.swingAnimation, 0.0F, 1.0F);
		var swordArm = model.getArm(state.mainArm);
		int side = state.mainArm == HumanoidArm.RIGHT ? 1 : -1;
		float amp = TacticalPoseDebug.proneSwordSwingAmp;
		// OUTWARD (swing=0) -> INWARD (swing=1): side * +amp -> side * -amp, a horizontal arc.
		swordArm.zRot += side * amp * (1.0F - 2.0F * swing);
		// Optional mid-swing lift (0 = pure flat sweep); sign tunable via a negative swordlift.
		float lift = TacticalPoseDebug.proneSwordSwingLift;
		if (lift != 0.0F) {
			swordArm.xRot -= lift * Mth.sin(swing * Mth.PI);
		}
	}

	// Standing sword swing: horizontal side-to-side sweep (zRot) with optional forward thrust
	// (xRot) at mid-swing. Same pattern as the prone swing but in the standing coordinate frame.
	// Applied in the debug path so it can be tested with the pose editor.
	private void tacticalMovement$applyStandingSwordSwing(AvatarRenderState state, PlayerModel model) {
		float swing = Mth.clamp(state.swingAnimation, 0.0F, 1.0F);
		var swordArm = model.getArm(state.mainArm);
		int side = state.mainArm == HumanoidArm.RIGHT ? 1 : -1;
		float amp = TacticalPoseDebug.standingSwordSwingAmp;
		// OUTWARD (swing=0) -> INWARD (swing=1): side * +amp -> side * -amp, a horizontal arc.
		swordArm.zRot += side * amp * (1.0F - 2.0F * swing);
		// Mid-swing forward thrust: sin(PI*swing) peaks at swing=0.5, giving a cutting arc.
		float thrust = TacticalPoseDebug.standingSwordSwingThrust;
		if (thrust != 0.0F) {
			swordArm.xRot -= thrust * Mth.sin(swing * Mth.PI);
		}

		TacticalAnimations.applySwordTwist(model, state.mainArm, swing);
	}

}
