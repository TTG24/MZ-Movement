package com.mzcod.tacticalmovement.client.zombie;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mzcod.tacticalmovement.TacticalMovement;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Arena zombies drawn with mz_zombie.bbmodel (assets/tacticalmovement/models), the same model and animations BetterModel
 * shows players without the mod (tools/models/zombie_body.py makes both).
 *
 * Each zombie picks its style of idle / walk / sprint (and crawl) from its entity id, the same way the server plugin
 * does (ZombieModelHook.variant), so everyone sees it move the same way. Which one plays follows how fast its legs move
 * (vanilla's walk animation speed), cross-faded, played faster or slower to match; the claw plays over the upper body
 * when it swings; the head turns with its look. A crawler (ZombieCrawlers) falls when its legs blow off, then crawls.
 * A rising zombie plays "rise".
 *
 * Each zombie wears one of five looks (zombie_1..5.png, tools/models/zombie_skins.py), picked from its id like its
 * animation styles; the server gives players without the mod the matching mz_zombie_N model.
 *
 * Corpses (the ragdoll, Corpses) are drawn with the same model and look: every bone is placed from the ragdoll's points
 * (shoulder, elbow, hand; hip, knee, foot; neck, head), so it bends where the model bends.
 */
public final class ZombieModel {
	private static final Identifier MODEL = TacticalMovement.id("models/mz_zombie.bbmodel");
	private static final Identifier EYES = TacticalMovement.id("textures/entity/zombie_eyes.png");
	private static final Identifier GORE = TacticalMovement.id("textures/entity/zombie_gore.png");
	/** How many looks there are (must match ZombieModelHook.LOOKS on the server). */
	private static final int LOOKS = 5;
	private static final Identifier[] SKINS = new Identifier[LOOKS];
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("tacticalmovement");
	private static final float FADE = 0.25F;
	private static final int B = 9; // floats per bone in a pose: rotation, position, scale

	private static BbModel model;
	private static boolean failed;

	/** One zombie's animation: what plays now, what it is fading out of, and how far into each. */
	private static final class Anim {
		String current = "";
		float time;
		String previous;
		float previousTime, previousRate = 1;
		float fade = 1;
		float rate = 1;
		long lastNanos;
	}

	private static final Map<Integer, Anim> ANIMS = new HashMap<>();

	private ZombieModel() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.level == null) {
				ANIMS.clear();
				return;
			}
			if (mc.level.getGameTime() % 100 != 0) {
				return;
			}
			long stale = System.nanoTime() - 5_000_000_000L;
			ANIMS.values().removeIf(a -> a.lastNanos < stale);
		});
	}

	/** The model, read once; null (zombies drawn the old way) if it is missing or broken. */
	static BbModel model() {
		if (model == null && !failed) {
			try (var in = Minecraft.getInstance().getResourceManager().getResourceOrThrow(MODEL).open()) {
				model = BbModel.read(new InputStreamReader(in, StandardCharsets.UTF_8));
				LOG.info("[ZombieModel] loaded {}: {} bones, {} animations", MODEL, model.bones.size(), model.animations.size());
			} catch (Exception e) {
				failed = true;
				LOG.error("[ZombieModel] could not load {}; zombies are drawn the old way", MODEL, e);
			}
		}
		return model;
	}

	/** The look (skin) of the zombie with this (server) entity id. */
	public static Identifier skin(int entityId) {
		int v = variant(entityId, LOOKS, 7) - 1;
		if (SKINS[v] == null) {
			SKINS[v] = TacticalMovement.id("textures/entity/zombie_" + (v + 1) + ".png");
		}
		return SKINS[v];
	}

	/** Which of an animation's styles a zombie uses (1..count). Must match ZombieModelHook.variant on the server. */
	static int variant(int entityId, int count, int salt) {
		return Math.floorMod(entityId * 31 + salt * 17, count) + 1;
	}

	// ------------------------------------------------------------------------------------------------------- animating

	/** The zombie's pose this frame: [bone * 9] = rotation (degrees), position (pixels), scale, in Blockbench terms. */
	private static float[] pose(BbModel m, int id, LivingEntityRenderState state, Float riseTime) {
		int n = m.bones.size();
		float[] out = new float[n * B];
		if (riseTime != null) {
			sampleAll(m, m.animations.get("rise"), riseTime, out);
			return out;
		}
		Anim a = ANIMS.computeIfAbsent(id, k -> new Anim());
		long now = System.nanoTime();
		float dt = a.lastNanos == 0 ? 0 : Math.min(0.1F, (now - a.lastNanos) / 1.0E9F);
		a.lastNanos = now;

		float spd = state.walkAnimationSpeed;
		float crawlTime = ZombieCrawlers.secondsSince(id);
		boolean crawler = crawlTime >= 0;
		BbModel.Animation fall = m.animations.get("crawl_fall");
		String wanted;
		float rate;
		if (crawler && fall != null && crawlTime < fall.length) {
			wanted = "crawl_fall";
			rate = 1;
		} else if (crawler) {
			if (spd < 0.06F) {
				wanted = "crawl_idle_" + variant(id, 2, 4);
				rate = 1;
			} else if (spd < 0.2F) {
				wanted = "crawl_" + variant(id, 3, 5);
				rate = clamp(spd / 0.14F, 0.4F, 2.0F);
			} else {
				wanted = "crawl_fast_" + variant(id, 3, 6);
				rate = clamp(spd / 0.28F, 0.6F, 2.0F);
			}
		} else if (spd < 0.1F) {
			wanted = "idle_" + variant(id, 3, 1);
			rate = 1;
		} else if (spd < 0.67F) {
			wanted = "walk_" + variant(id, 4, 2);
			rate = clamp(spd / 0.45F, 0.4F, 2.0F);
		} else {
			wanted = "sprint_" + variant(id, 4, 3);
			rate = clamp(spd / 0.9F, 0.6F, 2.0F);
		}
		BbModel.Animation next = m.animations.get(wanted);
		if (next == null) {
			return out; // an old model file without it: rest pose
		}
		if (!wanted.equals(a.current)) {
			BbModel.Animation old = m.animations.get(a.current);
			a.previous = a.current.isEmpty() ? null : a.current;
			a.previousTime = a.time;
			a.previousRate = a.rate;
			// Walk <-> sprint (or crawl <-> fast crawl) keep their place in the step, so the feet don't skip
			a.time = old != null && old.loops() && next.loops() && !wanted.contains("idle") && !a.current.contains("idle")
				? a.time / old.length * next.length : 0;
			a.fade = a.previous == null ? 1 : 0;
			a.current = wanted;
		}
		a.rate = rate;
		a.time += dt * rate;
		a.previousTime += dt * a.previousRate;
		a.fade = Math.min(1, a.fade + dt / FADE);
		if (wanted.equals("crawl_fall")) {
			a.time = crawlTime; // in step with when the legs came off
		}

		sampleAll(m, next, a.time, out);
		BbModel.Animation prev = a.previous == null ? null : m.animations.get(a.previous);
		if (prev != null && a.fade < 1) {
			float[] from = new float[n * B];
			sampleAll(m, prev, a.previousTime, from);
			float k = smooth(a.fade);
			for (int i = 0; i < out.length; i++) {
				// Scale (legs on or off) never fades: it snaps to the new animation's
				if (i % B < 6) {
					out[i] = from[i] + (out[i] - from[i]) * k;
				}
			}
		}

		// The claw, over the upper body (the bones the attack keys); the legs keep walking
		float swing = state instanceof net.minecraft.client.renderer.entity.state.HumanoidRenderState h ? h.swingAnimation : 0;
		BbModel.Animation attack = m.animations.get(crawler ? "crawl_attack" : "attack");
		if (swing > 0 && attack != null && !wanted.equals("crawl_fall")) {
			float w = Math.min(1, (float) Math.sin(swing * Math.PI) * 3);
			float[] tmp = new float[B];
			for (int b = 0; b < n; b++) {
				if (attack.keys(b)) {
					attack.sample(b, swing * attack.length, tmp, 0);
					for (int i = 0; i < 6; i++) {
						out[b * B + i] += (tmp[i] - out[b * B + i]) * w;
					}
				}
			}
		}

		// The head follows its look (less when lying down: the neck is already bent back)
		Integer head = m.boneIndex.get("hi_head");
		if (head != null && !wanted.equals("crawl_fall")) {
			float k = crawler ? 0.5F : 1.0F;
			out[head * B] += -state.xRot * k;
			out[head * B + 1] += -clamp(state.yRot, -70, 70) * k;
		}
		return out;
	}

	private static void sampleAll(BbModel m, BbModel.Animation anim, float t, float[] out) {
		for (int b = 0; b < m.bones.size(); b++) {
			if (anim != null) {
				anim.sample(b, t, out, b * B);
			} else {
				out[b * B + 6] = out[b * B + 7] = out[b * B + 8] = 1;
			}
		}
	}

	// ------------------------------------------------------------------------------------------------------- drawing

	/**
	 * Draws a living arena zombie with the model, in place of the vanilla one. False when the model isn't available (the
	 * caller then draws it the old way).
	 *
	 * @param wounds the zombie's wound overlay (same UV layout as its skin), or null
	 */
	public static boolean submit(LivingEntityRenderState state, int id, Identifier skin, Identifier wounds, Float riseTime,
		PoseStack poseStack, SubmitNodeCollector collector) {
		BbModel m = model();
		if (m == null) {
			return false;
		}
		float[] pose = pose(m, id, state, riseTime);
		Matrix4f[] bones = matrices(m, pose);
		poseStack.pushPose();
		// Like vanilla's living renderer: turned to the body's facing, sized, then from pixels to blocks (the model's front
		// is -z, its right +x, y up: Blockbench's own axes)
		poseStack.mulPose(new Matrix4f().rotationY((float) Math.toRadians(180.0F - state.bodyRot)));
		poseStack.scale(state.scale, state.scale, state.scale);
		emit(m, bones, state, skin(id), wounds, poseStack, collector);
		poseStack.popPose();
		return true;
	}

	/**
	 * Draws a corpse with the model, its bones placed from the ragdoll's points. False when the model isn't available.
	 *
	 * @param wounds the corpse's wound overlay, or null
	 */
	public static boolean submitCorpse(LivingEntityRenderState state, com.mzcod.tacticalmovement.client.wounds.Corpses.Pose corpse,
		Identifier wounds, PoseStack poseStack, SubmitNodeCollector collector) {
		BbModel m = model();
		if (m == null || corpse.points() == null) {
			return false;
		}
		Matrix4f[] bones = corpseMatrices(m, corpse);
		poseStack.pushPose();
		emit(m, bones, state, skin(corpse.source()), wounds, poseStack, collector);
		poseStack.popPose();
		return true;
	}

	/** Emits the model's faces with the given bone matrices (pixels), from the pose stack's origin. */
	private static void emit(BbModel m, Matrix4f[] bones, LivingEntityRenderState state, Identifier skin, Identifier wounds,
		PoseStack poseStack, SubmitNodeCollector collector) {
		int overlay = OverlayTexture.pack(OverlayTexture.u(0.0F), OverlayTexture.v(state.hasRedOverlay));
		int light = state.lightCoords;
		poseStack.scale(1 / 16.0F, 1 / 16.0F, 1 / 16.0F);
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(skin),
			(p, vc) -> draw(m, bones, p, vc, 0, false, light, overlay));
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(GORE),
			(p, vc) -> draw(m, bones, p, vc, 2, false, light, overlay));
		if (wounds != null) {
			collector.order(1).submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(wounds),
				(p, vc) -> draw(m, bones, p, vc, 0, false, light, overlay));
		}
		collector.order(2).submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(EYES),
			(p, vc) -> draw(m, bones, p, vc, 1, true, 0xF000F0, OverlayTexture.NO_OVERLAY));
	}

	// ------------------------------------------------------------------------------------------------------- corpses

	private static final Matrix4f HIDDEN = new Matrix4f().scaling(0);
	/** How far a corpse's jaw hangs open (degrees). */
	private static final float DEAD_JAW = 28;

	/**
	 * Every bone's matrix (pixels, from the hips point) for a ragdoll: the torso turned to the ragdoll's torso, each limb
	 * part turned to point from its joint to the next point and moved so its pivot sits on the joint.
	 */
	private static Matrix4f[] corpseMatrices(BbModel m, com.mzcod.tacticalmovement.client.wounds.Corpses.Pose c) {
		double[][] pts = c.points();
		double[][] f = com.mzcod.tacticalmovement.client.wounds.Corpses.frame(pts);
		double[] up = f[0], right = f[1];
		double[] neck = pts[com.mzcod.tacticalmovement.client.wounds.Corpses.NECK];
		double[] hips = pts[com.mzcod.tacticalmovement.client.wounds.Corpses.HIPS];
		Matrix4f[] out = new Matrix4f[m.bones.size()];
		for (int i = 0; i < out.length; i++) {
			BbModel.Bone b = m.bones.get(i);
			out[i] = corpseBone(b, c, pts, f, neck, hips, right, up);
		}
		return out;
	}

	private static Matrix4f corpseBone(BbModel.Bone b, com.mzcod.tacticalmovement.client.wounds.Corpses.Pose c, double[][] pts,
		double[][] f, double[] neck, double[] hips, double[] right, double[] up) {
		boolean r = b.name.startsWith("right_");
		double side = r ? 1 : -1;
		double[] shoulder = com.mzcod.tacticalmovement.client.wounds.Corpses.joint(neck, right, up,
			side * 0.3125, -0.125);
		double[] hip = com.mzcod.tacticalmovement.client.wounds.Corpses.joint(hips, right, up, side * 0.119, 0);
		int elbow = r ? com.mzcod.tacticalmovement.client.wounds.Corpses.R_ELBOW : com.mzcod.tacticalmovement.client.wounds.Corpses.L_ELBOW;
		int hand = r ? com.mzcod.tacticalmovement.client.wounds.Corpses.R_HAND : com.mzcod.tacticalmovement.client.wounds.Corpses.L_HAND;
		int knee = r ? com.mzcod.tacticalmovement.client.wounds.Corpses.R_KNEE : com.mzcod.tacticalmovement.client.wounds.Corpses.L_KNEE;
		int foot = r ? com.mzcod.tacticalmovement.client.wounds.Corpses.R_FOOT : com.mzcod.tacticalmovement.client.wounds.Corpses.L_FOOT;
		String part = b.name.startsWith("right_") ? b.name.substring(6) : b.name.startsWith("left_") ? b.name.substring(5) : b.name;
		// The elbow and knee where the upper arm and thigh really end (the ragdoll's points can be a little off when a hand
		// or foot is caught on a block): the forearm and shin hang from there, aimed at the hand and foot
		double[] upperDir = dir(shoulder, pts[elbow]), thighDir = dir(hip, pts[knee]);
		double[] elbowAt = {shoulder[0] + upperDir[0] * 0.25, shoulder[1] + upperDir[1] * 0.25, shoulder[2] + upperDir[2] * 0.25};
		double[] kneeAt = {hip[0] + thighDir[0] * 0.375, hip[1] + thighDir[1] * 0.375, hip[2] + thighDir[2] * 0.375};
		return switch (part) {
			case "root", "torso", "chest" -> place(b, hips, f[1], f[0], 0, 12, 0);
			case "hi_head" -> c.headless() ? HIDDEN
				: place(b, neck, right, dir(neck, pts[com.mzcod.tacticalmovement.client.wounds.Corpses.HEAD]), 0, 24, 0);
			// The jaw hangs slack: the head's place, then dropped open about its hinge (negative X drops the front, as the
			// model's animations do)
			case "jaw" -> c.headless() ? HIDDEN
				: place(b, neck, right, dir(neck, pts[com.mzcod.tacticalmovement.client.wounds.Corpses.HEAD]), 0, 24, 0)
					.translate(b.px, b.py, b.pz).rotateX((float) Math.toRadians(-DEAD_JAW)).translate(-b.px, -b.py, -b.pz);
			case "arm" -> place(b, shoulder, right, neg(upperDir), side * 5, 22, 0);
			case "forearm", "hand" -> place(b, elbowAt, right, dir(pts[hand], elbowAt), side * 5, 18, 0);
			case "leg" -> place(b, hip, right, neg(thighDir), side * 1.9, 12, 0);
			case "wound" -> c.legless() ? place(b, hip, right, neg(thighDir), side * 1.9, 12, 0) : HIDDEN;
			case "shin", "foot" -> c.legless() ? HIDDEN : place(b, kneeAt, right, dir(pts[foot], kneeAt), side * 1.9, 6, 0);
			default -> place(b, hips, f[1], f[0], 0, 12, 0);
		};
	}

	private static double[] neg(double[] v) {
		return new double[] {-v[0], -v[1], -v[2]};
	}

	/** The unit direction from a to b (straight up when they meet). */
	private static double[] dir(double[] a, double[] b) {
		double x = b[0] - a[0], y = b[1] - a[1], z = b[2] - a[2];
		double l = Math.sqrt(x * x + y * y + z * z);
		return l < 1.0E-6 ? new double[] {0, 1, 0} : new double[] {x / l, y / l, z / l};
	}

	/**
	 * A bone's matrix: its model point (px, py, pz) on {@code at} (blocks from the hips), its +y along {@code upDir}, its +x
	 * as near {@code rightHint} as that allows, +z behind (the model's front is -z).
	 */
	private static Matrix4f place(BbModel.Bone b, double[] at, double[] rightHint, double[] upDir, double px, double py, double pz) {
		double[] y = upDir;
		double k = rightHint[0] * y[0] + rightHint[1] * y[1] + rightHint[2] * y[2];
		double[] x = {rightHint[0] - y[0] * k, rightHint[1] - y[1] * k, rightHint[2] - y[2] * k};
		double l = Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]);
		if (l < 1.0E-4) {
			// Pointing along its own side: any side at right angles
			x = Math.abs(y[1]) < 0.9 ? new double[] {y[2], 0, -y[0]} : new double[] {1, 0, 0};
			l = Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]);
		}
		x = new double[] {x[0] / l, x[1] / l, x[2] / l};
		double[] z = {x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0]};
		Matrix4f rot = new Matrix4f(
			(float) x[0], (float) x[1], (float) x[2], 0,
			(float) y[0], (float) y[1], (float) y[2], 0,
			(float) z[0], (float) z[1], (float) z[2], 0,
			0, 0, 0, 1);
		return new Matrix4f().translate((float) (at[0] * 16), (float) (at[1] * 16), (float) (at[2] * 16)).mul(rot)
			.translate((float) -px, (float) -py, (float) -pz);
	}

	/** Each bone's matrix (pixels, model space): parent * T(pivot + offset) * Rz * Ry * Rx * S * T(-pivot). */
	private static Matrix4f[] matrices(BbModel m, float[] pose) {
		Matrix4f[] out = new Matrix4f[m.bones.size()];
		for (int i = 0; i < out.length; i++) {
			BbModel.Bone b = m.bones.get(i);
			int o = i * B;
			Matrix4f local = new Matrix4f()
				.translate(b.px + pose[o + 3], b.py + pose[o + 4], b.pz + pose[o + 5])
				.rotateZ((float) Math.toRadians(pose[o + 2]))
				.rotateY((float) Math.toRadians(pose[o + 1]))
				.rotateX((float) Math.toRadians(pose[o]))
				.scale(pose[o + 6], pose[o + 7], pose[o + 8])
				.translate(-b.px, -b.py, -b.pz);
			out[i] = b.parent >= 0 ? new Matrix4f(out[b.parent]).mul(local) : local;
		}
		return out;
	}

	// Corners of each face (FACES order) as seen from outside: top-left, top-right, bottom-right, bottom-left, picked from
	// (x0|x1, y0|y1, z0|z1) by 0/1; and the face normals.
	private static final int[][][] CORNERS = {
		{{1, 1, 0}, {0, 1, 0}, {0, 0, 0}, {1, 0, 0}}, // north: seen from -z, +x is on the left
		{{1, 1, 1}, {1, 1, 0}, {1, 0, 0}, {1, 0, 1}}, // east
		{{0, 1, 1}, {1, 1, 1}, {1, 0, 1}, {0, 0, 1}}, // south
		{{0, 1, 0}, {0, 1, 1}, {0, 0, 1}, {0, 0, 0}}, // west
		{{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}}, // up
		{{0, 0, 1}, {1, 0, 1}, {1, 0, 0}, {0, 0, 0}}, // down
	};
	/** Corner order to emit: counter-clockwise seen from outside (top-left, bottom-left, bottom-right, top-right). */
	private static final int[] CCW = {0, 3, 2, 1};
	private static final float[][] NORMALS = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};

	/** Emits the faces drawn with one texture (glowing ones only, or all but those). */
	private static void draw(BbModel m, Matrix4f[] bones, PoseStack.Pose pose, VertexConsumer vc, int texture, boolean glowing,
		int light, int overlay) {
		float size = texture < m.uvSize.length ? m.uvSize[texture] : 64;
		Vector3f v = new Vector3f(), nrm = new Vector3f();
		for (int i = 0; i < bones.length; i++) {
			Matrix4f bm = bones[i];
			if (Math.abs(bm.determinant3x3()) < 1.0E-6F) {
				continue; // scaled to nothing (a blown-off leg)
			}
			for (BbModel.Cube c : m.bones.get(i).cubes) {
				if (c.glowing != glowing) {
					continue;
				}
				float[] xs = {c.x0, c.x1}, ys = {c.y0, c.y1}, zs = {c.z0, c.z1};
				for (int f = 0; f < 6; f++) {
					float[] uv = c.uv[f];
					if (uv == null || c.texture[f] != texture) {
						continue;
					}
					bm.transformDirection(NORMALS[f][0], NORMALS[f][1], NORMALS[f][2], nrm).normalize();
					pose.transformNormal(nrm.x, nrm.y, nrm.z, nrm);
					for (int k : CCW) {
						int[] cr = CORNERS[f][k];
						bm.transformPosition(xs[cr[0]], ys[cr[1]], zs[cr[2]], v);
						pose.pose().transformPosition(v);
						float u = (k == 0 || k == 3 ? uv[0] : uv[2]) / size;
						float t = (k < 2 ? uv[1] : uv[3]) / size;
						vc.addVertex(v.x, v.y, v.z, 0xFFFFFFFF, u, t, overlay, light, nrm.x, nrm.y, nrm.z);
					}
				}
			}
		}
	}

	private static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : Math.min(v, hi);
	}

	private static float smooth(float x) {
		x = clamp(x, 0, 1);
		return x * x * (3 - 2 * x);
	}
}
