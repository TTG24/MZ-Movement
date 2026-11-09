package com.mzcod.tacticalmovement.client.animation;

import com.mzcod.tacticalmovement.client.debug.TacticalPoseDebug;
import com.mzcod.tacticalmovement.movement.MovementStance;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;

// Tactical arm/body poses. The limbs are the vanilla single-cube model parts, so a
// pose is expressed purely as part rotations (xRot/yRot/zRot) plus part offsets
// (y/z) rather than custom geometry.
//
// COORDINATE SYSTEM (critical for prone):
// Limbs are CHILDREN of the body. Their rotations are in the body's LOCAL frame.
// When body xRot = HALF_PI (flat, face down):
//   - Body local -Y (limb hang) = world BACKWARD
//   - Body local -Z            = world UP
//   - Body local +Y            = world FORWARD
// Therefore (for a flat body):
//   - Arm xRot = PI (180°)  → arm points FORWARD
//   - Arm xRot = HALF_PI    → arm points UP (the "V" bug!)
//   - Arm xRot = 0          → arm points BACKWARD
//   - Leg xRot = 0          → leg points BACKWARD (trailing)
//   - Leg xRot = -0.4       → leg bent (foot swings down, knee up)
//   - Head xRot = HALF_PI   → face UP (looking at sky)
//   - Head xRot = 0         → face DOWN (on ground)
public final class TacticalAnimations {
	private static final float HALF_PI = (float) Math.PI / 2F;

	// Part channel/part indices used by the snapshot helper.
	private static final int X = 0, XR = 1, YR = 2, ZR = 3, Y = 4, Z = 5;
	private static final int BODY = 0, HEAD = 1, RARM = 2, LARM = 3, RLEG = 4, LLEG = 5;

	// Lateral push applied to each leg in prone so the two cubes don't overlap.
	private static final float LEG_SEP = 1.1F;

	// Most recently requested stance (debug/legacy callers).
	private static MovementStance currentStance = MovementStance.STANDING;

	// Whether a low-profile pose was actually written this frame, and whether it was a
	// dive (deterministic, re-run on reapply) or a blended prone (cached verbatim).
	private static boolean poseApplied;
	private static boolean divePose;

	// Final 6-part pose (x,xRot,yRot,zRot,y,z) cached at setupAnim and re-written at
	// renderToBuffer. Caching the RESULT (not a relative blend) keeps the reapply
	// idempotent, so a layer re-run can't double-apply the transition blend.
	private static final float[] poseCache = new float[6 * 6];

	// When false, reapply() is a no-op. The live pose editor turns this off so the
	// cached production pose can't clobber the sculpted pose before render.
	public static boolean reapplyEnabled = true;

	public static MovementStance getCurrentStance() {
		return currentStance;
	}

	private TacticalAnimations() {
	}

	// blend: 0..1 upright->prone transition (eased on the client tick).
	// move:  0..1 horizontal crawl speed (drives the arm/leg stroke amplitude).
	public static void apply(
		PlayerModel model,
		MovementStance stance,
		boolean holdingSword,
		HumanoidArm mainArm,
		float swingAnimation,
		float ageInTicks,
		float blend,
		float move
	) {
		currentStance = stance;

		if (stance == MovementStance.DIVING) {
			applyDive(model);
			cachePose(model);
			poseApplied = true;
			divePose = true;
			return;
		}
		divePose = false;

		float b = Mth.clamp(blend, 0.0F, 1.0F);
		if (b < 0.001F) {
			// Fully upright: hand off cleanly to vanilla, only the standing sword
			// slash is added on top.
			poseApplied = false;
			if (stance == MovementStance.STANDING && holdingSword) {
				applySwordSwing(model, mainArm, swingAnimation);
			}
			return;
		}

		applyProne(model, b, Mth.clamp(move, 0.0F, 1.0F), ageInTicks, mainArm, stance.isLowProfile());
		// Apply the standing sword swing on top of the get-up transition so the arm
		// follows the slash as it rises from prone to standing.
		if (stance == MovementStance.STANDING && holdingSword && swingAnimation > 0.0F) {
			applySwordSwing(model, mainArm, swingAnimation);
		}
		cachePose(model);
		poseApplied = true;
	}

