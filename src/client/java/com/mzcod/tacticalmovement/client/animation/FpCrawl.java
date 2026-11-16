package com.mzcod.tacticalmovement.client.animation;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mzcod.tacticalmovement.movement.MovementStance;
import com.mzcod.tacticalmovement.movement.MovementStateAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * First person, prone: both arms crawl, an army crawl seen from your eyes. Each arm runs a stroke of distinct beats:
 * REACH (the hand lifts off the ground and stretches far out ahead, the elbow straightening), PLANT (it comes down onto the
 * ground), PULL (it stays planted while you drag yourself up to it, so it slides back toward you and the elbow folds), and
 * then reaches again. The arms are half a stroke apart, so one reaches while the other pulls. Standing still they rest
 * planted ahead. The arms sit wide apart, out at the shoulders.
 * The off hand rises into view as you go down and drops away quickly when you get up.
 * Getting up plays a push-up: both hands come in beside your chest with the elbows bent, then press straight down as the
 * body rises (dropping out of view), and the sword arm comes back up into its usual place.
 * All offsets are in the hand pose space (side * x = that arm's outward side, +y up, -z forward).
 */
public final class FpCrawl {
	/** Seconds the push-up takes. */
	public static float pushTime = 0.6F;
	/** Strokes per second per arm at full crawl speed. */
	public static float strokeRate = 0.85F;

	private static float cycle, speed, weight, offVis, diveW, airTime;
	private static long lastNanos;
	private static MovementStance lastStance = MovementStance.STANDING;
	private static long pushStart = -1L;

	private FpCrawl() {
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	/** Once a frame (extra calls in the same frame do nothing). */
	public static void update() {
		long now = System.nanoTime();
		if (lastNanos != 0L && now - lastNanos < 2_000_000L) {
			return;
		}
		float dt = lastNanos == 0L ? 0.0F : Mth.clamp((now - lastNanos) / 1.0E9F, 0.0F, 0.1F);
		lastNanos = now;
		var player = Minecraft.getInstance().player;
		if (player == null) {
			weight = 0.0F;
			offVis = 0.0F;
			return;
		}
		MovementStateAccess m = (MovementStateAccess) player;
		MovementStance stance = m.tacticalMovement$getStance();
		if (stance != lastStance) {
			if (lastStance == MovementStance.PRONE && (stance == MovementStance.STANDING || stance == MovementStance.CROUCHING)) {
				pushStart = now;
			} else if (stance == MovementStance.PRONE) {
				pushStart = -1L;
			}
			lastStance = stance;
		}
		boolean prone = stance == MovementStance.PRONE;
		boolean diving = stance == MovementStance.DIVING;
		// The crawl follows the prone blend going down; once you're getting up (or diving) it only fades out, quickly
		// (the push-up has taken the arms over), so it can't come back while the prone blend is still easing out
		float blend = smooth(m.tacticalMovement$getProneBlend());
		weight = prone ? blend : Math.max(0.0F, Math.min(weight, blend) - dt * 6.0F);
		// Dolphin dive: both arms thrown out ahead, held through the air, easing into the crawl's planted hands on landing
		diveW = diving ? Math.min(1.0F, diveW + dt * 9.0F) : Math.max(0.0F, diveW - dt * 3.5F);
		airTime = diving ? airTime + dt : 0.0F;
		// The off hand: up into view going down (with the prone blend / the dive), straight out when getting up
		offVis = prone || diving ? Math.max(offVis, Math.max(weight, diveW)) : Math.max(0.0F, offVis - dt * 5.0F);
		float target = 0.0F;
		if (prone && player.onGround()) {
			var v = player.getDeltaMovement();
			target = Mth.clamp((float) Math.sqrt(v.x * v.x + v.z * v.z) / 0.06F, 0.0F, 1.2F);
		}
		speed += (target - speed) * Math.min(1.0F, dt * 6.0F);
		cycle += dt * strokeRate * Math.min(1.2F, speed);
		if (cycle > 1000.0F) {
			cycle -= 1000.0F;
		}
	}

	/** The push-up's progress (0..1), or -1 when not playing. */
	private static float push() {
		if (pushStart < 0L) {
			return -1.0F;
		}
		float t = (System.nanoTime() - pushStart) / 1.0E9F / Math.max(0.1F, pushTime);
		if (t >= 1.0F) {
			pushStart = -1L;
			return -1.0F;
		}
		return t;
	}

	/** Whether the off hand should be drawn this frame. */
	public static boolean showOffHand() {
		return offVis > 0.02F;
	}

	/** One arm's stroke at cycle position t (0..1): {forward (blocks, + = ahead), lift (blocks), elbow (rad)}. */
	private static void stroke(float t, float[] out) {
		final float reachEnd = 0.32F, plantEnd = 0.42F;
		final float far = 0.30F, near = -0.10F; // how far ahead it lands / where the pull ends (beside the shoulder)
		if (t < reachEnd) {
			float r = smooth(t / reachEnd);
			out[0] = Mth.lerp(r, near, far);
			out[1] = Mth.sin(r * Mth.PI) * 0.09F + 0.03F * r; // an arc up over the ground
			out[2] = Mth.lerp(r, 1.15F, 0.12F);
		} else if (t < plantEnd) {
			float p = smooth((t - reachEnd) / (plantEnd - reachEnd));
			out[0] = far + 0.02F * p;
			out[1] = 0.03F * (1.0F - p) - 0.015F * Mth.sin(p * Mth.PI); // down onto the ground (a little give)
			out[2] = 0.12F + 0.12F * p;
		} else {
			float p = (t - plantEnd) / (1.0F - plantEnd);
			float e = p * p * (3.0F - 2.0F * p) * 0.65F + p * 0.35F; // a steady drag, not a flick
			out[0] = Mth.lerp(e, far + 0.02F, near);
			out[1] = -0.05F * e; // the hand going back under the shoulder sits lower in view
			out[2] = Mth.lerp(e, 0.24F, 1.15F);
		}
	}

	private static final float[] S = new float[3];

	/** The arm's stroke, blended with its resting place (planted ahead) by how fast you're crawling. */
	private static void armStroke(boolean main) {
		float t = cycle + (main ? 0.0F : 0.5F);
		t -= (float) Math.floor(t);
		stroke(t, S);
		float k = Mth.clamp(speed * 1.5F, 0.0F, 1.0F) * (main ? 0.85F : 1.0F);
		S[0] = Mth.lerp(k, 0.10F, S[0]);
		S[1] = Mth.lerp(k, 0.0F, S[1]);
		S[2] = Mth.lerp(k, 0.55F, S[2]);
	}

	/**
	 * Moves one arm for the crawl and the push-up. side = +1 right / -1 left; main = the sword arm (which fades out of the
	 * stroke while slashing: `attack` 0..1).
	 */
	public static void applyArm(PoseStack poseStack, int side, boolean main, float attack) {
		float p = push();
		float plant = p >= 0.0F ? smooth(p / 0.3F) : 0.0F;
		float w = weight * (main ? 1.0F - attack : 1.0F) * (1.0F - plant);
		if (w > 0.001F) {
			armStroke(main);
			float breathe = Mth.sin(System.nanoTime() / 1.0E9F * 1.6F + (main ? 0.0F : 1.3F)) * 0.006F;
			poseStack.translate(
				side * 0.17F * w,                              // wide apart, out at the shoulders
				(-0.07F + breathe + S[1]) * w,
				(-0.02F - S[0]) * w);
			// Reaching tilts the arm up the length of the reach; pulled back it points down at the ground
			poseStack.rotateDegrees(Axis.XP, (-4.0F + 22.0F * (S[0] - 0.10F)) * w);
			poseStack.rotateDegrees(Axis.YP, side * -6.0F * w); // forearms angled in a touch, like the hands on the ground
		}
		if (diveW > 0.001F) {
			// Superman: both arms straight out ahead and up, spread to the shoulders, a little flutter in the air
			float d = smooth(diveW) * (main ? 1.0F - attack : 1.0F);
			float flutter = Mth.sin(airTime * 9.0F + (main ? 0.0F : 1.7F)) * 0.012F * Math.min(1.0F, airTime * 4.0F);
			poseStack.translate(side * 0.14F * d, (0.05F + flutter) * d, -0.12F * d);
			poseStack.rotateDegrees(Axis.XP, 14.0F * d);
			poseStack.rotateDegrees(Axis.YP, side * -4.0F * d);
		}
		if (!main) {
			poseStack.translate(0.0F, -(1.0F - offVis) * 0.7F, 0.0F); // rising into / dropping out of view
		}
		if (p >= 0.0F) {
			float press = smooth((p - 0.25F) / 0.45F);      // the body rises, the hands stay on the ground: down out of view
			float back = main ? smooth((p - 0.65F) / 0.35F) : 0.0F; // the sword arm comes back up
			float on = (1.0F - back) * (main ? 1.0F - attack : 1.0F);
			poseStack.translate(
				side * 0.20F * plant * on,                     // hands beside the chest, shoulder width
				(-0.14F * plant - 0.6F * press) * on,
				(0.04F * plant) * on);
			poseStack.rotateDegrees(Axis.XP, -18.0F * plant * on); // palms down on the ground
		}
	}

	/** The elbow bend for this arm (radians), given what it would be otherwise. */
	public static float elbow(float normal, boolean main, float attack) {
		float e = normal;
		float p = push();
		float plant = p >= 0.0F ? smooth(p / 0.3F) : 0.0F;
		float w = weight * (main ? 1.0F - attack : 1.0F) * (1.0F - plant);
		if (w > 0.001F) {
			armStroke(main);
			e = Mth.lerp(w, e, S[2]);
		}
		if (diveW > 0.001F) {
			e = Mth.lerp(smooth(diveW) * (main ? 1.0F - attack : 1.0F), e, 0.06F); // straight arms in the dive
		}
		if (p >= 0.0F) {
			// Bent as the hands plant, straightening through the press
			float bend = Mth.lerp(smooth((p - 0.25F) / 0.45F), 1.25F * plant, 0.1F);
			float on = main ? (1.0F - smooth((p - 0.65F) / 0.35F)) * (1.0F - attack) : 1.0F;
			e = Mth.lerp(on, e, bend);
		}
		return e;
	}

	/** While prone, the crawl replaces the generic walk sway on the sword arm. */
	public static boolean crawling() {
		return weight > 0.001F;
	}
}
