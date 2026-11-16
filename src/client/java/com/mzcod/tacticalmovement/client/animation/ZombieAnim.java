package com.mzcod.tacticalmovement.client.animation;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.util.Mth;

// Call of Duty-style zombie body language, layered from vanilla's walk data:
//  * idle:   hunched over, head hanging and tilted, arms dangling, slow sway and the odd twitch
//  * walk:   a lurching shamble with a dragged leg, both arms reaching forward, swaying side to side
//  * sprint: leaned far forward, arms pumping wildly, long strides (fast "sprinter" zombies)
//  * attack: both arms rear up and claw down across the target, the body lunging in
// "Sprint" is picked from how fast the walk animation runs (walkAnimationSpeed), tunable with
// /tacticaledbg zombie sprint <from> <to>. /tacticaledbg zombie on|off toggles the whole thing.
public final class ZombieAnim {
	public static boolean enabled = true;
	/** walkAnimationSpeed at which sprinting starts / is fully blended in. */
	public static float sprintFrom = 0.55F;
	public static float sprintTo = 0.80F;
	/** Last walk speed of a zombie seen this frame (for tuning the sprint thresholds). */
	public static float lastSpeed;

	private ZombieAnim() {
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	public static void apply(HumanoidModel<?> m, HumanoidRenderState s) {
		float t = s.ageInTicks;
		float pos = s.walkAnimationPos;
		float spd = s.walkAnimationSpeed;
		lastSpeed = spd;
		float move = smooth(spd / 0.15F);
		float sprint = smooth((spd - sprintFrom) / Math.max(0.01F, sprintTo - sprintFrom));
		float walkW = move * (1.0F - sprint);
		float c = Mth.cos(pos * 0.6662F);
		float sn = Mth.sin(pos * 0.6662F);

		// --- torso lean (radians forward) and side sway
		float lean = lerp(lerp(0.22F, 0.32F, walkW), 0.62F, sprint);
		float bodyRoll = Mth.sin(t * 0.06F) * 0.05F * (1.0F - move) + sn * 0.13F * walkW + sn * 0.06F * sprint;
		float bodyYaw = Mth.sin(pos * 0.3331F) * 0.10F * walkW + c * 0.12F * sprint;

		// --- arms (xRot: negative = raised forward)
		float idleR = -0.30F + Mth.sin(t * 0.07F) * 0.06F;
		float idleL = -0.18F + Mth.sin(t * 0.07F + 1.7F) * 0.06F;
		float walkR = -1.40F + c * 0.28F * spd;
		float walkL = -1.10F - c * 0.34F * spd;
		float runR = -0.35F - c * 1.55F;
		float runL = -0.35F + c * 1.55F;
		float rArmX = lerp(lerp(idleR, walkR, move), runR, sprint);
		float lArmX = lerp(lerp(idleL, walkL, move), runL, sprint);
		float rArmZ = lerp(lerp(0.10F, 0.05F, move), 0.30F, sprint);
		float lArmZ = lerp(lerp(-0.14F, -0.05F, move), -0.30F, sprint);
		float rArmY = lerp(0.0F, -0.15F, walkW);
		float lArmY = lerp(0.0F, 0.18F, walkW);

		// --- legs: a limp while walking (the left leg drags), long strides while sprinting
		float rLegX = c * lerp(1.0F, 1.35F, sprint) * spd * 1.4F;
		float lLegX = -c * lerp(0.62F, 1.35F, sprint) * spd * 1.4F;

		// --- head: hangs and tilts while idle, bobs against the sway while walking, looks up at the target when leaning far
		float twitch = Mth.sin(t * 0.9F) * smooth((Mth.sin(t * 0.045F) - 0.92F) * 12.0F) * 0.25F;
		float headDrop = lerp(lerp(0.38F, 0.18F, move), -0.30F, sprint);
		float headRoll = lerp(0.16F + twitch, -bodyRoll * 0.7F + 0.10F, move);

		// --- attack: arms rear up, then claw down and across, the body lunging in
		float swing = s.swingAnimation;
		if (swing > 0.0F) {
			float w = Mth.sin(swing * Mth.PI);
			float clawX = swing < 0.35F ? lerp(-1.4F, -2.8F, swing / 0.35F) : lerp(-2.8F, -0.45F, smooth((swing - 0.35F) / 0.65F));
			rArmX = lerp(rArmX, clawX, Math.min(1.0F, w * 2.0F));
			lArmX = lerp(lArmX, clawX + 0.25F, Math.min(1.0F, w * 2.0F));
			rArmY = lerp(rArmY, 0.35F, w);
			lArmY = lerp(lArmY, -0.35F, w);
			lean += 0.28F * w;
			headDrop -= 0.25F * w;
		}

		// Pose the torso about the hips (the legs stay planted): move the body, head and shoulders so the hips sit
		// over the legs' pivot, the same way vanilla's crouch does but for any lean.
		float cos = Mth.cos(lean);
		float sin = Mth.sin(lean);
		float by = 12.0F - 12.0F * cos;
		float bz = -12.0F * sin;
		m.body.xRot = lean;
		m.body.y = by;
		m.body.z = bz;
		m.body.zRot = bodyRoll;
		m.body.yRot = bodyYaw;
		m.head.y = by;
		m.head.z = bz;
		m.head.xRot += headDrop;
		m.head.zRot = headRoll;
		float sy = by + 2.0F * cos;
		float sz = bz + 2.0F * sin;
		m.rightArm.y = sy;
		m.rightArm.z = sz;
		m.leftArm.y = sy;
		m.leftArm.z = sz;
		m.rightArm.xRot = rArmX;
		m.leftArm.xRot = lArmX;
		m.rightArm.yRot = rArmY;
		m.leftArm.yRot = lArmY;
		m.rightArm.zRot = rArmZ;
		m.leftArm.zRot = lArmZ;
		m.rightLeg.xRot = rLegX;
		m.leftLeg.xRot = lLegX;
	}
}
