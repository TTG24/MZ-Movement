package com.mzcod.tacticalmovement.client.animation;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mzcod.tacticalmovement.movement.MovementStance;
import com.mzcod.tacticalmovement.movement.MovementStateAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

// First-person stance effects for the local player:
//  * DIVE: the hands (everything in the hand pose: sword arm, item, empty hand) reach out forward as the dive
//    starts, stay out while airborne, then on landing slam forward (impact) with a damped shake + a small camera
//    kick and ease back to rest.
//  * CROUCH + sword: the sword arm is pulled in toward the chest (offsets scaled by the crouch blend).
// Live tuning: /tacticaledbg fpdive <reach> <lift> <impact>, /tacticaledbg fpcrouch <x> <y> <z> <pitch> <yaw> <roll>
public final class FpStanceAnim {
	// --- dive (hand pose space: -z = forward)
	public static float diveReach = 0.40F;   // how far the hands push forward
	public static float diveLift = 0.10F;    // how far they rise
	public static float diveImpact = 1.0F;   // landing slam / shake / camera kick strength (0 = none)
	public static float diveReachIn = 0.16F; // seconds to reach out
	public static float diveReturn = 0.60F;  // seconds to come back after landing

	// --- crouch sword arm (applied to the sword arm; x is toward the centre for either hand)
	public static float crouchX = 0.22F;
	public static float crouchY = -0.14F;
	public static float crouchZ = 0.14F;
	public static float crouchPitch = 8.0F;
	public static float crouchYaw = 0.0F;
	public static float crouchRoll = 0.0F;

	// --- stance-transition bobs. Per event: {camera Y (blocks), camera pitch (deg), hand Y (blocks)}; negative =
	// dips down first, positive = lifts first, then a damped oscillation settles it. Kinds: 0 enter crouch,
	// 1 enter prone, 2 exit crouch (stand up), 3 exit prone.
	public static final String[] BOB_NAMES = { "entercrouch", "enterprone", "exitcrouch", "exitprone" };
	private static final float[][] BOB_DEFAULT = {
		{ -0.06F, 1.4F, -0.05F },
		{ -0.16F, 4.0F, -0.12F },
		{ 0.04F, -1.0F, 0.035F },
		{ 0.10F, -3.0F, 0.08F }
	};
	public static final float[][] bob = new float[4][3];
	public static float bobDecay = 5.5F;
	public static float bobFreq = 13.0F;
	// --- standing idle breathing (camera Y blocks, hand Y blocks, rate rad/s)
	public static float idleCam = 0.0028F;
	public static float idleHand = 0.004F;
	public static float idleRate = 1.8F;

	static {
		resetBobs();
	}

	public static void resetBobs() {
		for (int i = 0; i < 4; i++) {
			System.arraycopy(BOB_DEFAULT[i], 0, bob[i], 0, 3);
		}
		bobDecay = 5.5F;
		bobFreq = 13.0F;
		idleCam = 0.0028F;
		idleHand = 0.004F;
		idleRate = 1.8F;
	}

	private static MovementStance lastStance = MovementStance.STANDING;
	private static int bobKind = -1;
	private static long bobStart;

	private static boolean wasDiving;
	private static long diveStart;
	private static long landStart = -1L;

	private FpStanceAnim() {
	}

	public static void resetDive() {
		diveReach = 0.40F;
		diveLift = 0.10F;
		diveImpact = 1.0F;
		diveReachIn = 0.16F;
		diveReturn = 0.60F;
	}

