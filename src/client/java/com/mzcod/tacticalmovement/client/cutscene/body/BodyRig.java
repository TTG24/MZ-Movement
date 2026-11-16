package com.mzcod.tacticalmovement.client.cutscene.body;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * A player body for cutscenes that bends like a real one: a skeleton (hips, a spine that bends, chest, neck, head; shoulders,
 * elbows, hands; hips, knees, feet) posed by the scene every frame, arms and legs reaching for targets with two-bone IK
 * (the elbow or knee bends to get the hand or foot exactly there), drawn with the player's skin on limbs that bend smoothly
 * at the joint instead of being one stiff box each.
 *
 * <p>The body rides on an ordinary entity (a {@code CutsceneClone}, for the skin, light and culling); its normal model is
 * swapped for this one (see {@code BodyRigRenderMixin}).
 */
public final class BodyRig {
	/** Gives the body's pose for a frame (partialTicks between the last tick and this one). */
	public interface Animator {
		Pose pose(float partialTicks);
	}

	/**
	 * Where the body is and how it is held. Pelvis = the bottom middle of the torso; up = along the spine; front = the way the
	 * chest faces (right = front x up). Hands and feet are where they should end up (world); the poles say which way an elbow or
	 * knee points (world directions; null = the natural way).
	 */
	public static final class Pose {
		public Vec3 pelvis = Vec3.ZERO;
		public Vec3 up = new Vec3(0, 1, 0), front = new Vec3(0, 0, 1);
		/** Spine: bent forward (radians, + = chest toward the front), twisted (about the spine), leaning to the right. */
		public float bend, twist, lean;
		/** Head, from the chest: turned (+ = to the right... about the spine) and pitched (+ = chin down). */
		public float headYaw, headPitch;
		public Vec3 handR, handL, footR, footL;
		public Vec3 elbowR, elbowL, kneeR, kneeL;
		public boolean hideHead, hideArmR, hideArmL, hideBody, hideLegR, hideLegL;

		/** The body's right, from its up and front. */
		public Vec3 right() {
			return front.cross(up).normalize();
		}
	}

	/** Every joint of a posed body (world), and the frames of the pelvis, chest and head. */
	public static final class Joints {
		public Vec3 pelvis, mid, neck, headCenter, eyes;
		public Vec3 pR, pU, pF, mR, mU, mF, cR, cU, cF, hR, hU, hF;
		public Vec3 shoulderR, shoulderL, elbowR, elbowL, handR, handL;
		public Vec3 hipR, hipL, kneeR, kneeL, footR, footL;
		public Vec3 elbowPoleR, elbowPoleL, kneePoleR, kneePoleL;
		public float armWidth;
		/** Where the torso's top is drawn, when not at the neck (a posed player model: the head can sit apart from it). */
		public Vec3 torsoTop;
	}

	public static final double SEG = 0.375; // upper/lower arm and leg (6 px each)
	private static final Map<Integer, Animator> RIGS = new HashMap<>();
	private static final Map<Integer, Boolean> SLIM = new HashMap<>();

	private BodyRig() {
	}

	public static void attach(int entityId, Animator animator) {
		RIGS.put(entityId, animator);
	}

	public static void detach(int entityId) {
		RIGS.remove(entityId);
		SLIM.remove(entityId);
	}

	public static boolean has(int entityId) {
		return RIGS.containsKey(entityId);
	}

	/** The body's joints this frame (null if it has no rig). */
	public static Joints joints(int entityId, float partialTicks) {
		Animator a = RIGS.get(entityId);
		return a == null ? null : solve(a.pose(partialTicks), SLIM.getOrDefault(entityId, false));
	}

	// ------------------------------------------------------------------------------------------------------ the skeleton

	/** Turns v about the axis k (unit) by a radians. */
	public static Vec3 rot(Vec3 v, Vec3 k, double a) {
		double c = Math.cos(a), s = Math.sin(a);
		return v.scale(c).add(k.cross(v).scale(s)).add(k.scale(k.dot(v) * (1 - c)));
	}

	private static Vec3 avg(Vec3 a, Vec3 b) {
		Vec3 m = a.add(b);
		return m.lengthSqr() < 1.0E-8 ? a : m.normalize();
	}

