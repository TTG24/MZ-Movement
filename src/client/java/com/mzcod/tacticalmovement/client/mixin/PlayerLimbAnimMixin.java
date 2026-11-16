package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.animation.TacticalRenderStateAccess;
import com.mzcod.tacticalmovement.movement.MovementStance;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Knees and elbows for the player's standing animations (after everything else has posed the model; the crouch, prone, dive
// and downed poses bend them themselves and are left alone):
//  * walking: each knee folds as its leg swings through (the foot lifting off the ground); the elbows bend a little more
//    as each arm swings forward
//  * sprinting: high knee drive, elbows bent and pumping
//  * idle: soft knees and elbows, breathing
//  * holding the sword: the sword arm's elbow bent at the ready (straightening through a slash)
@Mixin(value = PlayerModel.class, priority = 1500)
public abstract class PlayerLimbAnimMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void tacticalMovement$limbs(AvatarRenderState state, CallbackInfo ci) {
		PlayerModel m = (PlayerModel) (Object) this;
		ModelPart foreR = child(m.rightArm, "right_forearm"), foreL = child(m.leftArm, "left_forearm");
		ModelPart shinR = child(m.rightLeg, "right_shin"), shinL = child(m.leftLeg, "left_shin");
		// The forearm sleeves show when the sleeves do
		if (foreR != null && foreR.hasChild("right_forearm_sleeve")) {
			foreR.getChild("right_forearm_sleeve").visible = m.rightSleeve.visible;
		}
		if (foreL != null && foreL.hasChild("left_forearm_sleeve")) {
			foreL.getChild("left_forearm_sleeve").visible = m.leftSleeve.visible;
		}
		if (!(state instanceof TacticalRenderStateAccess t) || foreR == null || foreL == null || shinR == null || shinL == null) {
			return;
		}
		// Prone (the sculpted prone pose: both arms flat on the ground reaching ahead): an army crawl. The forearms fold in
		// across in front of the face, flat on the ground, the two elbows working in turn (one arm reaching out straighter
		// while the other bends, pulling you up to it), and the opposite knee drawing up with each pull. In that pose the
		// right forearm folds inward on its xRot (negative) and the left on its zRot (negative); the knees lift the feet on
		// a positive shin xRot.
		MovementStance st = t.tacticalMovement$getStance();
		float pb = Mth.clamp(t.tacticalMovement$getProneBlend(), 0.0F, 1.0F);
		if (st == MovementStance.PRONE && pb > 0.001F) {
			boolean sword = t.tacticalMovement$isHoldingSword();
			float mv = Mth.clamp(t.tacticalMovement$getProneMove(), 0.0F, 1.0F);
			float clk = com.mzcod.tacticalmovement.client.debug.TacticalPoseDebug.clock() * com.mzcod.tacticalmovement.client.debug.TacticalPoseDebug.moveSpeed;
			float s = Mth.sin(clk) * mv;
			float idle = Mth.sin(state.ageInTicks * 0.09F) * 0.04F;
			float w = pb * pb;
			// The sword arm keeps its blade pointing out, so its elbow folds less
			float swordK = sword ? 0.45F : 1.0F;
			float bendR = (0.75F + 0.5F * s + idle) * (state.mainArm == HumanoidArm.RIGHT ? swordK : 1.0F);
			float bendL = (0.75F - 0.5F * s + idle) * (state.mainArm == HumanoidArm.LEFT ? swordK : 1.0F);
			foreR.xRot -= w * bendR;
			foreL.zRot -= w * bendL;
			shinR.xRot += w * (0.12F + Math.max(0.0F, s) * 0.9F);
			shinL.xRot += w * (0.12F + Math.max(0.0F, -s) * 0.9F);
			return;
		}
		// Getting up out of prone: a push-up. The hands plant under the shoulders with the elbows bent, then straighten as
		// they press the body up off the ground; the knee of the leg that steps up folds as it comes forward.
		if ((st == MovementStance.STANDING || st == MovementStance.CROUCHING) && pb > 0.001F && pb < 0.999F) {
			float up = 1.0F - pb; // 0 still down -> 1 up
			float bend = Mth.sin(Mth.clamp(up / 0.6F, 0.0F, 1.0F) * Mth.PI) * 1.0F;
			foreR.xRot -= bend;
			foreL.xRot -= bend;
			float knee = Mth.sin(up * Mth.PI) * 1.1F;
			shinR.xRot += knee;
			shinL.xRot += knee * 0.35F;
			if (pb > 0.33F) {
				return;
			}
		}
		// Only upright and on your feet; fades out as a crouch or prone takes over
		float upright = 1.0F - Mth.clamp(Math.max(t.tacticalMovement$getCrouchBlend(), t.tacticalMovement$getProneBlend()) * 3.0F, 0.0F, 1.0F);
		MovementStance stance = st;
		if (stance != MovementStance.STANDING || upright <= 0.0F || state.isPassenger || state.isFallFlying || state.swimAmount > 0.01F) {
			return;
		}
		float pos = state.walkAnimationPos * 0.6662F, spd = Mth.clamp(state.walkAnimationSpeed, 0.0F, 1.0F);
		float sprint = t.tacticalMovement$getSprintBlend();
		float s = Mth.sin(pos), c = Mth.cos(pos);
		float breathe = Mth.sin(state.ageInTicks * 0.09F) * 0.03F;
		// Knees: soft standing still; fold as the leg swings through, more and higher when sprinting
		float knee = 0.9F * spd + 0.9F * sprint * spd;
		shinR.xRot += upright * (0.06F + Math.max(0.0F, s) * knee);
		shinL.xRot += upright * (0.06F + Math.max(0.0F, -s) * knee);
		// Elbows (the forearm coming up): a relaxed bend, more on the forward swing, pumping at ~70 deg sprinting
		float relax = 0.18F + breathe;
		float walkR = Math.max(0.0F, -c) * 0.45F * spd, walkL = Math.max(0.0F, c) * 0.45F * spd;
		float bendR = Mth.lerp(sprint, relax + walkR, 1.15F + 0.25F * c);
		float bendL = Mth.lerp(sprint, relax + walkL, 1.15F - 0.25F * c);
		// The sword arm: elbow bent at the ready, straightening into a slash
		if (t.tacticalMovement$isHoldingSword()) {
			float slash = Mth.sin(Mth.clamp(state.swingAnimation, 0.0F, 1.0F) * Mth.PI);
			float ready = Mth.lerp(slash, 0.55F, 0.1F);
			// (sprinting it doesn't pump: it stays at the ready, swinging with the stride)
			if (state.mainArm == HumanoidArm.RIGHT) {
				bendR = Math.max(Mth.lerp(sprint, bendR, ready) * (1.0F - slash), ready);
			} else {
				bendL = Math.max(Mth.lerp(sprint, bendL, ready) * (1.0F - slash), ready);
			}
		}
		foreR.xRot -= upright * bendR;
		foreL.xRot -= upright * bendL;
	}

	private static ModelPart child(ModelPart part, String name) {
		return part.hasChild(name) ? part.getChild(name) : null;
	}
}
