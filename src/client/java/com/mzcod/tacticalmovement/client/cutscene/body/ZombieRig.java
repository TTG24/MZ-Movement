package com.mzcod.tacticalmovement.client.cutscene.body;

import com.mzcod.tacticalmovement.client.animation.ZombieAnim;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Ordinary zombies in the world on the bending body, with the same Call of Duty-style body language as {@link ZombieAnim}
 * (idle hunched and swaying, a lurching shamble with a dragged leg, a bent-over sprint with pumping arms, a rearing claw
 * attack), now with knees and elbows: knees lift through each step, the arms bend as they pump and claw.
 *
 * <p>Worked out from the render state each frame (nothing kept between frames), from vanilla's walk data.
 */
public final class ZombieRig {
	private ZombieRig() {
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	public static BodyRig.Pose pose(HumanoidRenderState s) {
		BodyRig.Pose normal = walkPose(s);
		// Digging up out of the ground right after spawning
		Float rise = s.getData(com.mzcod.tacticalmovement.client.animation.ZombieRise.TIME);
		return rise == null ? normal : com.mzcod.tacticalmovement.client.animation.ZombieRise.rigPose(s, rise, normal);
	}

	private static BodyRig.Pose walkPose(HumanoidRenderState s) {
		float t = s.ageInTicks;
		float pos = s.walkAnimationPos;
		float spd = s.walkAnimationSpeed;
		float move = smooth(spd / 0.15F);
		float sprint = smooth((spd - ZombieAnim.sprintFrom) / Math.max(0.01F, ZombieAnim.sprintTo - ZombieAnim.sprintFrom));
		float walkW = move * (1.0F - sprint);
		float c = Mth.cos(pos * 0.6662F), sn = Mth.sin(pos * 0.6662F);
		float idle = 1.0F - move;

		// Body: hunched over (more as it goes faster), rocking side to side, shoulders swinging
		float lean = Mth.lerp(sprint, Mth.lerp(walkW, 0.22F, 0.32F), 0.62F);
		float roll = Mth.sin(t * 0.06F) * 0.05F * idle + sn * 0.13F * walkW + sn * 0.06F * sprint;
		float twist = Mth.sin(pos * 0.3331F) * 0.10F * walkW + c * 0.12F * sprint;

		// Arms (model angles: 0 hanging, -pi/2 out ahead, -pi straight up): dangling idle, reaching walking, pumping sprinting
		float rArm = Mth.lerp(sprint, Mth.lerp(move, -0.30F + Mth.sin(t * 0.07F) * 0.06F, -1.40F + c * 0.28F * spd), -0.35F - c * 1.55F);
		float lArm = Mth.lerp(sprint, Mth.lerp(move, -0.18F + Mth.sin(t * 0.07F + 1.7F) * 0.06F, -1.10F - c * 0.34F * spd), -0.35F + c * 1.55F);
		float rOut = Mth.lerp(sprint, Mth.lerp(move, 0.10F, 0.05F), 0.30F), lOut = Mth.lerp(sprint, Mth.lerp(move, 0.14F, 0.05F), 0.30F);
		float rIn = 0.15F * walkW, lIn = 0.18F * walkW;
		float bendArms = 0.1F + 0.25F * sprint; // elbows bend as it pumps

		// Head: hanging and tilted idle, up at you when it is bent right over
		float twitch = Mth.sin(t * 0.9F) * smooth((Mth.sin(t * 0.045F) - 0.92F) * 12.0F) * 0.25F;
		float headDrop = Mth.lerp(sprint, Mth.lerp(move, 0.38F, 0.18F), -0.30F);

		// The claw attack: arms rear up, then rake down and across, the body lunging in
		float swing = s.swingAnimation;
		if (swing > 0.0F) {
			float w = Mth.sin(swing * Mth.PI);
			float claw = swing < 0.35F ? Mth.lerp(swing / 0.35F, -1.4F, -2.8F) : Mth.lerp(smooth((swing - 0.35F) / 0.65F), -2.8F, -0.45F);
			float k = Math.min(1.0F, w * 2.0F);
			rArm = Mth.lerp(k, rArm, claw);
			lArm = Mth.lerp(k, lArm, claw + 0.25F);
			rIn = Mth.lerp(w, rIn, -0.35F);
			lIn = Mth.lerp(w, lIn, -0.35F);
			lean += 0.28F * w;
			headDrop -= 0.25F * w;
			bendArms += 0.2F * w;
		}

		BodyRig.Pose p = new BodyRig.Pose();
		Vec3 feet = new Vec3(s.x, s.y, s.z);
		double yr = Math.toRadians(s.bodyRot);
		Vec3 up = new Vec3(0, 1, 0), fwd = new Vec3(-Math.sin(yr), 0, Math.cos(yr));
		Vec3 right = fwd.cross(up).normalize();
		// Bob with the steps, a sway idle
		double bob = -0.03 * walkW * Math.abs(sn) + 0.05 * sprint * (Math.abs(c) - 0.5) + Mth.sin(t * 0.08F) * 0.008 * idle;
		p.pelvis = feet.add(0, 0.75 + bob, 0).add(right.scale(sn * 0.04 * walkW));
		p.up = BodyRig.rot(BodyRig.rot(up, right, -lean * 0.75), fwd, roll);
		p.front = BodyRig.rot(fwd, right, -lean * 0.75);
		p.bend = lean * 0.25F;
		p.twist = twist;
		p.headYaw = (float) Math.toRadians(s.yRot);
		p.headPitch = (float) Math.toRadians(s.xRot) + headDrop - lean * 0.5F;
		p.hideHead = false;

		// Legs: a limp (the left leg drags, lifting little), long knee-driving strides sprinting
		float legSwing = 1.4F * spd;
		for (int f = 0; f < 2; f++) {
			boolean r = f == 0;
			float x = (r ? c : -c) * (r ? Mth.lerp(sprint, 1.0F, 1.35F) : Mth.lerp(sprint, 0.62F, 1.35F)) * legSwing;
			x = Mth.clamp(x, -1.1F, 1.1F);
			Vec3 hip = feet.add(0, 0.75 + bob, 0).add(right.scale(r ? 0.125 : -0.125));
			Vec3 foot = hip.add(fwd.scale(-Math.sin(x) * 0.74)).subtract(0, Math.cos(x) * 0.74, 0);
			// The leg swinging forward lifts its knee (more sprinting); the dragged one barely
			double forward = Math.max(0, -Math.sin(x));
			double lift = forward * (r ? 0.28 : 0.12) * walkW + forward * 0.45 * sprint;
			foot = foot.add(0, lift, 0).subtract(fwd.scale(lift * 0.5));
			if (r) {
				p.footR = foot;
				p.kneeR = fwd;
			} else {
				p.footL = foot;
				p.kneeL = fwd;
			}
		}

		// Arms from the shoulders of the posed chest
		BodyRig.Joints body = BodyRig.solve(p, false);
		Vec3 dr = body.cU.scale(-Math.cos(rArm)).subtract(body.cF.scale(Math.sin(rArm))).add(body.cR.scale(rOut * 0.8)).normalize();
		Vec3 dl = body.cU.scale(-Math.cos(lArm)).subtract(body.cF.scale(Math.sin(lArm))).subtract(body.cR.scale(lOut * 0.8)).normalize();
		dr = BodyRig.rot(dr, body.cU, rIn);
		dl = BodyRig.rot(dl, body.cU, -lIn);
		double reach = 0.74 - bendArms * 0.3;
		p.handR = body.shoulderR.add(dr.scale(reach));
		p.handL = body.shoulderL.add(dl.scale(reach));
		p.elbowR = body.cU.scale(-1).add(body.cR.scale(0.4)).subtract(body.cF.scale(0.4));
		p.elbowL = body.cU.scale(-1).subtract(body.cR.scale(0.4)).subtract(body.cF.scale(0.4));
		return p;
	}
}
