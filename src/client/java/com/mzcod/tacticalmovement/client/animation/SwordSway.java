package com.mzcod.tacticalmovement.client.animation;

import com.mzcod.tacticalmovement.client.debug.TacticalPoseDebug;
import com.mzcod.tacticalmovement.movement.MovementStateAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

// Shared walking clock for the first-person sword sway AND the matching custom camera bob, so the
// hand and the view move in step. update() advances the clock once per rendered frame (extra calls in
// the same frame just return the current values), driven by the local player's horizontal speed.
public final class SwordSway {
	private static float phase = 0.0F;
	private static float speed = 0.0F;
	private static long lastNanos = 0L;

	private SwordSway() {
	}

	public static void update() {
		long now = System.nanoTime();
		if (lastNanos != 0L && now - lastNanos < 2_000_000L) {
			return;
		}
		float dt = lastNanos == 0L ? 0.0F : Mth.clamp((now - lastNanos) / 1.0E9F, 0.0F, 0.1F);
		lastNanos = now;
		var player = Minecraft.getInstance().player;
		float target = 0.0F;
		if (player != null && player.onGround()) {
			var motion = player.getDeltaMovement();
			// Crawling is much slower than walking, so normalise by a lower reference speed when prone.
			float reference = ((MovementStateAccess) player).tacticalMovement$getStance().isLowProfile() ? 0.07F : 0.2F;
			target = Mth.clamp((float) Math.sqrt(motion.x * motion.x + motion.z * motion.z) / reference, 0.0F, 1.3F);
		}
		speed += (target - speed) * Math.min(1.0F, dt * 8.0F);
		phase += dt * TacticalPoseDebug.fpSwayRate * speed;
		if (phase > Mth.TWO_PI * 100.0F) {
			phase -= Mth.TWO_PI * 100.0F;
		}
	}

	// Smoothed walking-speed factor: 0 = still / in the air, ~1 = walking, a bit more when sprinting.
	public static float speed() {
		return speed;
	}

	public static float phase() {
		return phase;
	}
}
