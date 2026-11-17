package com.mzcod.tacticalmovement.client.animation;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom poses and the animations that go with them (/tacticaldb, alias /tdb). Separate from the movement animations:
 * a pose overrides the player model after everything else has posed it, and its animation blends back out to whatever
 * the player was doing before (standing, crouching...), so nothing is left changed when it ends.
 *
 * <p>Pose "shock": on the back, legs out and spread, left hand on the heart, right arm out. Its animation: a jolt as if
 * shocked awake, a gasping sit-up with the hand still on the chest, then getting up to the previous state.
 */
public final class CustomPoses {
	private CustomPoses() {
	}

	/** Seconds: jolt, sit-up, gasp, stand. */
	private static final float JOLT_END = 0.35F, SIT_END = 1.25F, GASP_END = 1.65F, STAND_END = 2.6F;
	/** Hip height above the ground lying/sitting (half a leg's thickness, a bit more), and standing. */
	private static final float LOW_HIP = 0.15F, HIP = 0.75F;

	/** Editable part angles (radians) of the lying pose: /tdb tune <part> <x> <y> <z>. */
	public static final Map<String, float[]> SHOCK = new LinkedHashMap<>();

	static {
		SHOCK.put("head", new float[] { 0.0F, 0.3F, 0.0F });
		SHOCK.put("rarm", new float[] { 0.0F, 0.0F, 1.35F });
		SHOCK.put("rfore", new float[] { 0.0F, 0.0F, 0.0F });
		SHOCK.put("larm", new float[] { -0.6F, 0.0F, 0.45F });
		SHOCK.put("lfore", new float[] { -1.9F, 0.0F, 0.0F });
		SHOCK.put("rleg", new float[] { 0.0F, 0.0F, 0.3F });
		SHOCK.put("lleg", new float[] { 0.0F, 0.0F, -0.3F });
	}

	private record Entry(String pose, long animStart) {
	}

	private static final Map<Integer, Entry> ACTIVE = new HashMap<>();

	public static boolean pose(int entityId, String name) {
		if (!"shock".equals(name)) {
			return false;
		}
		ACTIVE.put(entityId, new Entry(name, -1L));
		return true;
	}

	/** Plays the pose's animation (from the pose, even if not currently posed). */
	public static boolean animate(int entityId, String name) {
		if (!"shock".equals(name)) {
			return false;
		}
		ACTIVE.put(entityId, new Entry(name, System.nanoTime()));
		return true;
	}

	public static boolean stop(int entityId) {
		return ACTIVE.remove(entityId) != null;
	}

	public static boolean isActive(int entityId) {
		return ACTIVE.containsKey(entityId);
	}

	/** One moment of the timeline: whole-body tilt back (deg), hip height, leg angle in the world, knee bend, upper-body weight. */
	private record Frame(float tilt, float lift, float legWorld, float knee, float weight, float t, boolean posed) {
	}

	private static Frame frame(int id) {
		Entry e = ACTIVE.get(id);
		if (e == null) {
			return null;
		}
		if (e.animStart < 0) {
			return new Frame(90.0F, LOW_HIP, 0.0F, 0.0F, 1.0F, 0.0F, true);
		}
		float t = (System.nanoTime() - e.animStart) / 1.0E9F;
		if (t >= STAND_END) {
			ACTIVE.remove(id);
			return null;
		}
		if (t < JOLT_END) {
			// Shocked: the chest bucks off the ground and drops back
			float k = Mth.sin(Mth.PI * t / JOLT_END);
			return new Frame(90.0F - 22.0F * k, LOW_HIP, 0.0F, 0.0F, 1.0F, t, false);
		}
		if (t < SIT_END) {
			float s = ease((t - JOLT_END) / (SIT_END - JOLT_END));
			return new Frame(90.0F * (1.0F - s), LOW_HIP, 0.0F, 0.0F, 1.0F, t, false);
		}
		if (t < GASP_END) {
			// Sitting, catching breath: a small heave forward
			float g = Mth.sin(Mth.PI * (t - SIT_END) / (GASP_END - SIT_END));
			return new Frame(-6.0F * g, LOW_HIP, 0.0F, 0.0F, 1.0F, t, false);
		}
		float s = (t - GASP_END) / (STAND_END - GASP_END);
		float es = ease(s);
		float tuck = Mth.sin(Mth.PI * Math.min(1.0F, s * 1.15F));
		// Lean forward over the feet, knees tucked in, then rise straight up
		return new Frame(-25.0F * tuck, Mth.lerp(es, LOW_HIP, HIP) - 0.12F * tuck, -Mth.HALF_PI * (1.0F - es) - 0.5F * tuck,
			1.3F * tuck, 1.0F - es, t, false);
	}

