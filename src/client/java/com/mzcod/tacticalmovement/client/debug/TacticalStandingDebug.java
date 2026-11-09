package com.mzcod.tacticalmovement.client.debug;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;

// Custom STANDING animations (third person, all players): idle, idle with sword (ready), moving, moving with
// sword. Everything is layered on the vanilla pose (head/body/limbs at their vanilla positions), only rotations
// are changed. Arms are only overridden when they are empty or holding a plain item (not eating, blocking,
// bows, etc.), and the sword arm fades back to vanilla during an attack swing so the swing is untouched.
//
// Sign convention (arm of side s = +1 right / -1 left): +zRot = out to the side, -xRot = forward/up,
// -s*yRot = toward the body centre. +body xRot = lean forward.
//
// Tuning: /tacticaledbg stand            list all values
//         /tacticaledbg stand <name> <v> change one
//         /tacticaledbg standon <true|false>, /tacticaledbg standreset
public final class TacticalStandingDebug {
	public static boolean enabled = true;

	public static final class P {
		public final String name;
		public final float def;
		public float v;

		P(String name, float def) {
			this.name = name;
			this.def = def;
			this.v = def;
			ALL.put(name, this);
		}
	}

	public static final Map<String, P> ALL = new LinkedHashMap<>();

	// --- idle (no sword): breathing + weight shift
	static final P BREATH_RATE = new P("breathRate", 1.6F);
	static final P IDLE_ARM_OUT = new P("idleArmOut", 0.07F);
	static final P IDLE_ARM_FWD = new P("idleArmFwd", 0.05F);
	static final P BREATH_AMP = new P("breathAmp", 0.03F);
	static final P SHIFT_AMP = new P("shiftAmp", 0.025F);

	// --- sword ready stance (idle and moving)
	static final P READY_X = new P("readyX", -0.75F);   // sword arm forward raise
	static final P READY_Y = new P("readyY", 0.30F);    // sword arm toward the centre
	static final P READY_Z = new P("readyZ", 0.05F);
	static final P OFF_X = new P("offX", -0.45F);       // off hand guard
	static final P OFF_Y = new P("offY", 0.30F);
	static final P OFF_Z = new P("offZ", 0.08F);
	static final P BLADE_TURN = new P("bladeTurn", 0.18F); // body yaw so the shoulder leads (sword side back)
	static final P STAGGER = new P("stagger", 0.14F);   // idle leg stagger (sword-side leg back)
	static final P STANCE_WIDTH = new P("stanceWidth", 0.07F); // idle leg spread
	static final P READY_SWAY = new P("readySway", 0.035F);

	// --- moving
	static final P LEG_AMP = new P("legAmp", 1.25F);
	static final P ARM_AMP = new P("armAmp", 0.75F);    // arm swing (no sword / off arm weighting)
	static final P LEAN = new P("lean", 0.10F);         // forward torso lean while moving
	static final P TWIST = new P("twist", 0.14F);       // torso counter-twist with the stride
	static final P ROCK = new P("rock", 0.04F);         // side-to-side torso rock
	static final P SWORD_BOB = new P("swordBob", 0.07F); // ready-arm bob while moving
	static final P SWORD_ARM_SWING = new P("swordArmSwing", 0.35F); // off arm swing while holding sword

	static final P HIP_FOLLOW = new P("hipFollow", 1.0F); // how much the upper body hinges forward from the hips to stay attached to the legs (0 = none, 1 = fully)

	// --- sprint (multipliers on the moving values, so it reads as a bigger version of the run)
	static final P SPRINT_STRIDE = new P("sprintStride", 1.45F);
	static final P SPRINT_ARM = new P("sprintArm", 1.8F);
	static final P SPRINT_LEAN = new P("sprintLean", 0.30F);   // extra forward lean
	static final P SPRINT_TWIST = new P("sprintTwist", 1.9F);  // torso twist multiplier
	static final P SPRINT_ROCK = new P("sprintRock", 1.6F);
	static final P SPRINT_OUT = new P("sprintOut", 0.12F);     // arms drive out a little
	static final P SPRINT_SWORD_X = new P("sprintSwordX", -0.55F); // sword arm pumped, held tucked low
	static final P SPRINT_SWORD_SWING = new P("sprintSwordSwing", 0.75F);
	static final P SPRINT_SWORD_Y = new P("sprintSwordY", 0.35F);