	// Re-assert the cached low-profile pose right before the model is drawn, undoing
	// any resetPose()/setupAnim clobber from the render layers. Idempotent by design.
	public static void reapply(PlayerModel model) {
		if (!reapplyEnabled) {
			return;
		}
		if (divePose) {
			applyDive(model);
			return;
		}
		if (poseApplied) {
			writePose(model);
		}
	}

	// Standing sword swing: a horizontal side-to-side sweep (zRot) with an optional forward
	// thrust (xRot) at mid-swing. The arm sweeps from OUTWARD (out to the side, swing=0) to
	// INWARD (across the body, swing=1), similar to the prone swing but in the standing frame.
	// swingAnimation runs 0->1->0, so the sweep is one-directional per swing.
	private static void applySwordSwing(PlayerModel model, HumanoidArm mainArm, float swingAnimation) {
		float swing = Mth.clamp(swingAnimation, 0.0F, 1.0F);
		var swordArm = model.getArm(mainArm);
		int side = mainArm == HumanoidArm.RIGHT ? 1 : -1;
		float amp = TacticalPoseDebug.standingSwordSwingAmp;
		// OUTWARD (swing=0) -> INWARD (swing=1): side * +amp -> side * -amp, a horizontal arc.
		// Faded by the swing envelope so the arm eases out of / back into its resting pose instead of
		// popping to +amp at the start and snapping back from -amp at the end.
		swordArm.zRot += side * amp * (1.0F - 2.0F * swing) * swordEnvelope(swing);
		// Mid-swing forward thrust: sin(PI*swing) peaks at swing=0.5, giving a cutting arc.
		float thrust = TacticalPoseDebug.standingSwordSwingThrust;
		if (thrust != 0.0F) {
			swordArm.xRot -= thrust * Mth.sin(swing * Mth.PI);
		}
		applySwordTwist(model, mainArm, swing);
	}

	// Debug slow-motion: when swordSwingSpeed < 1, the swing progress is driven by our own render
	// clock instead of vanilla's (which ends after ~6 ticks), so the swing can play out slower.
	// Starts when the real swing starts, runs at (speed / 0.3 s), and finishes even after the real
	// swing has ended. Speed >= 1 passes the real value through untouched. Debug-only; the state
	// is shared, so it assumes one swinging player (the local one).
	private static float virtSwing = -1.0F;
	private static long virtLastNanos = 0L;
	private static float virtLastReal = 0.0F;
	private static float virtLastOut = 0.0F;

	public static float slowSwing(float real) {
		float speed = TacticalPoseDebug.swordSwingSpeed;
		if (speed >= 0.999F || speed <= 0.0F) {
			virtSwing = -1.0F;
			virtLastReal = real;
			return real;
		}
		long now = System.nanoTime();
		// setupAnim can run several times per rendered frame; only advance the clock once per frame
		// and hand every extra call the same result so the swing can't double-step or restart.
		if (virtLastNanos != 0L && now - virtLastNanos < 2_000_000L) {
			return virtLastOut;
		}
		float dt = Mth.clamp((now - virtLastNanos) / 1.0E9F, 0.0F, 0.1F);
		virtLastNanos = now;
		virtLastOut = slowSwingStep(real, dt);
		return virtLastOut;
	}

	private static float slowSwingStep(float real, float dt) {
		// A new swing is a rising edge: real goes from 0 to >0, or restarts lower than last frame.
		boolean newSwing = real > 0.0F && (virtLastReal <= 0.0F || real < virtLastReal);
		virtLastReal = real;
		if (virtSwing < 0.0F) {
			if (!newSwing) {
				return 0.0F;
			}
			// Start from the beginning of the swing (not the real swing's current value) so the
			// arm eases out of its resting pose instead of popping into the mid-swing pose.
			virtSwing = 0.001F;
			return virtSwing;
		}
		virtSwing += dt * TacticalPoseDebug.swordSwingSpeed / 0.3F;
		if (virtSwing >= 1.0F) {
			virtSwing = -1.0F;
			return 0.0F;
		}
		return virtSwing;
	}

