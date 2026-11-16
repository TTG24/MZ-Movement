package com.mzcod.tacticalmovement.client.cutscene.body;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws the player model with the bending body instead of its boxes. Every animation still poses the model the usual way
 * (stances, transitions, the sword, the pose editor); this reads where that put each joint (shoulder, elbow, hand; hip,
 * knee, foot; the torso and head) and draws the skin over them with limbs that bend smoothly at the elbow and knee.
 */
public final class PlayerRig {
	/** Whether a player model is slim-armed (the model's own flag). */
	public interface SlimAccess {
		boolean tacticalMovement$slim();
	}

	public static boolean enabled = true;

	private PlayerRig() {
	}

	/** The transform of a part (through its parents) from the model's root space, in blocks. */
	private static Matrix4f pose(ModelPart root, ModelPart... chain) {
		PoseStack ps = new PoseStack();
		root.translateAndRotate(ps);
		for (ModelPart p : chain) {
			p.translateAndRotate(ps);
		}
		return new Matrix4f(ps.last().pose());
	}

	private static Vec3 at(Matrix4f m, float px, float py, float pz) {
		Vector3f v = m.transformPosition(px / 16.0F, py / 16.0F, pz / 16.0F, new Vector3f());
		return new Vec3(v.x, v.y, v.z);
	}

	private static Vec3 dir(Matrix4f m, float x, float y, float z) {
		Vector3f v = m.transformDirection(x, y, z, new Vector3f());
		return new Vec3(v.x, v.y, v.z).normalize();
	}

	/** Draws the posed model. False if it can't (no elbows/knees on this model): then it is drawn the normal way. */
	public static boolean draw(PlayerModel m, PoseStack poseStack, VertexConsumer vc, int light, int overlay, int color) {
		if (!enabled || !m.rightArm.hasChild("right_forearm") || !m.leftArm.hasChild("left_forearm") || !m.rightLeg.hasChild("right_shin")
			|| !m.leftLeg.hasChild("left_shin")) {
			return false;
		}
		ModelPart root = m.root();
		boolean slim = m instanceof SlimAccess a && a.tacticalMovement$slim();
		float aw = slim ? 3 : 4;
		BodyRig.Joints j = new BodyRig.Joints();
		j.armWidth = aw;
		// Model space: the entity's right is -X, up is -Y, its front is -Z
		Matrix4f body = pose(root, m.body);
		j.neck = at(body, 0, 0, 0);
		j.pelvis = at(body, 0, 12, 0);
		j.mid = j.neck.lerp(j.pelvis, 0.5);
		j.cR = j.mR = j.pR = dir(body, -1, 0, 0);
		j.cU = j.mU = j.pU = dir(body, 0, -1, 0);
		j.cF = j.mF = j.pF = dir(body, 0, 0, -1);
		Matrix4f head = pose(root, m.head);
		j.hR = dir(head, -1, 0, 0);
		j.hU = dir(head, 0, -1, 0);
		j.hF = dir(head, 0, 0, -1);
		// The head box sits on its own pivot (it can be moved apart from the torso's top, e.g. lying prone)
		Vec3 headBase = at(head, 0, 0, 0);
		// Arms: the box centre line, from the top of the upper arm, through the elbow, to the end of the forearm
		float cr = slim ? -0.5F : -1.0F, cl = slim ? 0.5F : 1.0F;
		Matrix4f armR = pose(root, m.rightArm), foreR = pose(root, m.rightArm, m.rightArm.getChild("right_forearm"));
		Matrix4f armL = pose(root, m.leftArm), foreL = pose(root, m.leftArm, m.leftArm.getChild("left_forearm"));
		j.shoulderR = at(armR, cr, -2, 0);
		j.elbowR = at(foreR, cr, 0, 0);
		j.handR = at(foreR, cr, 6, 0);
		j.shoulderL = at(armL, cl, -2, 0);
		j.elbowL = at(foreL, cl, 0, 0);
		j.handL = at(foreL, cl, 6, 0);
		j.elbowPoleR = dir(armR, 0, 0, 1);
		j.elbowPoleL = dir(armL, 0, 0, 1);
		// Legs: hip, knee (the shin's pivot), foot
		Matrix4f legR = pose(root, m.rightLeg), shinR = pose(root, m.rightLeg, m.rightLeg.getChild("right_shin"));
		Matrix4f legL = pose(root, m.leftLeg), shinL = pose(root, m.leftLeg, m.leftLeg.getChild("left_shin"));
		j.hipR = at(legR, 0, 0, 0);
		j.kneeR = at(shinR, 0, 0, 0);
		j.footR = at(shinR, 0, 6, 0);
		j.hipL = at(legL, 0, 0, 0);
		j.kneeL = at(shinL, 0, 0, 0);
		j.footL = at(shinL, 0, 6, 0);
		j.kneePoleR = dir(legR, 0, 0, -1);
		j.kneePoleL = dir(legL, 0, 0, -1);
		j.torsoTop = j.neck;
		j.neck = headBase;
		boolean[] show = {m.head.visible, m.body.visible, m.rightArm.visible, m.leftArm.visible, m.rightLeg.visible, m.leftLeg.visible,
			m.hat.visible, m.jacket.visible, m.rightSleeve.visible, m.leftSleeve.visible, m.rightPants.visible, m.leftPants.visible};
		BodyRig.drawPlayer(poseStack.last(), vc, j, show, light, overlay, color);
		return true;
	}

	/** How bent the first-person arms' elbows are (radians; set by the first-person hand code before it draws them). */
	public static float fpElbow = 0.22F;

	/** A first-person arm worked out now (the model's parts are shared and change before the deferred draw): its joints. */
	public record ArmShape(Vec3 shoulder, Vec3 elbow, Vec3 hand, Vec3 front, float width, boolean right) {
		public void draw(PoseStack.Pose pose, VertexConsumer vc, int light, boolean sleeve) {
			BodyRig.drawArm(pose, vc, shoulder, elbow, hand, front, width, right, sleeve, light);
		}
	}

	/** The arm (and its forearm, bent by fpElbow) as the hand renderer has posed it; null if this model has no elbow. */
	public static ArmShape armShape(PlayerModel m, ModelPart arm, boolean right) {
		String name = right ? "right_forearm" : "left_forearm";
		if (!enabled || !arm.hasChild(name)) {
			return null;
		}
		ModelPart fore = arm.getChild(name);
		fore.resetPose();
		fore.xRot = -fpElbow;
		boolean slim = m instanceof SlimAccess a && a.tacticalMovement$slim();
		float c = right ? (slim ? -0.5F : -1.0F) : (slim ? 0.5F : 1.0F);
		PoseStack ps = new PoseStack();
		arm.translateAndRotate(ps);
		Matrix4f upper = new Matrix4f(ps.last().pose());
		fore.translateAndRotate(ps);
		Matrix4f lower = new Matrix4f(ps.last().pose());
		return new ArmShape(at(upper, c, -2, 0), at(lower, c, 0, 0), at(lower, c, 6, 0), dir(upper, 0, 0, -1), slim ? 3 : 4, right);
	}
}