	private static float ease(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	/** Whole-body transform, after the renderer's body turn (front is -Z here). */
	public static void applyRotation(int id, PoseStack poseStack) {
		Frame f = frame(id);
		if (f == null) {
			return;
		}
		poseStack.translate(0.0F, f.lift, 0.0F);
		poseStack.mulPose(new org.joml.Matrix4f().rotationX((float) Math.toRadians(f.tilt)));
		poseStack.translate(0.0F, -HIP, 0.0F);
	}

	/** Part angles, after every other animation has posed the model. */
	public static void applyModel(int id, PlayerModel m) {
		Frame f = frame(id);
		if (f == null) {
			return;
		}
		float tiltRad = (float) Math.toRadians(f.tilt);
		// Legs keep their angle in the world while the body tilts: local = world + tilt (negative = forward). Until the
		// stand-up they lie flat along the ground, straight out in front (-PI/2 from an upright body).
		float legWorld = f.posed || f.t < GASP_END ? -Mth.HALF_PI : f.legWorld;
		float legX = legWorld + tiltRad;
		float w = f.weight;
		float spread = f.posed || f.t < SIT_END ? 1.0F : (f.t < GASP_END ? 0.6F : 0.6F * w);
		float[] rl = SHOCK.get("rleg"), ll = SHOCK.get("lleg");
		set(m.rightLeg, legX, rl[1] * spread, rl[2] * spread, 1.0F);
		set(m.leftLeg, legX, ll[1] * spread, ll[2] * spread, 1.0F);
		shin(m.rightLeg, "right_shin", f.knee);
		shin(m.leftLeg, "left_shin", f.knee);
		m.body.xRot = 0.0F;
		m.body.yRot = 0.0F;
		m.body.zRot = 0.0F;

		float[] head = SHOCK.get("head").clone(), rarm = SHOCK.get("rarm").clone(), rfore = SHOCK.get("rfore").clone();
		float[] larm = SHOCK.get("larm"), lfore = SHOCK.get("lfore");
		if (!f.posed) {
			if (f.t < JOLT_END) {
				// The jolt: head thrown back, right arm flings up off the ground
				float k = Mth.sin(Mth.PI * f.t / JOLT_END);
				head[0] -= 0.5F * k;
				head[1] *= 1.0F - k;
				rarm[0] -= 0.9F * k;
				rarm[2] -= 0.4F * k;
			} else {
				// Sitting up: head comes forward, right arm swings back to push off the ground
				float s = ease((f.t - JOLT_END) / (SIT_END - JOLT_END));
				head[0] = Mth.lerp(s, 0.0F, 0.35F);
				head[1] = Mth.lerp(s, head[1], 0.0F);
				rarm[0] = Mth.lerp(s, 0.0F, 0.45F);
				rarm[2] = Mth.lerp(s, rarm[2], 0.35F);
				rfore[0] = 0.0F;
				if (f.t >= SIT_END && f.t < GASP_END) {
					head[0] += 0.12F * Mth.sin(Mth.PI * 2.0F * (f.t - SIT_END) / (GASP_END - SIT_END));
				}
			}
		}
		set(m.head, head[0], head[1], head[2], w);
		set(m.rightArm, rarm[0], rarm[1], rarm[2], w);
		set(m.leftArm, larm[0], larm[1], larm[2], w);
		child(m.rightArm, "right_forearm", rfore, w);
		child(m.leftArm, "left_forearm", lfore, w);
	}

	/** Blends a part from its current (vanilla/tactical) angles toward the target by w. */
	private static void set(ModelPart p, float x, float y, float z, float w) {
		p.xRot = Mth.lerp(w, p.xRot, x);
		p.yRot = Mth.lerp(w, p.yRot, y);
		p.zRot = Mth.lerp(w, p.zRot, z);
	}

	private static void child(ModelPart arm, String name, float[] r, float w) {
		if (arm.hasChild(name)) {
			set(arm.getChild(name), r[0], r[1], r[2], w);
		}
	}

	private static void shin(ModelPart leg, String name, float bend) {
		if (leg.hasChild(name)) {
			ModelPart s = leg.getChild(name);
			s.xRot = bend;
			s.yRot = 0.0F;
			s.zRot = 0.0F;
		}
	}
}