	public static Joints solve(Pose p, boolean slim) {
		Joints j = new Joints();
		j.armWidth = slim ? 3 : 4;
		Vec3 u = p.up.normalize();
		Vec3 f = p.front.subtract(u.scale(p.front.dot(u))).normalize();
		Vec3 r = f.cross(u).normalize();
		j.pelvis = p.pelvis;
		j.pR = r;
		j.pU = u;
		j.pF = f;
		// The chest: bent forward (about the right axis), twisted, leaned
		Vec3 cU = rot(u, r, -p.bend), cF = rot(f, r, -p.bend), cR = r;
		cF = rot(cF, cU, p.twist);
		cR = rot(cR, cU, p.twist);
		cU = rot(cU, cF, p.lean);
		cR = rot(cR, cF, p.lean);
		j.cR = cR;
		j.cU = cU;
		j.cF = cF;
		// The spine bends in the middle: the lower half goes up the pelvis, the upper half up the chest
		j.mid = p.pelvis.add(u.scale(0.375));
		j.neck = j.mid.add(cU.scale(0.375));
		j.mU = avg(u, cU);
		j.mF = avg(f, cF);
		j.mR = j.mF.cross(j.mU).normalize();
		// The head: turned, then pitched
		Vec3 hF = rot(cF, cU, -p.headYaw), hR = rot(cR, cU, -p.headYaw), hU = cU;
		hU = rot(hU, hR, -p.headPitch);
		hF = rot(hF, hR, -p.headPitch);
		j.hR = hR;
		j.hU = hU;
		j.hF = hF;
		j.headCenter = j.neck.add(hU.scale(0.25));
		j.eyes = j.neck.add(hU.scale(0.25)).add(hF.scale(0.05));
		// Arms from the shoulders (the top of the arm, at the side of the chest)
		double armOff = (4 + j.armWidth / 2.0) / 16.0;
		j.shoulderR = j.neck.add(cR.scale(armOff)).subtract(cU.scale(0.06));
		j.shoulderL = j.neck.subtract(cR.scale(armOff)).subtract(cU.scale(0.06));
		j.elbowPoleR = p.elbowR != null ? p.elbowR : cF.scale(-1).add(cR.scale(0.3));
		j.elbowPoleL = p.elbowL != null ? p.elbowL : cF.scale(-1).subtract(cR.scale(0.3));
		Vec3 handR = p.handR != null ? p.handR : j.shoulderR.subtract(cU.scale(0.74));
		Vec3 handL = p.handL != null ? p.handL : j.shoulderL.subtract(cU.scale(0.74));
		Vec3[] ar = ik(j.shoulderR, handR, j.elbowPoleR), al = ik(j.shoulderL, handL, j.elbowPoleL);
		j.elbowR = ar[0];
		j.handR = ar[1];
		j.elbowL = al[0];
		j.handL = al[1];
		// Legs from the hips
		j.hipR = p.pelvis.add(r.scale(0.125));
		j.hipL = p.pelvis.subtract(r.scale(0.125));
		j.kneePoleR = p.kneeR != null ? p.kneeR : f;
		j.kneePoleL = p.kneeL != null ? p.kneeL : f;
		Vec3 footR = p.footR != null ? p.footR : j.hipR.subtract(u.scale(0.74));
		Vec3 footL = p.footL != null ? p.footL : j.hipL.subtract(u.scale(0.74));
		Vec3[] lr = ik(j.hipR, footR, j.kneePoleR), ll = ik(j.hipL, footL, j.kneePoleL);
		j.kneeR = lr[0];
		j.footR = lr[1];
		j.kneeL = ll[0];
		j.footL = ll[1];
		return j;
	}

	/** Two bones of SEG each from a to the target t, the middle joint bending toward the pole: {middle joint, end}. */
	public static Vec3[] ik(Vec3 a, Vec3 t, Vec3 pole) {
		Vec3 d = t.subtract(a);
		double len = d.length();
		Vec3 n = len < 1.0E-5 ? new Vec3(0, -1, 0) : d.scale(1 / len);
		double reach = Math.min(Math.max(len, 0.02), SEG * 2 - 1.0E-4);
		double along = reach / 2;
		double h = Math.sqrt(Math.max(0, SEG * SEG - along * along));
		Vec3 side = pole.subtract(n.scale(pole.dot(n)));
		if (side.lengthSqr() < 1.0E-8) {
			side = Math.abs(n.y) < 0.9 ? new Vec3(0, 1, 0).subtract(n.scale(n.y)) : new Vec3(1, 0, 0).subtract(n.scale(n.x));
		}
		side = side.normalize();
		Vec3 mid = a.add(n.scale(along)).add(side.scale(h));
		return new Vec3[] {mid, a.add(n.scale(reach))};
	}

	// ------------------------------------------------------------------------------------------------------ drawing

