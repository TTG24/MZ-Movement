package com.mzcod.tacticalmovement.client.debug;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;

// Tactical CROUCH pose (separate from vanilla shift-sneaking). Same conventions as TacticalPoseDebug:
// head, body, both arms and both legs are direct children of the model ROOT, so every value is an
// absolute ROOT-frame channel: x/y/z in model pixels (y points DOWN, ground is y=24, forward is -z)
// and xRot/yRot/zRot in radians. Rotations apply as Rz*Ry*Rx.
//
// Layout (knees bent to roughly knee height, torso leaned in, arms out to carry the weight):
//   hips   (y=20, z=4)    -- 4px above the ground: a deep crouch
//   body   pivot at the neck (y=12.3, z=-5.2), xRot 0.87 = ~50 degree forward lean (+xRot swings the
//          torso's bottom BACK, i.e. leans the top forward)
//   legs   pivot at the hips, xRot -1.23 (~70 degrees forward) so the feet land back on y=24; a single
//          rigid cube per leg means this reads as thighs/knees forward
//   arms   shoulders on the leaned torso (y=13.6, z=-3.7), xRot -0.9 (forward/down), zRot +/-0.55 (out)
//   head   at the neck; rotation is LIVE (follows the camera), only its position comes from the pose
//
// Live tuning (client commands, all under /tacticaledbg):
//   cset <part> x y z xRot yRot zRot   edit one part of the crouch pose
//   cdump                              print the whole crouch pose + walk settings
//   creset                             restore the baked defaults
//   cwalk <legSwing> <armSway> <speed> walking stroke (radians, radians, rad/sec)
//   cspeed <rate>                      blend ease rate per tick (higher = faster crouch down/up)
//   cstance                            toggle the crouch stance without the key
public final class TacticalCrouchDebug {
	public static final float[][] pose = new float[TacticalPoseDebug.PART_COUNT][TacticalPoseDebug.CHANNEL_COUNT];

	// Per-tick ease rate for the crouch blend (toward 1 while crouching, toward 0 otherwise).
	public static float blendRate = 0.25F;

	// Walking stroke while crouch-moving: legs swing +/-legSwing about their crouch angle, arms sway
	// +/-armSway, both driven by `walkSpeed` rad/sec and weighted by the live movement speed (0..1).
	public static float legSwing = 0.30F;
	public static float armSway = 0.12F;
	public static float walkSpeed = 7.0F;

	// Gentle breathing/weight-shift idle on the arms while standing still crouched.
	public static float idleSway = 0.05F;

	// Reused every frame (render thread only) to avoid a per-call allocation.
	private static final ModelPart[] PARTS_SCRATCH = new ModelPart[TacticalPoseDebug.PART_COUNT];

	private TacticalCrouchDebug() {
	}

	static {
		reset();
	}

	// Knee bend (radians) of the shin RELATIVE to the thigh. The thigh points ~90 degrees forward (xRot
	// -1.5708), so a shin bend of +1.40 puts the shin roughly vertical (about 10 degrees forward of straight
	// down), i.e. the foot sits just behind the knee.
	public static float shinBend = 1.40F;

	// Sword swing while crouched (the crouch arms already point forward, so the standing swing's big
	// rotation would raise the arm overhead): swingSweep = horizontal arc (rad, +/-) of the sword arm about
	// the vertical axis from outside across the body; swingTwist = roll of the arm about its own axis so the
	// blade lies flat (rad); swingLift = how much the arm rises toward horizontal mid-swing (rad).
	// Tune: /tacticaledbg cswing <sweep> <twist> <lift>
	public static float swingSweep = 0.5F;
	public static float swingTwist = 2.0F;
	public static float swingLift = 1.5F;

	// Called AFTER apply() while crouched and swinging. swing is the (possibly slowed) 0..1 progress.
	public static void applySwing(PlayerModel model, net.minecraft.world.entity.HumanoidArm mainArm, float swingAnimation) {
		float s = Math.max(0.0F, Math.min(1.0F, swingAnimation));
		if (s <= 0.0F) {
			return;
		}
		float t = Math.max(0.0F, Math.min(1.0F, (float) Math.sin(s * Math.PI) * 2.0F));
		float env = t * t * (3.0F - 2.0F * t);
		int side = mainArm == net.minecraft.world.entity.HumanoidArm.RIGHT ? 1 : -1;
		ModelPart arm = model.getArm(mainArm);
		// Outward (s=0) -> across the body (s=1), eased at the ends by env and cos-free linear sweep.
		arm.yRot += side * swingSweep * (1.0F - 2.0F * s) * env;
		arm.xRot -= swingLift * env;
		// Fade the outward splay while swinging and roll the arm so the blade is flat.
		arm.zRot = arm.zRot * (1.0F - env) + side * swingTwist * env;
	}