	public static void resetCrouch() {
		crouchX = 0.22F;
		crouchY = -0.14F;
		crouchZ = 0.14F;
		crouchPitch = 8.0F;
		crouchYaw = 0.0F;
		crouchRoll = 0.0F;
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	// Track dive start / landing from the local player's synced stance. Cheap; call from each render hook.
	private static void track() {
		var player = Minecraft.getInstance().player;
		if (player == null) {
			wasDiving = false;
			return;
		}
		boolean diving = ((MovementStateAccess) player).tacticalMovement$getStance() == MovementStance.DIVING;
		long now = System.nanoTime();
		if (diving && !wasDiving) {
			diveStart = now;
			landStart = -1L;
		} else if (!diving && wasDiving) {
			landStart = now;
		}
		wasDiving = diving;

		MovementStance now2 = ((MovementStateAccess) player).tacticalMovement$getStance();
		if (now2 != lastStance) {
			int kind = -1;
			if (now2 == MovementStance.CROUCHING && lastStance == MovementStance.STANDING) {
				kind = 0;
			} else if (now2 == MovementStance.PRONE && (lastStance == MovementStance.STANDING || lastStance == MovementStance.CROUCHING)) {
				kind = 1;
			} else if (lastStance == MovementStance.CROUCHING && now2 == MovementStance.STANDING) {
				kind = 2;
			} else if (lastStance == MovementStance.PRONE && (now2 == MovementStance.STANDING || now2 == MovementStance.CROUCHING)) {
				kind = 3;
			}
			if (kind >= 0) {
				bobKind = kind;
				bobStart = now;
			}
			lastStance = now2;
		}
	}

	// Damped oscillation for the active transition bob: starts at 0, moves in the event's direction, rings out.
	private static float bobWave() {
		if (bobKind < 0) {
			return 0.0F;
		}
		float dt = seconds(bobStart);
		if (dt > 1.5F) {
			return 0.0F;
		}
		return (float) (Math.exp(-dt * bobDecay) * Math.sin(dt * bobFreq));
	}

	// Standing idle breathing weight: 1 when standing still, 0 when moving / not standing.
	private static float idleWeight() {
		var player = Minecraft.getInstance().player;
		if (player == null || lastStance != MovementStance.STANDING) {
			return 0.0F;
		}
		double v = player.getDeltaMovement().horizontalDistance();
		return 1.0F - Mth.clamp((float) (v * 12.0), 0.0F, 1.0F);
	}

	private static float idleWave() {
		return (float) Math.sin(System.nanoTime() / 1.0E9 * idleRate);
	}

	private static float seconds(long from) {
		return (System.nanoTime() - from) / 1.0E9F;
	}

	// Reach amount: 0 = rest, 1 = fully out, >1 during the landing slam.
	private static float reach() {
		if (wasDiving) {
			return smooth(seconds(diveStart) / Math.max(0.01F, diveReachIn));
		}
		if (landStart < 0L) {
			return 0.0F;
		}
		float dt = seconds(landStart);
		float slam = 0.12F;
		if (dt < slam) {
			return 1.0F + 0.35F * diveImpact * smooth(dt / slam);
		}
		float back = (dt - slam) / Math.max(0.05F, diveReturn);
		return (1.0F + 0.35F * diveImpact) * (1.0F - smooth(back));
	}

	// Damped shake right after landing (0 otherwise).
	private static float shake() {
		if (wasDiving || landStart < 0L || diveImpact <= 0.0F) {
			return 0.0F;
		}
		float dt = seconds(landStart);
		if (dt > 0.6F) {
			return 0.0F;
		}
		return (float) (Math.exp(-dt * 8.0) * Math.sin(dt * 34.0)) * diveImpact;
	}

	// Applied to the whole first-person hand pose (every hand/item), right after the view bob.
	public static void applyHands(PoseStack poseStack) {
		track();
		float r = reach();
		float k = shake();
		float w = bobWave();
		if (w != 0.0F) {
			float[] b = bob[bobKind];
			poseStack.translate(0.0F, b[2] * w * 4.0F, 0.0F);
			poseStack.rotateDegrees(Axis.XP, -b[1] * w * 3.0F);
		}
		float iw = idleWeight();
		if (iw > 0.0F) {
			float iv = idleWave() * iw;
			poseStack.translate(0.0F, idleHand * iv, 0.0F);
			poseStack.rotateDegrees(Axis.ZP, 0.5F * iv);
		}
		if (r > 0.0001F) {
			poseStack.translate(0.0F, diveLift * r, -diveReach * r);
			poseStack.rotateDegrees(Axis.XP, -6.0F * r);
		}
		if (k != 0.0F) {
			poseStack.translate(0.0F, -0.07F * k, 0.05F * k);
			poseStack.rotateDegrees(Axis.XP, 7.0F * k);
			poseStack.rotateDegrees(Axis.ZP, 3.0F * k);
		}
	}

	// Applied to the world camera: a downward pitch dip + bounce when the dive lands.
	public static void applyCamera(PoseStack poseStack) {
		track();
		float k = shake();
		float w = bobWave();
		if (w != 0.0F) {
			float[] b = bob[bobKind];
			poseStack.translate(0.0F, b[0] * w * 4.0F, 0.0F);
			poseStack.rotateDegrees(Axis.XP, b[1] * w * 3.0F);
		}
		float iw = idleWeight();
		if (iw > 0.0F) {
			poseStack.translate(0.0F, idleCam * idleWave() * iw, 0.0F);
		}
		if (k != 0.0F) {
			poseStack.translate(0.0F, -0.05F * k, 0.0F);
			poseStack.rotateDegrees(Axis.XP, 2.5F * k);
		}
	}

	// Crouch: extra offset for the sword arm, pulling it in toward the chest. side = +1 right / -1 left.
	public static void applyCrouchSwordArm(PoseStack poseStack, int side) {
		var player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		var access = (MovementStateAccess) player;
		float b = Mth.clamp(access.tacticalMovement$getCrouchBlend(), 0.0F, 1.0F);
		// The crouch blend is held at its old value while crouch -> prone cross-fades (so the 3p pose can start
		// from it); in first person fade the offset out with the prone blend so it doesn't pop when dropped.
		if (access.tacticalMovement$getStance().isLowProfile()) {
			b *= 1.0F - Mth.clamp(access.tacticalMovement$getProneBlend(), 0.0F, 1.0F);
		}
		if (b < 0.001F) {
			return;
		}
		poseStack.translate(side * crouchX * b, crouchY * b, crouchZ * b);
		poseStack.rotateDegrees(Axis.XP, crouchPitch * b);
		poseStack.rotateDegrees(Axis.YP, side * crouchYaw * b);
		poseStack.rotateDegrees(Axis.ZP, side * crouchRoll * b);
	}
}
