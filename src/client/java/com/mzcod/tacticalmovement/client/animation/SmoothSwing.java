package com.mzcod.tacticalmovement.client.animation;

import com.mzcod.tacticalmovement.client.debug.TacticalPoseDebug;
import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.Map;

/**
 * A smoother sword slash than vanilla's (which snaps through in ~0.3 s at an even speed). When a swing starts, it plays on
 * its own clock instead, shaped like a real cut: a slow wind-up (the arm drawing back), the cut itself (fast, but eased in
 * and out, not instant), then a long follow-through settling back to rest. Kept per player (each swings on their own), and
 * in first person under its own key. The existing slow-motion debug (/tacticaledbg swordspeed) still stretches it.
 */
public final class SmoothSwing {
	/** How long a whole slash takes (seconds). */
	public static float duration = 0.5F;
	/** The first-person arm's key (entity ids are never this). */
	public static final int FIRST_PERSON = Integer.MIN_VALUE + 7;

	/** Per player: {clock (0..1, or -1 idle), last real swing, last output, last frame time (ns, as float bits... kept double)}. */
	private static final Map<Integer, double[]> STATE = new HashMap<>();

	private SmoothSwing() {
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	/** The slash's progress (what the swing poses read as 0..1) at time t (0..1) of it. */
	public static float shape(float t) {
		if (t < 0.28F) {
			return 0.18F * smooth(t / 0.28F); // wind-up
		}
		if (t < 0.58F) {
			return 0.18F + 0.67F * smooth((t - 0.28F) / 0.30F); // the cut
		}
		return 0.85F + 0.15F * smooth((t - 0.58F) / 0.42F); // follow-through
	}

	/** The smoothed swing for this player, given vanilla's `real` swing progress (0 when not swinging). */
	public static float of(int key, float real) {
		long now = System.nanoTime();
		if (STATE.size() > 64) {
			STATE.values().removeIf(v -> now - (long) v[3] > 5_000_000_000L);
		}
		double[] s = STATE.computeIfAbsent(key, k -> new double[] {-1, 0, 0, now});
		// Drawn more than once a frame (layers): the same answer, the clock only moves once
		if (now - (long) s[3] < 2_000_000L && s[3] != now) {
			return (float) s[2];
		}
		float dt = Mth.clamp((now - (long) s[3]) / 1.0E9F, 0.0F, 0.1F);
		s[3] = now;
		boolean started = real > 0.0F && (s[1] <= 0.0 || real < s[1] - 0.05);
		s[1] = real;
		if (started) {
			s[0] = 0.0001; // a new slash (also cuts short one still finishing)
		}
		if (s[0] < 0) {
			s[2] = 0;
			return 0.0F;
		}
		float speed = TacticalPoseDebug.swordSwingSpeed > 0.0F && TacticalPoseDebug.swordSwingSpeed < 1.0F ? TacticalPoseDebug.swordSwingSpeed : 1.0F;
		s[0] += dt * speed / Math.max(0.05F, duration);
		if (s[0] >= 1.0) {
			s[0] = -1;
			s[2] = 0;
			return 0.0F;
		}
		s[2] = shape((float) s[0]);
		return (float) s[2];
	}
}
