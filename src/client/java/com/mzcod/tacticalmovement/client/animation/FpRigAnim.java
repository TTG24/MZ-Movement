package com.mzcod.tacticalmovement.client.animation;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/** First person: how much you are sprinting (eased in and out over a few frames, so the arm drops and lifts smoothly). */
public final class FpRigAnim {
	private static float sprint;
	private static long lastNanos;

	private FpRigAnim() {
	}

	public static void update() {
		long now = System.nanoTime();
		if (lastNanos != 0L && now - lastNanos < 2_000_000L) {
			return;
		}
		float dt = lastNanos == 0L ? 0.0F : Mth.clamp((now - lastNanos) / 1.0E9F, 0.0F, 0.1F);
		lastNanos = now;
		var player = Minecraft.getInstance().player;
		float target = player != null && player.isSprinting() && player.onGround() ? 1.0F : 0.0F;
		sprint += (target - sprint) * Math.min(1.0F, dt * 7.0F);
	}

	public static float sprint() {
		return sprint;
	}
}
