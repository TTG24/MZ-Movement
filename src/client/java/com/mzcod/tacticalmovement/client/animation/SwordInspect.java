package com.mzcod.tacticalmovement.client.animation;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mzcod.tacticalmovement.movement.MovementStance;
import com.mzcod.tacticalmovement.movement.MovementStateAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;

// First-person sword inspect (V key). Only the local player sees it. Timeline (seconds, before speed scaling):
//  0.0-0.7  the sword arm twists to one side, 0.7-1.4 twists to the other side
//  1.4-1.9  back to centre
//  1.9-2.3  the arm comes up close to the view and twists so the sword points down
//  2.3-2.7  hold, showing the full sword
//  2.7-3.3  back to the normal pose
// Live tuning: /tacticaledbg inspect <name> <value> (names in NAMES), /tacticaledbg inspect reset.
public final class SwordInspect {
	public static final String[] NAMES = { "speed", "twist", "sweep", "offin", "offup", "offfwd", "offpitch", "offyaw", "offroll", "pivx", "pivy", "pivz" };
	private static final float[] DEFAULTS = { 1.0F, -180.0F, 0.14F, 0.30F, 0.02F, -0.35F, -35.0F, 25.0F, 20.0F, 0.70F, -0.20F, -0.55F };
	public static final float[] p = DEFAULTS.clone();

	// Channels: 0 in, 1 up, 2 fwd, 3 pitch, 4 yaw, 5 roll, 6 flip (0..1), 7 off-hand weight.
	private static final float[][][] KEYS = {
		/* in    */ { {0, 0}, {0.7F, 0.06F}, {1.4F, -0.04F}, {1.9F, 0.10F}, {2.7F, 0.10F}, {3.3F, 0} },
		/* up    */ { {0, 0}, {1.9F, 0.0F}, {2.3F, 0.20F}, {2.7F, 0.20F}, {3.3F, 0} },
		/* fwd   */ { {0, 0}, {1.9F, 0.0F}, {2.3F, 0.16F}, {2.7F, 0.16F}, {3.3F, 0} },
		/* pitch */ { {0, 0}, {1.9F, 0.0F}, {2.3F, -28.0F}, {2.7F, -28.0F}, {3.3F, 0} },
		/* yaw   */ { {0, 0}, {0.7F, -14.0F}, {1.4F, 14.0F}, {1.9F, 0.0F}, {3.3F, 0} },
		/* roll  */ { {0, 0}, {0.7F, -28.0F}, {1.4F, 28.0F}, {1.9F, 0.0F}, {2.3F, 10.0F}, {2.7F, 10.0F}, {3.3F, 0} },
		/* flip  */ { {0, 0}, {1.9F, 0}, {2.3F, 1.0F}, {2.7F, 1.0F}, {3.3F, 0} },
		/* off   */ { {0, 0}, {3.3F, 0} }
	};
	// Left-to-right sweep position (-1..1) while the blade is inspected.
	private static final float SWEEP_START = 1.9F;
	private static final float SWEEP_END = 2.95F;
	private static final float LENGTH = 3.3F;

	private static long start = -1L;

	private SwordInspect() {
	}

	public static void reset() {
		System.arraycopy(DEFAULTS, 0, p, 0, p.length);
	}

	public static boolean active() {
		return start >= 0L;
	}

	public static void begin() {
		var player = Minecraft.getInstance().player;
		if (player == null || active() || !player.getMainHandItem().is(ItemTags.SWORDS)) {
			return;
		}
		MovementStance stance = ((MovementStateAccess) player).tacticalMovement$getStance();
		if (stance != MovementStance.STANDING && stance != MovementStance.CROUCHING) {
			return;
		}
		start = System.nanoTime();
	}

	// Called every client tick: stops the inspect when the player does something else.
	public static void tick(Minecraft client) {
		if (!active()) {
			return;
		}
		var player = client.player;
		if (player == null || !player.getMainHandItem().is(ItemTags.SWORDS) || player.isSprinting()
			|| player.getSwingAnimation(1.0F) > 0.0F || client.options.keyAttack.isDown() || client.options.keyUse.isDown()
			|| client.gui.screen() != null) {
			start = -1L;
			return;
		}
		MovementStance stance = ((MovementStateAccess) player).tacticalMovement$getStance();
		if (stance != MovementStance.STANDING && stance != MovementStance.CROUCHING) {
			start = -1L;
			return;
		}
		if (time() >= LENGTH) {
			start = -1L;
		}
	}

	private static float time() {
		return (System.nanoTime() - start) / 1.0E9F * p[0];
	}

	private static float sample(int channel, float t) {
		float[][] k = KEYS[channel];
		if (t <= k[0][0]) {
			return k[0][1];
		}
		for (int i = 1; i < k.length; i++) {
			if (t <= k[i][0]) {
				float u = (t - k[i - 1][0]) / (k[i][0] - k[i - 1][0]);
				u = u * u * (3.0F - 2.0F * u);
				return k[i - 1][1] + (k[i][1] - k[i - 1][1]) * u;
			}
		}
		return k[k.length - 1][1];
	}

	// Left-to-right inspect position: -1 (left) .. +1 (right), eased.
	private static float sweep(float t) {
		float u = Mth.clamp((t - SWEEP_START) / (SWEEP_END - SWEEP_START), 0.0F, 1.0F);
		u = u * u * (3.0F - 2.0F * u);
		return -1.0F + 2.0F * u;
	}

	// Sword arm: applied right after the crouch offset, before the arm is placed. side = +1 right / -1 left.
	public static void applyArm(PoseStack poseStack, int side) {
		if (!active()) {
			return;
		}
		float t = time();
		poseStack.translate(side * -sample(0, t) + p[2] * sweep(t) * sample(7, t), sample(1, t), sample(2, t));
		poseStack.rotateDegrees(Axis.YP, side * sample(4, t));
		poseStack.rotateDegrees(Axis.XP, sample(3, t));
		poseStack.rotateDegrees(Axis.ZP, side * sample(5, t));
		// twist (channel 6): after the arm has moved, it turns IN PLACE around the hand (a roll in the view plane about
		// the grip point), so the sword swings round in front of the player and points down without the arm travelling.
		float twist = p[1] * sample(6, t);
		if (twist != 0.0F) {
			poseStack.translate(side * p[9], p[10], p[11]);
			poseStack.rotateDegrees(Axis.ZP, side * twist);
			poseStack.translate(-side * p[9], -p[10], -p[11]);
		}
	}

	// How far the other arm is raised (0..1).
	public static float offWeight() {
		return active() ? sample(7, time()) : 0.0F;
	}

	// The other arm: from the arm rest pose up to the sword tip, following the left-to-right sweep. side = main arm side.
	public static void applyOffArm(PoseStack poseStack, int side) {
		float w = offWeight();
		float t = time();
		int o = -side;
		float s = sweep(t);
		// Same sideways slide as the sword arm, so the hand stays on the tip while the blade is inspected.
		poseStack.translate(o * (-0.46F + p[3] * w) + p[2] * s * w, 0.16F + p[4] * w, 0.10F + p[5] * w);
		poseStack.rotateDegrees(Axis.XP, -12.0F + p[6] * w);
		poseStack.rotateDegrees(Axis.YP, o * (-12.0F + p[7] * w));
		poseStack.rotateDegrees(Axis.ZP, o * (42.0F + p[8] * w));
	}
}