	public static void reset() {
		// Hips at (y=18, z=5), torso leaned ~52 degrees (xRot 0.9), thighs horizontal forward, shins
		// ~vertical. Neck = hips - 12*(cos,sin)(0.9) = (y 10.54, z -4.40); shoulders are 2px down the
		// torso from there. Head top ends ~1.34 blocks up, matching the 1.35 crouch hitbox.
		set("head", 0.0F, 10.54F, -4.4F, 0.0F, 0.0F, 0.0F);
		set("body", 0.0F, 10.54F, -4.4F, 0.9F, 0.0F, 0.0F);
		set("rArm", -5.0F, 11.78F, -2.83F, -0.9F, 0.0F, 0.55F);
		set("lArm", 5.0F, 11.78F, -2.83F, -0.9F, 0.0F, -0.55F);
		set("rLeg", -1.9F, 18.0F, 5.0F, -1.5708F, 0.0F, 0.04F);
		set("lLeg", 1.9F, 18.0F, 5.0F, -1.5708F, 0.0F, -0.04F);
		shinBend = 1.40F;
		swingSweep = 0.5F;
		swingTwist = 2.0F;
		swingLift = 1.5F;
		blendRate = 0.25F;
		legSwing = 0.30F;
		armSway = 0.12F;
		walkSpeed = 7.0F;
		idleSway = 0.05F;
	}

	private static void set(String part, float x, float y, float z, float xr, float yr, float zr) {
		int i = TacticalPoseDebug.findPart(part);
		if (i < 0) {
			return;
		}
		pose[i][0] = x;
		pose[i][1] = y;
		pose[i][2] = z;
		pose[i][3] = xr;
		pose[i][4] = yr;
		pose[i][5] = zr;
	}

	// Blend the model from its captured vanilla pose (TacticalPoseDebug.vanilla, filled this frame by
	// captureVanilla) to the crouch pose. blend 0 = vanilla, 1 = full crouch. move is the 0..1 walking
	// weight. The head keeps its live (camera) rotation; only its position is blended.
	public static void apply(PlayerModel model, float blend, float move) {
		float b = Math.max(0.0F, Math.min(1.0F, blend));
		float m = Math.max(0.0F, Math.min(1.0F, move));
		float t = (float) (System.nanoTime() / 1.0E9);
		float stroke = (float) Math.sin(t * walkSpeed) * m;
		float idle = (float) Math.sin(t * 1.5F) * idleSway * (1.0F - m);
		ModelPart[] parts = PARTS_SCRATCH;
		parts[0] = model.head;
		parts[1] = model.body;
		parts[2] = model.rightArm;
		parts[3] = model.leftArm;
		parts[4] = model.rightLeg;
		parts[5] = model.leftLeg;
		float[][] v = TacticalPoseDebug.vanilla;
		for (int i = 0; i < parts.length; i++) {
			ModelPart p = parts[i];
			p.x = v[i][0] + (pose[i][0] - v[i][0]) * b;
			p.y = v[i][1] + (pose[i][1] - v[i][1]) * b;
			p.z = v[i][2] + (pose[i][2] - v[i][2]) * b;
			if (i == 0) {
				// Head: live camera rotation (vanilla yaw/pitch).
				p.xRot = v[i][3];
				p.yRot = v[i][4];
				p.zRot = v[i][5];
				continue;
			}
			float xr = pose[i][3];
			float yr = pose[i][4];
			float zr = pose[i][5];
			switch (i) {
				case 2 -> xr += -stroke * armSway + idle;  // rArm
				case 3 -> xr += stroke * armSway + idle;   // lArm (opposite)
				case 4 -> xr += stroke * legSwing;         // rLeg
				case 5 -> xr += -stroke * legSwing;        // lLeg (opposite)
				default -> {
				}
			}
			p.xRot = v[i][3] + (xr - v[i][3]) * b;
			p.yRot = v[i][4] + (yr - v[i][4]) * b;
			p.zRot = v[i][5] + (zr - v[i][5]) * b;
		}

		// Knees: the shins are child parts of the legs (see PlayerModelKneeMixin). Their rotation is
		// reset to 0 every frame by the model's resetPose, so we only set it while crouched. While walking
		// the shin counter-swings against the thigh so the foot stays roughly under the knee.
		ModelPart rightShin = shin(model.rightLeg, "right_shin");
		ModelPart leftShin = shin(model.leftLeg, "left_shin");
		if (rightShin != null) {
			rightShin.xRot = (shinBend - stroke * legSwing * 0.6F) * b;
		}
		if (leftShin != null) {
			leftShin.xRot = (shinBend + stroke * legSwing * 0.6F) * b;
		}
	}

	private static ModelPart shin(ModelPart leg, String name) {
		return leg.hasChild(name) ? leg.getChild(name) : null;
	}

	public static String dump() {
		StringBuilder sb = new StringBuilder("CROUCH pose (root frame):");
		for (int i = 0; i < TacticalPoseDebug.PART_COUNT; i++) {
			sb.append("\n  ").append(TacticalPoseDebug.PARTS[i]).append(": ").append(TacticalPoseDebug.describe(pose[i]));
		}
		sb.append(String.format("%n  knee: shinBend=%.3f rad (relative to the thigh)", shinBend));
		sb.append(String.format("%n  swing: sweep=%.2f twist=%.2f lift=%.2f", swingSweep, swingTwist, swingLift));
		sb.append(String.format("%n  walk: legSwing=%.2f armSway=%.2f speed=%.1f idleSway=%.2f | blendRate=%.2f",
			legSwing, armSway, walkSpeed, idleSway, blendRate));
		return sb.toString();
	}
}