	/** Draws the rigged body instead of the normal player model. */
	public static void submit(AvatarRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
		Animator a = RIGS.get(state.id);
		if (a == null) {
			return;
		}
		boolean slim = state.skin.model() == PlayerModelType.SLIM;
		SLIM.put(state.id, slim);
		float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Pose p = a.pose(pt);
		Joints j = solve(p, slim);
		Vec3 origin = new Vec3(state.x, state.y, state.z);
		int light = state.lightCoords;
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(state.skin.body().texturePath()),
			(pose, vc) -> draw(new Out(pose, vc, origin, light), j, p, new Look(state.showHat, state.showJacket, state.showRightSleeve,
				state.showLeftSleeve, state.showRightPants, state.showLeftPants, false)));
	}

	/** Which layers to draw: the outer ones (player skins), whether left limbs reuse the right ones mirrored (mobs). */
	private record Look(boolean hat, boolean jacket, boolean sleeveR, boolean sleeveL, boolean pantsR, boolean pantsL, boolean mirrorLeft) {
	}

	private static final net.minecraft.resources.Identifier MOB_EYES = com.mzcod.tacticalmovement.TacticalMovement.id(
		"textures/entity/zombie_eyes.png");

	/**
	 * Draws a player body from joints already worked out (in whatever space `pose` maps from), into the model's own
	 * buffer: show = {head, body, right arm, left arm, right leg, left leg, hat, jacket, right sleeve, left sleeve,
	 * right pants, left pants}.
	 */
	public static void drawPlayer(PoseStack.Pose pose, VertexConsumer vc, Joints j, boolean[] show, int light, int overlay, int color) {
		Pose p = new Pose();
		p.hideHead = !show[0];
		p.hideBody = !show[1];
		p.hideArmR = !show[2];
		p.hideArmL = !show[3];
		p.hideLegR = !show[4];
		p.hideLegL = !show[5];
		draw(new Out(pose, vc, Vec3.ZERO, light, overlay, color), j, p,
			new Look(show[6], show[7], show[8], show[9], show[10], show[11], false));
	}

	/** One arm on its own (first person): shoulder, elbow, hand, the way its front faces; its skin and sleeve. */
	public static void drawArm(PoseStack.Pose pose, VertexConsumer vc, Vec3 shoulder, Vec3 elbow, Vec3 hand, Vec3 front, float width, boolean right,
		boolean sleeve, int light) {
		Out o = new Out(pose, vc, Vec3.ZERO, light);
		Section[] arm = limb(shoulder, elbow, hand, front);
		box(o, arm, width, 4, right ? 40 : 32, right ? 16 : 48, 0);
		if (sleeve) {
			box(o, arm, width, 4, right ? 40 : 48, right ? 32 : 48, 0.25F);
		}
	}

	/** A cutscene zombie on a rig (its animator gives the pose). */
	public static void submitMob(net.minecraft.client.renderer.entity.state.LivingEntityRenderState state, int id,
		net.minecraft.resources.Identifier texture, PoseStack poseStack, SubmitNodeCollector collector) {
		Animator a = RIGS.get(id);
		if (a != null) {
			submitMobPose(state, a.pose(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false)), texture, null, poseStack, collector);
		}
	}

	/**
	 * A mob drawn on the rig in a given pose: its texture (humanoid layout, left limbs mirrored), flashing red when hurt,
	 * its wounds over it (if any), and its glowing eyes.
	 */
	public static void submitMobPose(net.minecraft.client.renderer.entity.state.LivingEntityRenderState state, Pose p,
		net.minecraft.resources.Identifier texture, net.minecraft.resources.Identifier wounds, PoseStack poseStack, SubmitNodeCollector collector) {
		Joints j = solve(p, false);
		Vec3 origin = new Vec3(state.x, state.y, state.z);
		int overlay = OverlayTexture.pack(OverlayTexture.u(0.0F), OverlayTexture.v(state.hasRedOverlay));
		Look look = new Look(true, false, false, false, false, false, true);
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(texture),
			(pose, vc) -> draw(new Out(pose, vc, origin, state.lightCoords, overlay), j, p, look));
		if (wounds != null) {
			collector.order(1).submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(wounds),
				(pose, vc) -> draw(new Out(pose, vc, origin, state.lightCoords, overlay), j, p, new Look(false, false, false, false, false, false, true)));
		}
		if (!p.hideHead) {
			// Glowing (full-bright, drawn after the body), just proud of the face
			collector.order(2).submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(MOB_EYES), (pose, vc) -> {
				Out o = new Out(pose, vc, origin, 0xF000F0, OverlayTexture.NO_OVERLAY);
				box(o, new Section[] {new Section(j.neck.add(j.hU.scale(0.5)), j.hR, j.hU, j.hF, 0), new Section(j.neck, j.hR, j.hU, j.hF, 8)}, 8,
					8, 0, 0, 0.05F);
			});
		}
	}

	private record Out(PoseStack.Pose pose, VertexConsumer vc, Vec3 origin, int light, int overlay, int color) {
		Out(PoseStack.Pose pose, VertexConsumer vc, Vec3 origin, int light, int overlay) {
			this(pose, vc, origin, light, overlay, 0xFFFFFFFF);
		}

		Out(PoseStack.Pose pose, VertexConsumer vc, Vec3 origin, int light) {
			this(pose, vc, origin, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
		}

		void vertex(Vec3 w, float u, float v, Vec3 n) {
			Vector3f pos = pose.pose().transformPosition((float) (w.x - origin.x), (float) (w.y - origin.y), (float) (w.z - origin.z), new Vector3f());
			Vector3f nor = pose.transformNormal((float) n.x, (float) n.y, (float) n.z, new Vector3f());
			vc.addVertex(pos.x, pos.y, pos.z, color, u / 64.0F, v / 64.0F, overlay, light, nor.x, nor.y, nor.z);
		}
	}

	/** A cross-section of a box: its centre, its frame, and how far down the box (pixels) it is. */
	private record Section(Vec3 c, Vec3 r, Vec3 u, Vec3 f, float row) {
	}

	private static Section[] mirror(Section[] in) {
		Section[] out = new Section[in.length];
		for (int i = 0; i < in.length; i++) {
			out[i] = new Section(in[i].c, in[i].r.scale(-1), in[i].u, in[i].f, in[i].row);
		}
		return out;
	}

	private static void draw(Out o, Joints j, Pose p, Look s) {
		float aw = j.armWidth;
		// Torso: neck, the middle of the spine, the pelvis
		Section[] torso = {new Section(j.torsoTop != null ? j.torsoTop : j.neck, j.cR, j.cU, j.cF, 0), new Section(j.mid, j.mR, j.mU, j.mF, 6),
			new Section(j.pelvis, j.pR, j.pU, j.pF, 12)};
		if (!p.hideBody) {
			box(o, torso, 8, 4, 16, 16, 0);
			if (s.jacket()) {
				box(o, torso, 8, 4, 16, 32, 0.25F);
			}
		}
		if (!p.hideHead) {
			Section[] head = {new Section(j.neck.add(j.hU.scale(0.5)), j.hR, j.hU, j.hF, 0), new Section(j.neck, j.hR, j.hU, j.hF, 8)};
			box(o, head, 8, 8, 0, 0, 0);
			if (s.hat()) {
				box(o, head, 8, 8, 32, 0, 0.5F);
			}
		}
		// Arms bend toward the back at the elbow (their front faces away from the elbow); legs toward the front at the knee
		Section[] armR = limb(j.shoulderR, j.elbowR, j.handR, j.elbowPoleR.scale(-1));
		Section[] armL = limb(j.shoulderL, j.elbowL, j.handL, j.elbowPoleL.scale(-1));
		if (!p.hideArmR) {
			box(o, armR, aw, 4, 40, 16, 0);
			if (s.sleeveR()) {
				box(o, armR, aw, 4, 40, 32, 0.25F);
			}
		}
		if (!p.hideArmL) {
			if (s.mirrorLeft()) {
				box(o, mirror(armL), aw, 4, 40, 16, 0);
			} else {
				box(o, armL, aw, 4, 32, 48, 0);
			}
			if (s.sleeveL()) {
				box(o, armL, aw, 4, 48, 48, 0.25F);
			}
		}
		Section[] legR = limb(j.hipR, j.kneeR, j.footR, j.kneePoleR);
		Section[] legL = limb(j.hipL, j.kneeL, j.footL, j.kneePoleL);
		if (!p.hideLegR) {
			box(o, legR, 4, 4, 0, 16, 0);
			if (s.pantsR()) {
				box(o, legR, 4, 4, 0, 32, 0.25F);
			}
		}
		if (!p.hideLegL) {
			if (s.mirrorLeft()) {
				box(o, mirror(legL), 4, 4, 0, 16, 0);
			} else {
				box(o, legL, 4, 4, 16, 48, 0);
			}
			if (s.pantsL()) {
				box(o, legL, 4, 4, 0, 48, 0.25F);
			}
		}
	}

	/**
	 * A limb from a (top) through the joint b to c: straight along each bone, and around the joint a section halfway
	 * between the two bones' directions, so it bends smoothly instead of folding.
	 */
	private static Section[] limb(Vec3 a, Vec3 b, Vec3 c, Vec3 frontHint) {
		Vec3 u1 = a.subtract(b).normalize(), u2 = b.subtract(c).normalize();
		Vec3 f1 = frontHint.subtract(u1.scale(frontHint.dot(u1)));
		Vec3 f2 = frontHint.subtract(u2.scale(frontHint.dot(u2)));
		f1 = f1.lengthSqr() < 1.0E-8 ? perp(u1) : f1.normalize();
		f2 = f2.lengthSqr() < 1.0E-8 ? perp(u2) : f2.normalize();
		Vec3 r1 = f1.cross(u1).normalize(), r2 = f2.cross(u2).normalize();
		Vec3 um = avg(u1, u2), fm = avg(f1, f2);
		fm = fm.subtract(um.scale(fm.dot(um))).normalize();
		Vec3 rm = fm.cross(um).normalize();
		return new Section[] {new Section(a, r1, u1, f1, 0), new Section(a.lerp(b, 4 / 6.0), r1, u1, f1, 4), new Section(b, rm, um, fm, 6),
			new Section(b.lerp(c, 2 / 6.0), r2, u2, f2, 8), new Section(c, r2, u2, f2, 12)};
	}

	private static Vec3 perp(Vec3 u) {
		Vec3 t = Math.abs(u.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
		return t.subtract(u.scale(t.dot(u))).normalize();
	}

	/**
	 * A box w x d (pixels) through the sections (top first), skinned like a Minecraft model box whose texture starts at
	 * (u0, v0); inflated by `inf` pixels for the outer layer.
	 */
	private static void box(Out o, Section[] s, float w, float d, float u0, float v0, float inf) {
		int last = s.length - 1;
		float hw = (w / 2 + inf) / 16, hd = (d / 2 + inf) / 16, cap = inf / 16;
		Vec3[][] q = new Vec3[s.length][];
		for (int i = 0; i < s.length; i++) {
			Section x = s[i];
			Vec3 c = x.c;
			if (i == 0) {
				c = c.add(x.u.scale(cap));
			} else if (i == last) {
				c = c.subtract(x.u.scale(cap));
			}
			Vec3 r = x.r.scale(hw), f = x.f.scale(hd);
			// corners: +R+F, -R+F, -R-F, +R-F
			q[i] = new Vec3[] {c.add(r).add(f), c.subtract(r).add(f), c.subtract(r).subtract(f), c.add(r).subtract(f)};
		}
		float side = v0 + d;
		for (int i = 0; i < last; i++) {
			float va = side + s[i].row, vb = side + s[i + 1].row;
			Vec3[] t = q[i], b = q[i + 1];
			Vec3 nf = s[i].f, nr = s[i].r;
			// front (+F): +R at u0+d, -R at u0+d+w
			quad(o, t[0], u0 + d, va, t[1], u0 + d + w, va, b[1], u0 + d + w, vb, b[0], u0 + d, vb, nf);
			// back (-F): -R at u0+2d+w, +R at u0+2d+2w
			quad(o, t[2], u0 + 2 * d + w, va, t[3], u0 + 2 * d + 2 * w, va, b[3], u0 + 2 * d + 2 * w, vb, b[2], u0 + 2 * d + w, vb, nf.scale(-1));
			// right (+R): -F at u0, +F at u0+d
			quad(o, t[3], u0, va, t[0], u0 + d, va, b[0], u0 + d, vb, b[3], u0, vb, nr);
			// left (-R): +F at u0+d+w, -F at u0+2d+w
			quad(o, t[1], u0 + d + w, va, t[2], u0 + 2 * d + w, va, b[2], u0 + 2 * d + w, vb, b[1], u0 + d + w, vb, nr.scale(-1));
		}
		// top: +R at u0+d, -R at u0+d+w; -F at v0, +F at v0+d
		Vec3[] t = q[0];
		quad(o, t[3], u0 + d, v0, t[2], u0 + d + w, v0, t[1], u0 + d + w, v0 + d, t[0], u0 + d, v0 + d, s[0].u);
		// bottom: +R at u0+d+w, -R at u0+d+2w; +F at v0, -F at v0+d
		Vec3[] b = q[last];
		quad(o, b[0], u0 + d + w, v0, b[1], u0 + d + 2 * w, v0, b[2], u0 + d + 2 * w, v0 + d, b[3], u0 + d + w, v0 + d, s[last].u.scale(-1));
	}

	private static void quad(Out o, Vec3 a, float ua, float va, Vec3 b, float ub, float vb, Vec3 c, float uc, float vc, Vec3 d, float ud, float vd,
		Vec3 n) {
		o.vertex(a, ua, va, n);
		o.vertex(b, ub, vb, n);
		o.vertex(c, uc, vc, n);
		o.vertex(d, ud, vd, n);
	}
}
