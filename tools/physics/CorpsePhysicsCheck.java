package com.mzcod.tacticalmovement.client.wounds;

import java.lang.reflect.*;
import java.util.Random;

public class CorpsePhysicsCheck {
	public static void main(String[] a) throws Exception {
		Class<?> c = Corpses.class;
		Class<?> bc = Class.forName(c.getName() + "$Body");
		Constructor<?> ctor = bc.getDeclaredConstructor(int.class, boolean.class);
		ctor.setAccessible(true);
		Method step = c.getDeclaredMethod("step", net.minecraft.client.multiplayer.ClientLevel.class, bc);
		step.setAccessible(true);
		Method place = c.getDeclaredMethod("placeJoints", double[][].class, double.class, double.class);
		place.setAccessible(true);
		Field fStill0 = null; Field fPos = bc.getDeclaredField("pos"), fOld = bc.getDeclaredField("old"), fAsleep = bc.getDeclaredField("asleep"), fAge = bc.getDeclaredField("age"), fLegless = bc.getDeclaredField("legless");
		for (Field f : new Field[] {fPos, fOld, fAsleep, fAge, fLegless}) f.setAccessible(true);
		int trials = Integer.parseInt(a[0]);
		Random R = new Random(42);
		int spinners = 0, never = 0;
		for (int t = 0; t < trials; t++) {
			Object b = ctor.newInstance(-1, false);
			double[][] pos = (double[][]) fPos.get(b), old = (double[][]) fOld.get(b);
			double yaw = R.nextDouble() * Math.PI * 2, fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
			double HS = 0.3125, HO = 0.25, ARM = 0.625, HIP = 0.119, DROP = 0.125;
			double x = 0, y = 0, z = 0;
			set(pos[0], x, y + .75, z); set(pos[1], x, y + 1.5, z); set(pos[2], x - rx * HS, y + 1.5, z - rz * HS); set(pos[3], x, y + 1.5 + HO, z);
			double sy = y + 1.5 - DROP;
			set(pos[4], x + rx * HS + fx * ARM * .85, sy - .15, z + rz * HS + fz * ARM * .85);
			set(pos[5], x - rx * HS + fx * ARM * .85, sy - .15, z - rz * HS + fz * ARM * .85);
			set(pos[6], x + rx * HIP, y + .02, z + rz * HIP); set(pos[7], x - rx * HIP, y + .02, z - rz * HIP);
			place.invoke(null, pos, fx, fz);
			// a hit like Corpses.spawn: knockback along a direction, the struck part more, a twist, limb flings
			double dx = R.nextGaussian(), dz = R.nextGaussian(), dl = Math.hypot(dx, dz); dx /= dl; dz /= dl;
			double power = 0.1 + R.nextDouble() * 0.2;
			int struck = R.nextInt(12);
			for (int i = 0; i < 12; i++) {
				double k = i == struck ? 1.3 : 0.45;
				double vx = dx * power * k, vy = 0.05, vz = dz * power * k;
				if (i == 2) { double tw = (R.nextDouble() - .5) * .12; vx += fx * tw; vz += fz * tw; }
				if (i >= 4) { vx += (R.nextDouble() - .5) * .06; vy += R.nextDouble() * .04; vz += (R.nextDouble() - .5) * .06; }
				old[i][0] = pos[i][0] - vx; old[i][1] = pos[i][1] - vy; old[i][2] = pos[i][2] - vz;
			}
			double prevYaw = torsoYaw(pos), unwrapped = 0, at200 = 0, at400 = 0;
			int asleepAt = -1;
			double maxSpeed = 0;
			for (int tick = 0; tick < 600; tick++) {
				fAge.setInt(b, tick);
				if (!fAsleep.getBoolean(b)) step.invoke(null, null, b);
				else if (asleepAt < 0) asleepAt = tick;
				double y2 = torsoYaw(pos), d = y2 - prevYaw;
				while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI;
				unwrapped += d; prevYaw = y2;
				if (tick == 200) at200 = unwrapped;
				if (tick == 400) at400 = unwrapped;
			}
			double spin = Math.toDegrees(Math.abs(unwrapped - at400)); // degrees turned in the last 200 ticks
			boolean asleep = fAsleep.getBoolean(b);
			double mv = 0; for (int i = 0; i < 12; i++) for (int k = 0; k < 3; k++) mv = Math.max(mv, Math.abs(pos[i][k] - old[i][k])); if (t < 5) System.out.printf("  residual speed %.4f, still=%d%n", mv, stillOf(bc, b));
			if (spin > 20) spinners++; if (!asleep) never++;
			if (a.length > 1 && (t < 12 || spin > 20)) System.out.printf("trial %d: asleep=%b (tick %d) turned %.1f deg in last 200 ticks, %.1f deg in 200-400%n", t, asleep, asleepAt, spin, Math.toDegrees(Math.abs(at400 - at200)));
		}
		System.out.println("spinning (>20 deg in last 200 ticks): " + spinners + ", never asleep: " + never + " / " + trials);
	}

	static int stillOf(Class<?> bc, Object b) throws Exception { Field f = bc.getDeclaredField("still"); f.setAccessible(true); return f.getInt(b); }
	static void set(double[] p, double x, double y, double z) { p[0] = x; p[1] = y; p[2] = z; }

	static double torsoYaw(double[][] p) {
		return Math.atan2(p[2][2] - p[1][2], p[2][0] - p[1][0]);
	}
}