	// --- sneak (shift): low, creeping, sword held low and close
	static final P SNEAK_LEAN = new P("sneakLean", 0.20F);
	static final P SNEAK_LEG = new P("sneakLeg", 0.55F);       // stride multiplier
	static final P SNEAK_THIGH = new P("sneakThigh", 0.22F);   // thighs forward
	static final P SNEAK_KNEE = new P("sneakKnee", 0.55F);     // knee bend
	static final P SNEAK_SWORD_X = new P("sneakSwordX", -0.35F);
	static final P SNEAK_SWORD_Y = new P("sneakSwordY", 0.55F);
	static final P SNEAK_OFF_X = new P("sneakOffX", -0.85F);   // off hand feeling ahead
	static final P SNEAK_ARM = new P("sneakArm", 0.35F);       // arm swing multiplier (no sword)
	static final P SNEAK_TURN = new P("sneakTurn", 0.30F);     // bladed body turn with sword
	static final P SNEAK_SWAY = new P("sneakSway", 0.035F);    // slow creeping sway

	private TacticalStandingDebug() {
	}

	public static void reset() {
		for (P p : ALL.values()) {
			p.v = p.def;
		}
	}

	public static String dump() {
		StringBuilder sb = new StringBuilder("STANDING anim (enabled=" + enabled + "):");
		for (P p : ALL.values()) {
			sb.append(String.format(java.util.Locale.ROOT, "%n  %s = %.3f (default %.3f)", p.name, p.v, p.def));
		}
		return sb.toString();
	}

	private static boolean plainArm(HumanoidModel.ArmPose pose) {
		return pose == HumanoidModel.ArmPose.EMPTY || pose == HumanoidModel.ArmPose.ITEM;
	}

	private static void twistShoulder(ModelPart arm, ModelPart body, float cos, float sin) {
		float rx = arm.x - body.x;
		float rz = arm.z - body.z;
		arm.x = body.x + rx * cos + rz * sin;
		arm.z = body.z - rx * sin + rz * cos;
	}