	// Swing envelope: 0 at both ends of the swing (arm at rest), eases up to 1 over the first
	// ~sixth, holds, and eases back to 0 over the last ~sixth so the arm returns smoothly.
	private static float swordEnvelope(float swing) {
		float t = Mth.clamp(Mth.sin(swing * Mth.PI) * 2.0F, 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	// Twist of the sword arm about its own long axis (yRot, since the arm hangs near vertical) so
	// the held blade lies flat during the swing. Ramps in quickly, holds, ramps out. The sword is
	// attached to the arm, so it rolls with it. Flip SWORD_TWIST's sign if the blade rolls the wrong way.
	public static void applySwordTwist(PlayerModel model, HumanoidArm mainArm, float swingAnimation) {
		float swing = Mth.clamp(swingAnimation, 0.0F, 1.0F);
		float env = swordEnvelope(swing);
		int side = mainArm == HumanoidArm.RIGHT ? 1 : -1;
		var arm = model.getArm(mainArm);
		arm.yRot += side * TacticalPoseDebug.standingSwordTwist * env;
		arm.xRot += TacticalPoseDebug.standingSwordArmX * env;
		arm.yRot += side * TacticalPoseDebug.standingSwordArmY * env;
		arm.zRot += side * TacticalPoseDebug.standingSwordArmZ * env;
	}

	// Prone entry/exit dispatcher. blend is 0=standing -> 1=prone. At full blend it runs
	// the live crawl/idle loop. Below full blend it plays a keyframed timeline:
	//   entering (stance is low-profile) -> dropToProne: crouch first, then flatten.
	//   exiting  (stance is standing)    -> getUp: push off, tuck leg, rise.
	// The torso never lerps straight through the ground, and the head tracks the camera.
	private static void applyProne(PlayerModel model, float blend, float move, float age, HumanoidArm mainArm, boolean entering) {
		float eased = smooth(Mth.clamp(blend, 0.0F, 1.0F)); // 0 = standing, 1 = flat prone
		float[][] s = snapshot(model);
		float headYaw = Mth.clamp(s[HEAD][YR], -1.3F, 1.3F);
		float headPitch = Mth.clamp(s[HEAD][XR], -1.2F, 1.2F);
		if (eased > 0.999F) {
			animatedProne(model, s, move, age, headYaw, headPitch);
		} else if (entering) {
			dropToProne(model, s, eased, headYaw, headPitch);
		} else {
			getUp(model, s, 1.0F - eased, mainArm, headYaw, headPitch); // getUp progress: 0=prone -> 1=stand
		}
	}

	// Live prone: tactical crawl pose.
	// Coordinate system (body flat at xRot=HALF_PI, limbs are children of body):
	//   - Body local -Y (limb hang direction) = world BACKWARD
	//   - Body local -Z = world UP
	//   - Body local +Y = world FORWARD
	// So: arm xRot=PI → forward, arm xRot=HALF_PI → UP, arm xRot=0 → backward
	//     leg xRot=0 → backward, leg xRot=-0.4 → bent (foot down), leg xRot=PI → forward
	//     head xRot=HALF_PI → face UP, head xRot=0 → face DOWN (on ground)
	private static void animatedProne(PlayerModel model, float[][] s, float move, float age, float headYaw, float headPitch) {
		float phase = age * (0.18F + move * 0.55F);
		float a = Mth.sin(phase) * move;              // +1 reach forward, -1 pull back
		float legA = Mth.sin(phase + HALF_PI) * move; // legs offset 90° from arms

		// Body: flat on ground, face down
		model.body.x = s[BODY][X];
		model.body.xRot = HALF_PI;
		model.body.yRot = 0.0F;
		model.body.zRot = 0.0F;
		model.body.y = 19.0F;
		model.body.z = 0.0F;

		// Head: face UP, follow camera on all axes
		model.head.x = s[HEAD][X];
		model.head.xRot = HALF_PI - headPitch;   // face up, tilt with player pitch
		model.head.yRot = headYaw;               // follow camera yaw
		model.head.zRot = 0.0F;
		model.head.y = 21.0F;
		model.head.z = 0.0F;

		// Arms: reaching FORWARD (xRot ≈ PI = forward in body's local frame)
		setArm(model.rightArm, s[RARM], a, 1);
		setArm(model.leftArm, s[LARM], -a, -1);

		// Legs: bent BACKWARD (xRot ≈ 0 = backward, negative = bent knee)
		setLeg(model.rightLeg, s[RLEG], legA, 1);
		setLeg(model.leftLeg, s[LLEG], -legA, -1);
	}

	// Prone arm: reaches forward, pulls back, returns (crawling stroke).
	// xRot = PI - offset → pointing forward (in body's local frame when body is flat).
	private static void setArm(ModelPart p, float[] s, float a, int side) {
		p.x = s[X];
		p.xRot = Mth.PI - 0.35F + 0.25F * a;   // ~160° forward, oscillates ±14°
		p.yRot = 0.0F;
		p.zRot = side * 0.10F;                   // slight outward splay
		p.y = 19.0F;                             // elbows at body level (on ground)
		p.z = 0.0F;                              // no lateral push
	}

	// Prone leg: bent backward, alternating push/tuck.
	// xRot ≈ 0 → backward (in body's local frame), negative → bent (foot down).
	private static void setLeg(ModelPart p, float[] s, float a, int side) {
		p.x = s[X] + side * (LEG_SEP + 0.6F);    // wider separation
		p.xRot = -0.15F + 0.20F * a;            // slightly bent, oscillates
		p.yRot = 0.0F;
		p.zRot = side * 0.20F;                   // slight splay
		p.y = 19.0F;                             // hips at body level
		p.z = 2.0F + 1.0F * a;                   // slight backward push, more when extending
	}

	// Keyframed get-up from flat (u=0) to the standing snapshot (u=1). Two stages so it
	// reads as "push up with arms, then kick a leg to stand" rather than a backwards flip:
	//   stage 1 (u 0.00 -> 0.55) push: arms brace against the ground, torso rises from
	//     flat to a crouch, one leg swings forward (knee up), the other stays planted back.
	//   stage 2 (u 0.45 -> 1.00) rise: the planted leg swings forward, the torso goes
	//     upright, arms swing to the sides.
	// Every channel lerps prone -> crouch -> standing, so the figure never inverts and
	// the hand-off to the standing pose at u=1 is seamless.
	private static void getUp(PlayerModel model, float[][] s, float u, HumanoidArm mainArm, float headYaw, float headPitch) {
		float push = smooth(clamp01(u / 0.55F));
		float rise = smooth(clamp01((u - 0.45F) / 0.55F));

		boolean rightTucks = mainArm == HumanoidArm.LEFT;
		int tuckSide = rightTucks ? 1 : -1;
		int plantSide = -tuckSide;
		ModelPart tuckLeg = rightTucks ? model.rightLeg : model.leftLeg;
		ModelPart plantLeg = rightTucks ? model.leftLeg : model.rightLeg;
		float[] tuckS = rightTucks ? s[RLEG] : s[LLEG];
		float[] plantS = rightTucks ? s[LLEG] : s[RLEG];

		// --- BODY: flat -> crouch (push) -> upright (rise) ---
		float bodyXr = Mth.lerp(push, HALF_PI, 0.55F);
		bodyXr = Mth.lerp(rise, bodyXr, s[BODY][XR]);
		float bodyY = Mth.lerp(push, 19.0F, 4.0F);
		bodyY = Mth.lerp(rise, bodyY, s[BODY][Y]);
		model.body.x = s[BODY][X];
		model.body.xRot = bodyXr;
		model.body.yRot = Mth.lerp(rise, 0.0F, s[BODY][YR]);
		model.body.zRot = Mth.lerp(rise, 0.0F, s[BODY][ZR]);
		model.body.y = bodyY;
		model.body.z = Mth.lerp(rise, 0.0F, s[BODY][Z]);

		// --- HEAD: starts facing up + camera follow, rises with body ---
		float headXr = Mth.lerp(push, HALF_PI, s[HEAD][XR] + 0.15F);
		headXr = Mth.lerp(rise, headXr, s[HEAD][XR]);
		float headY = Mth.lerp(push, 21.0F, 4.0F);
		headY = Mth.lerp(rise, headY, s[HEAD][Y]);
		model.head.x = s[HEAD][X];
		model.head.xRot = headXr;
		model.head.yRot = Mth.lerp(push, headYaw, headYaw);
		model.head.yRot = Mth.lerp(rise, model.head.yRot, s[HEAD][YR]);
		model.head.zRot = Mth.lerp(rise, 0.0F, s[HEAD][ZR]);
		model.head.y = headY;
		model.head.z = Mth.lerp(rise, 0.0F, s[HEAD][Z]);

		// --- ARMS: start reaching forward (xRot≈PI), brace during push, swing to sides during rise ---
		float rArmXr = Mth.lerp(push, Mth.PI - 0.35F, -0.5F);
		rArmXr = Mth.lerp(rise, rArmXr, s[RARM][XR]);
		float rArmY = Mth.lerp(push, 19.0F, 6.0F);
		rArmY = Mth.lerp(rise, rArmY, s[RARM][Y]);
		model.rightArm.x = s[RARM][X];
		model.rightArm.xRot = rArmXr;
		model.rightArm.yRot = Mth.lerp(rise, 0.0F, s[RARM][YR]);
		model.rightArm.zRot = Mth.lerp(rise, 0.10F, s[RARM][ZR]);
		model.rightArm.y = rArmY;
		model.rightArm.z = Mth.lerp(push, 0.0F, 0.0F);
		model.rightArm.z = Mth.lerp(rise, model.rightArm.z, s[RARM][Z]);

		float lArmXr = Mth.lerp(push, Mth.PI - 0.35F, -0.5F);
		lArmXr = Mth.lerp(rise, lArmXr, s[LARM][XR]);
		float lArmY = Mth.lerp(push, 19.0F, 6.0F);
		lArmY = Mth.lerp(rise, lArmY, s[LARM][Y]);
		model.leftArm.x = s[LARM][X];
		model.leftArm.xRot = lArmXr;
		model.leftArm.yRot = Mth.lerp(rise, 0.0F, s[LARM][YR]);
		model.leftArm.zRot = Mth.lerp(rise, -0.10F, s[LARM][ZR]);
		model.leftArm.y = lArmY;
		model.leftArm.z = Mth.lerp(push, 0.0F, 0.0F);
		model.leftArm.z = Mth.lerp(rise, model.leftArm.z, s[LARM][Z]);

		// --- TUCK LEG: starts bent back (xRot≈-0.15), swings forward during push (knee comes up), settles during rise ---
		float tuckXr = Mth.lerp(push, -0.15F, 0.0F);
		tuckXr = Mth.lerp(rise, tuckXr, tuckS[XR]);
		float tuckY = Mth.lerp(push, 19.0F, 12.0F);
		tuckY = Mth.lerp(rise, tuckY, tuckS[Y]);
		tuckLeg.x = Mth.lerp(push, tuckS[X] + tuckSide * (LEG_SEP + 0.6F), tuckS[X]);
		tuckLeg.xRot = tuckXr;
		tuckLeg.yRot = Mth.lerp(rise, 0.0F, tuckS[YR]);
		tuckLeg.zRot = Mth.lerp(rise, tuckSide * 0.20F, tuckS[ZR]);
		tuckLeg.y = tuckY;
		tuckLeg.z = Mth.lerp(push, 2.0F, 0.0F);
		tuckLeg.z = Mth.lerp(rise, tuckLeg.z, tuckS[Z]);

		// --- PLANT LEG: starts bent back (xRot≈-0.15), stays back during push, swings forward during rise ---
		float plantXr = -0.15F;
		plantXr = Mth.lerp(rise, plantXr, plantS[XR]);
		float plantY = 19.0F;
		plantY = Mth.lerp(rise, plantY, plantS[Y]);
		plantLeg.x = Mth.lerp(rise, plantS[X] + plantSide * (LEG_SEP + 0.6F), plantS[X]);
		plantLeg.xRot = plantXr;
		plantLeg.yRot = Mth.lerp(rise, 0.0F, plantS[YR]);
		plantLeg.zRot = Mth.lerp(rise, plantSide * 0.20F, plantS[ZR]);
		plantLeg.y = plantY;
		plantLeg.z = Mth.lerp(rise, 2.0F, plantS[Z]);
	}

	// Natural 3-phase drop-to-prone (v: 0=standing -> 1=flat):
	//   Phase 1 (v 0.00 -> 0.45) squat: knees bend deep, body sinks, arms reach down.
	//   Phase 2 (v 0.35 -> 0.75) lean: torso pitches forward over the knees, hands
	//     reach toward the ground, legs stay bent.
	//   Phase 3 (v 0.65 -> 1.00) flatten: body goes flat, legs extend and splay,
	//     arms plant forward.
	// Overlapping windows ensure smooth hand-off between phases.
	private static void dropToProne(PlayerModel model, float[][] s, float v, float headYaw, float headPitch) {
		float squat = smooth(clamp01(v / 0.45F));
		float lean = smooth(clamp01((v - 0.35F) / 0.40F));
		float flatP = smooth(clamp01((v - 0.65F) / 0.35F));

		// --- BODY: sink in squat, pitch forward in lean, flatten in flatP ---
		float bodyXr = Mth.lerp(squat, s[BODY][XR], 0.35F);
		bodyXr = Mth.lerp(lean, bodyXr, 1.10F);
		bodyXr = Mth.lerp(flatP, bodyXr, HALF_PI);
		float bodyY = Mth.lerp(squat, s[BODY][Y], 20.0F);
		bodyY = Mth.lerp(lean, bodyY, 16.0F);
		bodyY = Mth.lerp(flatP, bodyY, 22.0F);
		model.body.x = s[BODY][X];
		model.body.xRot = bodyXr;
		model.body.yRot = Mth.lerp(flatP, s[BODY][YR], 0.0F);
		model.body.zRot = Mth.lerp(flatP, s[BODY][ZR], 0.0F);
		model.body.y = bodyY;
		float bodyZ = Mth.lerp(squat, s[BODY][Z], s[BODY][Z] + 2.0F);
		bodyZ = Mth.lerp(lean, bodyZ, s[BODY][Z] + 5.0F);
		bodyZ = Mth.lerp(flatP, bodyZ, 0.0F);
		model.body.z = bodyZ;

		// --- HEAD: stays up in squat, follows torso in lean, ends facing up + camera follow ---
		float headXr = Mth.lerp(squat, s[HEAD][XR], 0.15F);
		headXr = Mth.lerp(lean, headXr, 0.85F);
		headXr = Mth.lerp(flatP, headXr, HALF_PI - headPitch);
		float headY = Mth.lerp(squat, s[HEAD][Y], 22.0F);
		headY = Mth.lerp(lean, headY, 18.0F);
		headY = Mth.lerp(flatP, headY, 21.0F);
		model.head.x = s[HEAD][X];
		model.head.xRot = headXr;
		model.head.yRot = Mth.lerp(flatP, s[HEAD][YR], headYaw);
		model.head.zRot = Mth.lerp(flatP, s[HEAD][ZR], 0.0F);
		model.head.y = headY;
		float headZ = Mth.lerp(squat, s[HEAD][Z], s[HEAD][Z] + 2.0F);
		headZ = Mth.lerp(lean, headZ, s[HEAD][Z] + 5.0F);
		headZ = Mth.lerp(flatP, headZ, 0.0F);
		model.head.z = headZ;

		// --- RIGHT ARM: reach down in squat, extend forward in lean, plant reaching forward in flatP ---
		float rArmXr = Mth.lerp(squat, s[RARM][XR], 0.70F);
		rArmXr = Mth.lerp(lean, rArmXr, 1.20F);
		rArmXr = Mth.lerp(flatP, rArmXr, Mth.PI - 0.35F);
		float rArmY = Mth.lerp(squat, s[RARM][Y], 20.0F);
		rArmY = Mth.lerp(lean, rArmY, 16.0F);
		rArmY = Mth.lerp(flatP, rArmY, 19.0F);
		model.rightArm.x = s[RARM][X];
		model.rightArm.xRot = rArmXr;
		model.rightArm.yRot = Mth.lerp(flatP, s[RARM][YR], 0.0F);
		model.rightArm.zRot = Mth.lerp(flatP, s[RARM][ZR], 0.10F);
		model.rightArm.y = rArmY;
		float rArmZ = Mth.lerp(squat, s[RARM][Z], s[RARM][Z] + 2.0F);
		rArmZ = Mth.lerp(lean, rArmZ, s[RARM][Z] + 5.0F);
		rArmZ = Mth.lerp(flatP, rArmZ, 0.0F);
		model.rightArm.z = rArmZ;

		// --- LEFT ARM: mirror of right ---
		float lArmXr = Mth.lerp(squat, s[LARM][XR], 0.70F);
		lArmXr = Mth.lerp(lean, lArmXr, 1.20F);
		lArmXr = Mth.lerp(flatP, lArmXr, Mth.PI - 0.35F);
		float lArmY = Mth.lerp(squat, s[LARM][Y], 20.0F);
		lArmY = Mth.lerp(lean, lArmY, 16.0F);
		lArmY = Mth.lerp(flatP, lArmY, 19.0F);
		model.leftArm.x = s[LARM][X];
		model.leftArm.xRot = lArmXr;
		model.leftArm.yRot = Mth.lerp(flatP, s[LARM][YR], 0.0F);
		model.leftArm.zRot = Mth.lerp(flatP, s[LARM][ZR], -0.10F);
		model.leftArm.y = lArmY;
		float lArmZ = Mth.lerp(squat, s[LARM][Z], s[LARM][Z] + 2.0F);
		lArmZ = Mth.lerp(lean, lArmZ, s[LARM][Z] + 5.0F);
		lArmZ = Mth.lerp(flatP, lArmZ, 0.0F);
		model.leftArm.z = lArmZ;

		// --- RIGHT LEG: track body Y to stay attached, extend, flatten bent back+splay ---
		float rLegXr = Mth.lerp(squat, s[RLEG][XR], 0.15F);
		rLegXr = Mth.lerp(lean, rLegXr, 0.80F);
		rLegXr = Mth.lerp(flatP, rLegXr, -0.15F);
		float rLegY = Mth.lerp(squat, s[RLEG][Y], 20.0F);
		rLegY = Mth.lerp(lean, rLegY, 16.0F);
		rLegY = Mth.lerp(flatP, rLegY, 19.0F);
		float rLegZ = Mth.lerp(squat, s[RLEG][Z], s[RLEG][Z] + 3.0F);
		rLegZ = Mth.lerp(lean, rLegZ, s[RLEG][Z] + 8.0F);
		rLegZ = Mth.lerp(flatP, rLegZ, 2.0F);
		model.rightLeg.x = Mth.lerp(flatP, s[RLEG][X], s[RLEG][X] + LEG_SEP + 0.6F);
		model.rightLeg.xRot = rLegXr;
		model.rightLeg.yRot = Mth.lerp(flatP, s[RLEG][YR], 0.0F);
		model.rightLeg.zRot = Mth.lerp(flatP, s[RLEG][ZR], 0.20F);
		model.rightLeg.y = rLegY;
		model.rightLeg.z = rLegZ;

		// --- LEFT LEG: mirror of right ---
		float lLegXr = Mth.lerp(squat, s[LLEG][XR], 0.15F);
		lLegXr = Mth.lerp(lean, lLegXr, 0.80F);
		lLegXr = Mth.lerp(flatP, lLegXr, -0.15F);
		float lLegY = Mth.lerp(squat, s[LLEG][Y], 20.0F);
		lLegY = Mth.lerp(lean, lLegY, 16.0F);
		lLegY = Mth.lerp(flatP, lLegY, 19.0F);
		float lLegZ = Mth.lerp(squat, s[LLEG][Z], s[LLEG][Z] + 3.0F);
		lLegZ = Mth.lerp(lean, lLegZ, s[LLEG][Z] + 8.0F);
		lLegZ = Mth.lerp(flatP, lLegZ, 2.0F);
		model.leftLeg.x = Mth.lerp(flatP, s[LLEG][X], s[LLEG][X] - LEG_SEP - 0.6F);
		model.leftLeg.xRot = lLegXr;
		model.leftLeg.yRot = Mth.lerp(flatP, s[LLEG][YR], 0.0F);
		model.leftLeg.zRot = Mth.lerp(flatP, s[LLEG][ZR], -0.20F);
		model.leftLeg.y = lLegY;
		model.leftLeg.z = lLegZ;
	}



	// Snapshot each humanoid part's channels (x,xRot,yRot,zRot,y,z) before overwriting.
	private static final float[][] SNAPSHOT = new float[6][6]; // reused every frame (render thread only)

	private static float[][] snapshot(PlayerModel m) {
		float[][] s = SNAPSHOT;
		grab(m.body, s[BODY]);
		grab(m.head, s[HEAD]);
		grab(m.rightArm, s[RARM]);
		grab(m.leftArm, s[LARM]);
		grab(m.rightLeg, s[RLEG]);
		grab(m.leftLeg, s[LLEG]);
		return s;
	}

	private static void grab(ModelPart p, float[] o) {
		o[X] = p.x;
		o[XR] = p.xRot;
		o[YR] = p.yRot;
		o[ZR] = p.zRot;
		o[Y] = p.y;
		o[Z] = p.z;
	}

	// smoothstep on an already 0..1 value.
	private static float smooth(float x) {
		return x * x * (3.0F - 2.0F * x);
	}

	// clamp to 0..1.
	private static float clamp01(float x) {
		return x < 0.0F ? 0.0F : (x > 1.0F ? 1.0F : x);
	}



	// Dolphin dive: the same flat layout as prone but lifted into the air, nose pitched
	// down and legs kicked up behind, reading as a short leap. Deterministic (no blend).
	private static void applyDive(PlayerModel model) {
		float flat = HALF_PI;
		float bodyY = 15.0F;

		resetRotations(model);

		model.body.xRot = flat - 0.2F;
		model.body.y = bodyY;
		model.body.z = 0.0F;

		model.head.xRot = flat - 0.4F;
		model.head.y = bodyY + 2.0F;
		model.head.z = 0.0F;

		model.rightArm.xRot = flat + 0.5F;
		model.rightArm.yRot = 0.3F;
		model.rightArm.y = bodyY - 2.0F;
		model.rightArm.z = 0.0F;

		model.leftArm.xRot = flat + 0.5F;
		model.leftArm.yRot = -0.3F;
		model.leftArm.y = bodyY - 2.0F;
		model.leftArm.z = 0.0F;

		model.rightLeg.xRot = flat + 0.3F;
		model.rightLeg.y = bodyY;
		model.rightLeg.z = 12.0F;

		model.leftLeg.xRot = flat + 0.3F;
		model.leftLeg.y = bodyY;
		model.leftLeg.z = 12.0F;
	}

	private static void cachePose(PlayerModel m) {
		int o = 0;
		o = storePart(m.body, o);
		o = storePart(m.head, o);
		o = storePart(m.rightArm, o);
		o = storePart(m.leftArm, o);
		o = storePart(m.rightLeg, o);
		storePart(m.leftLeg, o);
	}

	private static void writePose(PlayerModel m) {
		int o = 0;
		o = loadPart(m.body, o);
		o = loadPart(m.head, o);
		o = loadPart(m.rightArm, o);
		o = loadPart(m.leftArm, o);
		o = loadPart(m.rightLeg, o);
		loadPart(m.leftLeg, o);
	}

	private static int storePart(ModelPart p, int o) {
		poseCache[o] = p.x;
		poseCache[o + 1] = p.xRot;
		poseCache[o + 2] = p.yRot;
		poseCache[o + 3] = p.zRot;
		poseCache[o + 4] = p.y;
		poseCache[o + 5] = p.z;
		return o + 6;
	}

	private static int loadPart(ModelPart p, int o) {
		p.x = poseCache[o];
		p.xRot = poseCache[o + 1];
		p.yRot = poseCache[o + 2];
		p.zRot = poseCache[o + 3];
		p.y = poseCache[o + 4];
		p.z = poseCache[o + 5];
		return o + 6;
	}

	// Zero every humanoid part's rotation before a low-profile pose writes its own,
	// so leftover swim/walk animation (notably large arm zRot) can't bleed through.
	private static void resetRotations(PlayerModel model) {
		zeroRotations(model.body);
		zeroRotations(model.head);
		zeroRotations(model.hat);
		zeroRotations(model.rightArm);
		zeroRotations(model.leftArm);
		zeroRotations(model.rightLeg);
		zeroRotations(model.leftLeg);
	}

	private static void zeroRotations(ModelPart part) {
		part.xRot = 0.0F;
		part.yRot = 0.0F;
		part.zRot = 0.0F;
	}
}