	private static void hinge(ModelPart part, float back, float down) {
		part.z += back;
		part.y += down;
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	// Call once per frame in the standing branch (after vanilla setupAnim, before the sword swing is layered on).
	public static void apply(PlayerModel model, AvatarRenderState state, boolean holdingSword) {
		if (!enabled || state.isPassenger || state.isFallFlying) {
			return;
		}
		float t = state.ageInTicks / 20.0F;
		float m = Mth.clamp(state.walkAnimationSpeed * 1.2F, 0.0F, 1.0F);
		float pos = state.walkAnimationPos * 0.6662F;
		float stride = Mth.cos(pos);          // right leg forward when +
		float stride2 = Mth.sin(pos);         // quarter-phase, used for bob
		float bounce = Math.abs(stride2);
		float breath = Mth.sin(t * BREATH_RATE.v);
		float shift = Mth.sin(t * 0.7F);
		float idle = 1.0F - m;
		var access = (com.mzcod.tacticalmovement.client.animation.TacticalRenderStateAccess) state;
		float sp = Mth.clamp(access.tacticalMovement$getSprintBlend(), 0.0F, 1.0F) * (m > 0.3F ? 1.0F : m / 0.3F);
		float sn = Mth.clamp(access.tacticalMovement$getSneakBlend(), 0.0F, 1.0F);
		int main = state.mainArm == HumanoidArm.RIGHT ? 1 : -1;
		ModelPart mainArm = state.mainArm == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
		ModelPart offArm = state.mainArm == HumanoidArm.RIGHT ? model.leftArm : model.rightArm;
		boolean mainOk = plainArm(state.mainArm == HumanoidArm.RIGHT ? state.rightArmPose : state.leftArmPose) && !state.isUsingItem;
		boolean offOk = plainArm(state.mainArm == HumanoidArm.RIGHT ? state.leftArmPose : state.rightArmPose) && !state.isUsingItem;

		// Legs (always): our own stride, plus an idle stagger/spread when holding a sword.
		float legAmp = LEG_AMP.v * m * (1.0F + sp * (SPRINT_STRIDE.v - 1.0F)) * (1.0F - sn * (1.0F - SNEAK_LEG.v));
		model.rightLeg.xRot = stride * legAmp;
		model.leftLeg.xRot = -stride * legAmp;
		if (holdingSword) {
			// Sword-side leg back, off-side leg forward, feet a touch apart; fades out as the player moves.
			float st = STAGGER.v * idle;
			model.rightLeg.xRot += main * st;
			model.leftLeg.xRot -= main * st;
			model.rightLeg.zRot += STANCE_WIDTH.v * idle;
			model.leftLeg.zRot -= STANCE_WIDTH.v * idle;
		}

		if (sn > 0.001F) {
			// Sneak: thighs forward, knees bent (the shin is the knee joint child added by PlayerModelKneeMixin);
			// the trailing leg bends more so the step reads as creeping.
			model.rightLeg.xRot -= SNEAK_THIGH.v * sn;
			model.leftLeg.xRot -= SNEAK_THIGH.v * sn;
			float rBend = Math.max(0.0F, SNEAK_KNEE.v - 0.35F * stride * m);
			float lBend = Math.max(0.0F, SNEAK_KNEE.v + 0.35F * stride * m);
			if (model.rightLeg.hasChild("right_shin")) {
				model.rightLeg.getChild("right_shin").xRot = rBend * sn;
			}
			if (model.leftLeg.hasChild("left_shin")) {
				model.leftLeg.getChild("left_shin").xRot = lBend * sn;
			}
		}

		// Torso: lean + counter-twist + rock while moving, bladed turn while holding a sword.
		float bodyYaw = -stride * TWIST.v * m * (1.0F + sp * (SPRINT_TWIST.v - 1.0F));
		float bodyRock = stride * ROCK.v * m * (1.0F + sp * (SPRINT_ROCK.v - 1.0F)) + shift * SHIFT_AMP.v * idle;
		float bodyLean = LEAN.v * m + SPRINT_LEAN.v * sp + breath * BREATH_AMP.v * 0.5F * idle + SNEAK_LEAN.v * sn;
		bodyRock += Mth.sin(t * 1.3F) * SNEAK_SWAY.v * sn;
		if (holdingSword) {
			bodyYaw += main * BLADE_TURN.v * (1.0F - sn) + main * SNEAK_TURN.v * sn;
		}
		model.body.yRot += bodyYaw;
		// The arms are separate parts at fixed shoulder positions, so a twisted torso would slide away from them
		// (torso turned, shoulders not). Swing the shoulder pivots around the spine by the same yaw.
		float cy = Mth.cos(bodyYaw);
		float sy = Mth.sin(bodyYaw);
		twistShoulder(model.rightArm, model.body, cy, sy);
		twistShoulder(model.leftArm, model.body, cy, sy);
		model.body.zRot += bodyRock;
		model.body.xRot += bodyLean;
		// Hip hinge: the torso pivots at the neck, so leaning it alone swings its bottom (the hips) back and up and
		// detaches it from the legs. Instead, move the whole upper body (torso, head, arms are all root children)
		// forward and down by that same amount so the torso's bottom stays planted on the legs.
		float hinge = HIP_FOLLOW.v;
		float hingeBack = -12.0F * Mth.sin(bodyLean) * hinge;
		float hingeDown = 12.0F * (1.0F - Mth.cos(bodyLean)) * hinge;
		hinge(model.body, hingeBack, hingeDown);
		hinge(model.head, hingeBack, hingeDown);
		hinge(model.rightArm, hingeBack, hingeDown);
		hinge(model.leftArm, hingeBack, hingeDown);
		// The head is a sibling of the body (both children of the root), so body rotation never moves it:
		// leave the head exactly as vanilla set it so it always follows the look direction.

		// Sword-arm weight: fades to vanilla during the attack swing so vanilla's chop + tactical swing play unchanged.
		float s = Mth.clamp(state.swingAnimation, 0.0F, 1.0F);
		float swingFade = s > 0.0F ? 1.0F - Mth.clamp(Math.min(s, 1.0F - s) * 8.0F, 0.0F, 1.0F) : 1.0F;
		boolean swinging = s > 0.0F;

		if (holdingSword) {
			if (mainOk) {
				float bob = stride2 * SWORD_BOB.v * m + breath * READY_SWAY.v * idle;
				float x = READY_X.v + bob - bounce * 0.04F * m;
				float y = -main * READY_Y.v;
				// Sprint: sword arm tucked low and pumping with the stride; sneak: held low and close.
				x = lerp(x, SPRINT_SWORD_X.v - stride * main * SPRINT_SWORD_SWING.v * m, sp);
				y = lerp(y, -main * SPRINT_SWORD_Y.v, sp);
				x = lerp(x, SNEAK_SWORD_X.v, sn);
				y = lerp(y, -main * SNEAK_SWORD_Y.v, sn);
				float z = main * READY_Z.v;
				float w = swinging ? swingFade : 1.0F;
				mainArm.xRot = lerp(mainArm.xRot, x, w);
				mainArm.yRot = lerp(mainArm.yRot, y, w);
				mainArm.zRot = lerp(mainArm.zRot, z, w);
			}
			if (offOk) {
				float x = OFF_X.v - stride * main * SWORD_ARM_SWING.v * m * 0.4F + breath * READY_SWAY.v * idle * 0.6F;
				// Sprint: the off arm drives hard against the stride; sneak: reaching out ahead, feeling the way.
				x = lerp(x, stride * main * ARM_AMP.v * SPRINT_ARM.v * m, sp);
				x = lerp(x, SNEAK_OFF_X.v + stride * main * 0.12F * m, sn);
				offArm.xRot = x;
				offArm.yRot = main * OFF_Y.v;
				offArm.zRot = -main * OFF_Z.v;
			}
		} else {
			float armAmp = ARM_AMP.v * m * (1.0F + sp * (SPRINT_ARM.v - 1.0F)) * (1.0F - sn * (1.0F - SNEAK_ARM.v));
			float out = IDLE_ARM_OUT.v + (0.03F * m) + SPRINT_OUT.v * sp;
			float fwd = IDLE_ARM_FWD.v * idle + breath * BREATH_AMP.v * idle + 0.55F * sn;
			boolean rightFree = state.rightArmPose == HumanoidModel.ArmPose.EMPTY && !state.isUsingItem
				&& !(s > 0.0F && state.mainArm == HumanoidArm.RIGHT);
			boolean leftFree = state.leftArmPose == HumanoidModel.ArmPose.EMPTY && !state.isUsingItem
				&& !(s > 0.0F && state.mainArm == HumanoidArm.LEFT);
			// Arms swing opposite the legs when moving; relaxed breathing sway when idle.
			if (rightFree) {
				model.rightArm.xRot = -stride * armAmp - fwd;
				model.rightArm.zRot = out + breath * BREATH_AMP.v * idle * 0.8F;
			}
			if (leftFree) {
				model.leftArm.xRot = stride * armAmp - fwd;
				model.leftArm.zRot = -out - breath * BREATH_AMP.v * idle * 0.8F;
			}
		}
		// Arms turn with the torso.
		model.rightArm.yRot += bodyYaw;
		model.leftArm.yRot += bodyYaw;
	}
}
