package com.mzcod.tacticalmovement.client.cutscene;

import com.mzcod.tacticalmovement.TacticalMovement;
import com.mzcod.tacticalmovement.client.BloodFx;
import com.mzcod.tacticalmovement.client.cutscene.body.BodyRig;
import com.mzcod.tacticalmovement.client.cutscene.body.RigBody;
import com.mzcod.tacticalmovement.client.wounds.Corpses;
import com.mzcod.tacticalmovement.network.GameOverPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * The game-over cutscene, in first person: when everyone is down the server sends where this player went down, and one
 * of three scripted scenes plays there. A copy of the player (their skin) lies there and the camera is in its head (the
 * head and the first-person hand are not drawn), so the player sees their own body; the zombies and the body are
 * client-only. Zombies kneel for real (legs folded back along the ground, bending at the hips; applyPose and
 * LivingEntityRendererCorpseMixin).
 *  * PINNED: knocked onto your back, they eat you limb by limb: one grabs your right arm and bites into it, a second your
 *    left, a third your legs, one after the other, and the last comes down over your face.
 *  * OVERWHELMED: on your knees you look around: zombies everywhere, closing in. A sprinter screams and runs at you,
 *    knocks you down and bites again and again, the rest closing over you.
 *  * DRAGGED: a zombie walks up and hits you: knocked out, black. You come to on your back, dragged by the ankles, a blood
 *    trail behind you, blacking in and out; it drops you and kneels over you to feed.
 *  * DISMEMBERED: it tears off your right arm, then your left (each held up, then thrown down), then slowly pulls your
 *    head off: the camera goes with the head, looking down at your body; a white flash and grey flashback cuts, then black.
 * Then black and GAME OVER with the rounds survived. The server stops it (a negative round) when the player leaves the
 * arena; changing world stops it too. /got (or /gameovertest) plays one anywhere for testing.
 */
public final class GameOverCutscene {
	private enum Kind {
		PINNED(270), OVERWHELMED(225), DRAGGED(285), DISMEMBERED(286), BEATEN(250), AMBUSHED(180), LIFTED(245), SLAMMED(235), CHOKED(255), CRAWLING(380), TACKLED(250), MISSED(240);

		final int length;

		Kind(int length) {
			this.length = length;
		}
	}

	/** How far a zombie bends forward (degrees), and how far it is kneeling (0..1), for the renderer. */
	public static final RenderStateDataKey<Float> LEAN = RenderStateDataKey.create(() -> "tacticalmovement:cutscene_lean");
	public static final RenderStateDataKey<Float> KNEEL = RenderStateDataKey.create(() -> "tacticalmovement:cutscene_kneel");
	/** A cutscene zombie's arms when the scene moves them itself ({right xRot, left xRot}; empty = its own animation). */
	public static final RenderStateDataKey<float[]> ARMS = RenderStateDataKey.create(() -> "tacticalmovement:cutscene_arms");
	private static final float[] NO_ARMS = new float[0];
	/** Kneeling: how far the model goes down, and where its hips are (it bends there). */
	public static final float KNEEL_DROP = 0.72F, HIP = 0.75F;

	private static final int SCREEN = 120;
	private static final double ZOMBIE_EYES = 1.74;
	private static final Random RANDOM = new Random();
	private static final SoundEvent ATTACK = mz("zombie_attack");
	private static final SoundEvent HIT_PLAYER = mz("zombie_hit_player");
	private static final SoundEvent SPRINT = mz("zombie_sprint");
	/** The player's own grunts of pain (the zombie-sounds pack). */
	private static final SoundEvent PLAYER_PAIN = mz("hurt");

	// The cutscene's own sounds (assets/tacticalmovement/sounds/cutscene)
	private static final SoundEvent BITE = cs("bite"), FLESH_TEAR = cs("flesh_tear"), BONE_SNAP = cs("bone_snap"),
		BLOOD_GUSH = cs("blood_gush"), SPLAT = cs("splat"), PUNCH = cs("punch"), BODY_FALL = cs("body_fall"), DRAG = cs("drag"),
		HEARTBEAT = cs("heartbeat"), TINNITUS = cs("tinnitus"), STRAIN = cs("strain"), FLASHBACK = cs("flashback"),
		DREAD = cs("dread"), GAME_OVER = cs("game_over"), FACE_PUNCH = cs("face_punch"), TV_STATIC = cs("tv_static");
	/** Sounds heard "in your head" (not from a place), stopped if the cutscene is cut short. */
	private static final List<net.minecraft.client.resources.sounds.SoundInstance> HEAD_SOUNDS = new ArrayList<>();
	private static int nextBeat;

	private static SoundEvent mz(String name) {
		return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("mz", name));
	}

	private static SoundEvent cs(String name) {
		return SoundEvent.createVariableRangeEvent(TacticalMovement.id("cutscene." + name));
	}

	private static void fx(Vec3 at, SoundEvent sound, float volume, float pitch) {
		level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.HOSTILE, volume, pitch * (0.92F + RANDOM.nextFloat() * 0.16F), false);
	}

	private static void inHead(SoundEvent sound, float volume, float pitch) {
		var instance = net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(sound, pitch, volume);
		HEAD_SOUNDS.add(instance);
		Minecraft.getInstance().getSoundManager().play(instance);
	}

	/** A cutscene zombie. The scene script sets where it goes, what it faces, whether it kneels and how far it bends. */
	private static final class Walker implements BodyRig.Animator {
		final Zombie zombie;
		/** Which way it faces (degrees, now and last tick), how far it has walked (for its stride), how fast it is going. */
		float yaw, yawOld;
		double walked, walkedOld, pace, phase, phaseOld;
		/** How much it is walking / sprinting (0..1, eased; now and last tick), which way it is moving. */
		float walkW, walkWOld, sprintW, sprintWOld;
		Vec3 moving = new Vec3(0, 0, 1);
		/** How far its arms are doing what the scene wants (vs its own swing), now and last tick; set this tick? */
		float armUse, armUseOld;
		boolean armCalled;
		double x, y, z, lastX, lastZ;
		Vec3 goal;
		Vec3 faceAt;
		double speed;
		int startTick;
		boolean arrived;
		float kneel, kneelOld, kneelTarget, kneelRate = 0.22F;
		/** Arms turned in toward each other (radians; 0 = straight ahead), for both hands on one throat. */
		float armIn, armInOld;
		float lean, leanOld, leanTarget, leanRate = 0.2F;
		float headJitter, headPitch;
		/** Walking: its speed now (it speeds up and slows down), where its head is turned from its body (degrees). */
		double cur;
		float headTurn, headTurnOld;
		/** Set by a scene that moves it while it keeps facing something (backing off, dragging): no turning to walk. */
		boolean faceWhileMoving;
		int nextGroan;
		/** Arms moved by the scene (NaN = its own animation): now, last tick, where they are going and how fast. */
		float armR = Float.NaN, armL = Float.NaN, armROld, armLOld, armRTarget, armLTarget, armRRate = 0.3F, armLRate = 0.3F;
		/** Hands reaching for exact places (world; the elbows bend to get there), and how far that has taken over. */
		Vec3 reachR, reachL, reachROld, reachLOld;
		float reach, reachOld;

		void arms(float right, float rightRate, float left, float leftRate) {
			armCalled = true;
			if (Float.isNaN(armR)) {
				// Starting from where its own swing has them (it eases over to what the scene wants)
				armR = armROld = ownArm(true, age);
				armL = armLOld = ownArm(false, age);
			}
			armRTarget = right;
			armRRate = rightRate;
			armLTarget = left;
			armLRate = leftRate;
		}

		Walker(Zombie zombie, double x, double y, double z) {
			this.zombie = zombie;
			this.x = this.lastX = x;
			this.y = y;
			this.z = this.lastZ = z;
			this.nextGroan = 5 + RANDOM.nextInt(30);
		}

		/** The point it bends around (the feet standing, the hips kneeling), how far its face is from it, and its front. */
		private Vec3 pivot(float partial) {
			float k = Mth.lerp(partial, kneelOld, kneel);
			double px = Mth.lerp(partial, zombie.xo, zombie.getX()), py = Mth.lerp(partial, zombie.yo, zombie.getY()),
				pz = Mth.lerp(partial, zombie.zo, zombie.getZ());
			return new Vec3(px, py + (HIP - KNEEL_DROP) * k, pz);
		}

		/** Where its eyes are (from its skeleton: exactly where its head is drawn). */
		Vec3 face(float partial) {
			return BodyRig.solve(pose(partial), false).eyes;
		}

		/** Between its hands. */
		Vec3 hands() {
			BodyRig.Joints j = BodyRig.solve(pose(1.0F), false);
			return j.handR.lerp(j.handL, 0.5);
		}

		/** Where it holds something up to its mouth (just under its face, a little in front). */
		Vec3 mouth() {
			BodyRig.Joints j = BodyRig.solve(pose(1.0F), false);
			return j.eyes.subtract(j.hU.scale(0.15)).add(j.hF.scale(0.12));
		}

		/**
		 * Its body this frame: standing (legs striding as it walks) or up on its knees (shins along the ground behind it),
		 * bent forward at the hips by its lean, head pitched and twitching, arms where the scene puts them (or held out
		 * in front, swaying, like a zombie).
		 */
		/** Its arms by themselves (model angle): hanging forward idle, held out shambling, flailing as it sprints. */
		float ownArm(boolean right, float t) {
			double ph = phase + (right ? 0 : Math.PI);
			float idle = -1.15F + (float) Math.sin(t * 0.07 + zombie.getId() + (right ? 0 : 1.7)) * 0.08F;
			float walk = -1.4F + (float) Math.sin(ph) * 0.14F;
			float sprint = -1.75F + (float) Math.sin(ph) * 0.55F;
			float going = Math.max(walkW, sprintW);
			return Mth.lerp(sprintW, Mth.lerp(going, idle, walk), sprint);
		}

		/**
		 * Its body this frame. On its feet it idles (breathing, swaying, weight shifting, head lolling), shambles (a lurching,
		 * dragging step, the body rocking side to side, shoulders swinging against the hips) or sprints (bent right over, knees
		 * driving up, a bounding stride, arms flailing), blended by how fast it is going; or it is up on its knees, shins
		 * along the ground behind it. It bends at the hips by its lean; its arms do what the scene wants, eased in and out.
		 */
		@Override
		public BodyRig.Pose pose(float pt) {
			BodyRig.Pose p = new BodyRig.Pose();
			Vec3 feet = new Vec3(Mth.lerp(pt, zombie.xo, zombie.getX()), Mth.lerp(pt, zombie.yo, zombie.getY()), Mth.lerp(pt, zombie.zo, zombie.getZ()));
			double yr = Math.toRadians(Mth.rotLerp(pt, yawOld, yaw));
			Vec3 up = new Vec3(0, 1, 0), fwd = new Vec3(-Math.sin(yr), 0, Math.cos(yr));
			Vec3 right = fwd.cross(up).normalize();
			float k = Mth.clamp(Mth.lerp(pt, kneelOld, kneel), 0, 1);
			float t = age + pt;
			float ww = Mth.lerp(pt, walkWOld, walkW) * (1 - k), sw = Mth.lerp(pt, sprintWOld, sprintW) * (1 - k);
			float idle = (1 - Math.max(ww, sw)) * (1 - k);
			double ph = Mth.lerp(pt, phaseOld, phase);
			int id = zombie.getId();
			// Lean: the scene's, plus bent over when it runs, plus a sway
			double l = Math.toRadians(Mth.lerp(pt, leanOld, lean)) + 0.12 * ww + 0.42 * sw + Math.sin(t * 0.05 + id) * 0.04 * idle;
			// Hips: up on its legs (bobbing with each step, rocking to the side as it lurches, swaying idle) or down on its knees
			double bob = -0.035 * ww * Math.abs(Math.sin(ph)) + 0.07 * sw * (Math.abs(Math.cos(ph)) - 0.5) + Math.sin(t * 0.08 + id) * 0.008 * idle;
			Vec3 rock = right.scale(Math.sin(ph) * 0.05 * ww + Math.sin(t * 0.04 + id) * 0.03 * idle);
			p.pelvis = feet.add(0, Mth.lerp(k, 0.75, 0.44) + bob, 0).add(rock);
			p.up = BodyRig.rot(up, right, -l * 0.7);
			p.front = BodyRig.rot(fwd, right, -l * 0.7);
			p.up = BodyRig.rot(p.up, fwd, Math.sin(ph) * 0.07 * ww); // a lurch to the side with each step
			p.bend = (float) (l * 0.3);
			p.twist = (float) (Math.sin(ph) * 0.15 * (ww + sw));
			p.headPitch = (float) Math.toRadians(headPitch) + (float) Math.sin(t * 0.21 + id) * 0.04F + 0.18F * idle - 0.25F * sw;
			p.headYaw = (float) Math.toRadians(headJitter + Mth.lerp(pt, headTurnOld, headTurn)) + (float) Math.sin(t * 0.13 + id) * (0.08F + 0.15F * idle);
			// Legs: stepping along the way it is going (forward, or backward when it backs off), one foot dragging (a bad
			// leg); bounding knees-up strides sprinting; kneeling with the shins back along the ground
			Vec3 mv = moving;
			for (int f = 0; f < 2; f++) {
				double side = f == 0 ? 1 : -1, legPh = ph + (f == 0 ? 0 : Math.PI);
				boolean bad = (id & 1) == f;
				double stride = 0.27 * ww + 0.48 * sw, lift = (bad ? 0.06 : 0.13) * ww + 0.32 * sw;
				Vec3 stand = feet.add(right.scale(side * 0.13)).add(mv.scale(Math.sin(legPh) * stride))
					.add(0, Math.max(0, Math.cos(legPh)) * lift, 0);
				Vec3 kneelFoot = feet.add(right.scale(side * 0.15)).subtract(fwd.scale(0.48)).add(0, 0.06, 0);
				Vec3 foot = stand.lerp(kneelFoot, k);
				Vec3 pole = fwd.add(0, -0.3 * k, 0);
				if (f == 0) {
					p.footR = foot;
					p.kneeR = pole;
				} else {
					p.footL = foot;
					p.kneeL = pole;
				}
			}
			// Arms: its own swing, eased into whatever the scene wants (model angles: 0 hanging, -pi/2 out ahead, -pi up)
			BodyRig.Joints body = BodyRig.solve(p, false);
			float use = Mth.lerp(pt, armUseOld, armUse);
			float ar = Mth.lerp(use, ownArm(true, t), Float.isNaN(armR) ? ownArm(true, t) : Mth.lerp(pt, armROld, armR));
			float al = Mth.lerp(use, ownArm(false, t), Float.isNaN(armL) ? ownArm(false, t) : Mth.lerp(pt, armLOld, armL));
			float in = Mth.lerp(pt, armInOld, armIn) * use;
			Vec3 dr = body.cU.scale(-Math.cos(ar)).subtract(body.cF.scale(Math.sin(ar)));
			Vec3 dl = body.cU.scale(-Math.cos(al)).subtract(body.cF.scale(Math.sin(al)));
			dr = BodyRig.rot(dr, body.cU, in);
			dl = BodyRig.rot(dl, body.cU, -in);
			p.handR = body.shoulderR.add(dr.scale(0.7));
			p.handL = body.shoulderL.add(dl.scale(0.7));
			float rc = Mth.lerp(pt, reachOld, reach);
			if (rc > 0 && reachR != null && reachL != null) {
				p.handR = p.handR.lerp(reachROld.lerp(reachR, pt), rc);
				p.handL = p.handL.lerp(reachLOld.lerp(reachL, pt), rc);
			}
			p.elbowR = body.cU.scale(-1).add(body.cR.scale(0.4)).subtract(body.cF.scale(0.3));
			p.elbowL = body.cU.scale(-1).subtract(body.cR.scale(0.4)).subtract(body.cF.scale(0.3));
			return p;
		}
	}

	private static boolean active;
	private static Kind kind;
	/** The scene /got asked for (null = random). */
	private static Kind forced;
	private static int age;
	private static int rounds;
	private static ClientLevel level;
	/** Where the player went down (on the ground); the way the zombies come from (the body's feet point that way). */
	private static double sx, sy, sz, awayX, awayZ;
	private static int bodyId = Integer.MIN_VALUE;
	/** Your body on the ground (on the bending rig); bodyId is its entity. */
	private static RigBody body;
	private static final List<Walker> walkers = new ArrayList<>();
	private static final Map<Integer, Walker> byId = new HashMap<>();
	/** The main zombie (the rusher, the one that drags) and the ones eating each limb (pinned). */
	private static Walker main, armR, armL, legs, last;
	private static int nextId = -3_000_000;

	// DISMEMBERED: which of the body's parts are gone (bits), the torn-off arms, the camera in the torn-off head
	private static final int GONE_R_ARM = 1, GONE_L_ARM = 2;
	private static int gone;
	private static final class Prop {
		final CutsceneClone entity;
		final boolean right;
		float armX;

		Prop(CutsceneClone entity, boolean right) {
			this.entity = entity;
			this.right = right;
		}
	}
	private static final Map<Integer, Prop> props = new HashMap<>();
	private static Prop heldArm;
	/** The head is off: the camera is where the zombie holds it. */
	private static boolean headOff;
	private static Vec3 headCam, headCamOld;
	/** The flashback: 0 none, else which cut is showing; the white flash; whether the screen is grey. */
	private static int flashCut;
	private static float white, whiteOld;
	private static boolean grey;

	// BEATEN: the next punch, which fist, how swollen your eyes are, the last punch on screen, the camera knocked aside
	private static int nextPunch, punchHand, blink;
	private static float swell;
	private static int impactTick = -100;
	private static float impactX, impactY, impactScale;
	private static float kickYaw, kickYawOld, kickPitch, kickPitchOld;

	// Debugging (/got debug|pause|step|speed): an orbit camera you steer with the mouse, markers, a readout; time control
	private static boolean debugView, debugPaused;
	private static int debugSteps;
	private static float debugSpeed = 1.0F, debugAcc;
	private static double debugDist = 4.0;

	// CHOKED: when the hands closed round your throat; the static eating your vision, and when it took over
	private static int chokeStart, staticOutTick;
	private static float staticLevel, staticTarget;

	// CRAWLING, on the bending body (BodyRig): the way you crawl and the body's right; the body; where it started, how far it
	// has come at this pull's start and how far this pull goes; the pull's rhythm and which hand reaches; where each hand grips
	// (and gripped before); when it took the leg, the arm, when it left; what its hands hold; how far gone you are
	private static Vec3 crawlDir, crawlSide, crawlOrigin;
	private static CutsceneClone crawler;
	private static double crawlDist, crawlStride;
	private static int crawlStart, crawlPeriod, legTick, armTick, leaveTick;
	private static boolean legBroken, armBroken, crawlRight;
	private static Vec3 plantR, plantL, fromR, fromL;
	private static Vec3 heldFoot, heldFootOld, heldHand, heldHandOld;
	private static float weak;
	/** How far round you have turned to look back (0..1, now and last tick), and over which shoulder (+1 right, -1 left). */
	private static float lookBack, lookBackOld, lookSide = 1;

	/** LIFTED / SLAMMED: your own body, hanging from its hands under the camera (head hidden), legs kicking. */
	private static CutsceneClone hanger;
	private static float hangKick, hangLimp, hangLimpOld;

	// LIFTED: when it grabbed you, the camera pulled toward it, and the blackout (the moment the head comes off)
	private static int grabTick, blackoutTick;
	private static double camDX, camDZ, camDXOld, camDZOld;

	// AMBUSHED: where your eyes dart to, when the sprinter hit you, how far you fly, its wind-up, knocked out
	private static Vec3 glance;
	private static int hitTick, windStart;
	private static double flyDist;
	private static boolean knockedOut;

	// Camera: while there is no body yet, eyes at the spot this high; then in the body's head
	private static double eyeHeight, eyeHeightOld;
	private static boolean inBody;
	private static Vec3 look, lookOld;
	private static float roll, rollOld, rollTarget;
	private static float shake;
	/** A bite just hurt you: for a few ticks your eyes snap to it. */
	private static Vec3 flinchAt;
	private static int flinch;
	/** When you last grunted in pain (not on every bite). */
	private static int lastPain = -100;
	// Screen: red flash, how dark (vignette), how black (knocked out / blinking), each now and last tick
	private static float redPulse;
	private static float dark, darkOld, darkTarget, darkRate = 0.1F;
	private static float black, blackOld, blackTarget, blackRate = 0.2F;

	private GameOverCutscene() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(GameOverPayload.TYPE, (payload, context) -> {
			if (payload.rounds() < 0) {
				stop(); // left the arena
			} else {
				safeStart(context.client(), payload);
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(GameOverCutscene::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> stop());
		for (String command : new String[] {"got", "gameovertest"}) {
			registerTestCommand(command);
		}
	}

	/** /got [pinned|overwhelmed|dragged|random] [rounds] plays one where you stand, anywhere; /got stop ends it. */
	private static void registerTestCommand(String command) {
		net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			var root = net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal(command)
				.requires(source -> source.getPlayer() != null)
				.executes(ctx -> test(null, 12))
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("stop").executes(ctx -> {
					stop();
					return 1;
				}))
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("debug").executes(ctx -> {
					debugView = !debugView;
					say("Cutscene debug view " + (debugView ? "ON: mouse orbits the scene. Markers: white = your eyes, flame = its hands, green = its face"
						: "OFF"));
					return 1;
				}).then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
					.argument("distance", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(1.0, 20.0)).executes(ctx -> {
						debugView = true;
						debugDist = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "distance");
						say("Cutscene debug view ON, " + debugDist + " blocks out");
						return 1;
					})))
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("pause").executes(ctx -> {
					debugPaused = !debugPaused;
					say("Cutscene " + (debugPaused ? "paused (/got step to advance)" : "running"));
					return 1;
				}))
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("step").executes(ctx -> {
					debugPaused = true;
					debugSteps++;
					return 1;
				}).then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
					.argument("ticks", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 400)).executes(ctx -> {
						debugPaused = true;
						debugSteps += com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "ticks");
						return 1;
					})))
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("speed")
					.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
						.argument("speed", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.05F, 1.0F)).executes(ctx -> {
							debugSpeed = com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "speed");
							say("Cutscene speed " + debugSpeed);
							return 1;
						})));
			for (String name : new String[] {"pinned", "overwhelmed", "dragged", "dismembered", "beaten", "ambushed", "lifted", "slammed", "choked", "crawling", "tackled", "missed", "random"}) {
				Kind k = name.equals("random") ? null : Kind.valueOf(name.toUpperCase(Locale.ROOT));
				root.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal(name)
					.executes(ctx -> test(k, 12))
					.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
						.argument("rounds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
						.executes(ctx -> test(k, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "rounds")))));
			}
			dispatcher.register(root);
		});
	}

	private static void say(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[got] " + text));
		}
	}

	private static int test(Kind k, int rounds) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return 0;
		}
		forced = k;
		boolean ok = safeStart(mc, new GameOverPayload(mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), rounds));
		forced = null;
		return ok ? 1 : 0;
	}

	/** Starts a cutscene; if anything goes wrong it is cleaned up and skipped rather than breaking the game. */
	private static boolean safeStart(Minecraft mc, GameOverPayload p) {
		try {
			start(mc, p);
			return true;
		} catch (RuntimeException e) {
			TacticalMovement.LOGGER.warn("[tactical] game-over cutscene could not start", e);
			stop();
			return false;
		}
	}

	public static boolean isActive() {
		return active;
	}

	/** The mouse and attack/use do nothing while a cutscene plays (except in the debug orbit, where the mouse turns it). */
	public static boolean locksInput() {
		return active && !debugView;
	}

	/** The cutscene's body of the player (its head is not drawn while the camera is in it). */
	public static boolean isBody(int entityId) {
		return active && entityId == bodyId && inBody && age < sceneLength;
	}

	/** How far a cutscene zombie leans forward this frame (0 for anything else). */
	public static float lean(int entityId, float partialTicks) {
		Walker w = active ? byId.get(entityId) : null;
		return w == null ? 0.0F : Mth.lerp(partialTicks, w.leanOld, w.lean);
	}

	/** A cutscene zombie's arms this frame, if the scene is moving them. */
	public static float[] arms(int entityId, float partialTicks) {
		Walker w = active ? byId.get(entityId) : null;
		if (w == null || Float.isNaN(w.armR)) {
			return NO_ARMS;
		}
		float in = Mth.lerp(partialTicks, w.armInOld, w.armIn);
		return new float[] {Mth.lerp(partialTicks, w.armROld, w.armR), Mth.lerp(partialTicks, w.armLOld, w.armL), in};
	}

	/** How far a cutscene zombie is kneeling this frame (0 for anything else). */
	public static float kneel(int entityId, float partialTicks) {
		Walker w = active ? byId.get(entityId) : null;
		return w == null ? 0.0F : Mth.lerp(partialTicks, w.kneelOld, w.kneel);
	}

	/** A torn-off arm (no name over it). */
	public static boolean isProp(int entityId) {
		return active && (props.containsKey(entityId) || hanger != null && hanger.getId() == entityId || BodyRig.has(entityId));
	}

	/** Which parts of a cutscene copy of the player are drawn (called for every player model; others are left as they are). */
	public static void applyParts(net.minecraft.client.model.player.PlayerModel m, int entityId) {
		if (!active) {
			return;
		}
		if (entityId == bodyId) {
			if ((inBody || headOff) && age < sceneLength && !debugView) {
				m.head.visible = false;
			}
			if ((gone & GONE_R_ARM) != 0) {
				m.rightArm.visible = false;
			}
			if ((gone & GONE_L_ARM) != 0) {
				m.leftArm.visible = false;
			}
			return;
		}
		if (hanger != null && entityId == hanger.getId()) {
			// Hanging by the throat: hands up clawing at its wrists, legs kicking
			m.head.visible = debugView;
			float kick = (float) Math.sin(age * 0.45) * hangKick;
			m.rightArm.xRot = -2.0F + kick * 0.15F;
			m.rightArm.yRot = -0.35F;
			m.rightArm.zRot = 0;
			m.leftArm.xRot = -2.0F - kick * 0.15F;
			m.leftArm.yRot = 0.35F;
			m.leftArm.zRot = 0;
			m.rightLeg.xRot = kick;
			m.leftLeg.xRot = -kick;
			m.rightLeg.yRot = m.leftLeg.yRot = 0;
			return;
		}
		Prop prop = props.get(entityId);
		if (prop != null) {
			m.head.visible = false;
			m.body.visible = false;
			m.rightLeg.visible = false;
			m.leftLeg.visible = false;
			(prop.right ? m.leftArm : m.rightArm).visible = false;
			var arm = prop.right ? m.rightArm : m.leftArm;
			arm.xRot = prop.armX;
			arm.yRot = 0;
			arm.zRot = 0;
		}
	}

	/**
	 * A kneeling zombie's legs: folded back along the ground under it, whatever its bend (the whole model is bent forward
	 * at the hips, so the legs are turned back by as much to stay on the ground).
	 */
	public static void applyPose(HumanoidModel<?> m, HumanoidRenderState state) {
		float[] arms = state.getData(ARMS);
		if (arms != null && arms.length >= 2) {
			float in = arms.length > 2 ? arms[2] : 0.0F;
			m.rightArm.xRot = arms[0];
			m.rightArm.yRot = 0.1F - in;
			m.rightArm.zRot = 0.08F;
			m.leftArm.xRot = arms[1];
			m.leftArm.yRot = -0.1F + in;
			m.leftArm.zRot = -0.08F;
		}
		Float kneel = state.getData(KNEEL);
		if (kneel == null || kneel <= 0.0F) {
			return;
		}
		Float lean = state.getData(LEAN);
		float l = lean == null ? 0.0F : (float) Math.toRadians(lean);
		float legs = 1.45F - l;
		m.rightLeg.xRot = Mth.lerp(kneel, m.rightLeg.xRot, legs);
		m.leftLeg.xRot = Mth.lerp(kneel, m.leftLeg.xRot, legs);
		m.rightLeg.yRot = 0;
		m.leftLeg.yRot = 0;
		m.rightLeg.zRot = Mth.lerp(kneel, m.rightLeg.zRot, 0.12F);
		m.leftLeg.zRot = Mth.lerp(kneel, m.leftLeg.zRot, -0.12F);
	}

	// ------------------------------------------------------------------ start / stop

	private static void start(Minecraft mc, GameOverPayload p) {
		stop();
		if (mc.level == null || mc.player == null) {
			return;
		}
		level = mc.level;
		active = true;
		age = 0;
		rounds = p.rounds();
		kind = forced != null ? forced : Kind.values()[RANDOM.nextInt(Kind.values().length)];
		sceneLength = kind.length;
		sx = p.x();
		sz = p.z();
		sy = ground(sx, p.y() + 0.5, sz);
		findOpenSpot();
		main = armR = armL = legs = last = null;
		gone = 0;
		heldArm = null;
		headOff = false;
		headCam = headCamOld = null;
		flashCut = 0;
		white = whiteOld = 0;
		nextPunch = punchHand = blink = 0;
		swell = 0;
		impactTick = -100;
		glance = null;
		grabTick = -1;
		blackoutTick = -1000;
		chokeStart = -1;
		overTick = -1;
		legTick = armTick = leaveTick = -1;
		legBroken = armBroken = false;
		heldFoot = heldFootOld = heldHand = heldHandOld = null;
		weak = 0;
		lookBack = lookBackOld = 0;
		staticOutTick = -1000;
		staticLevel = staticTarget = 0;
		camDX = camDZ = camDXOld = camDZOld = 0;
		hitTick = -1;
		windStart = 0;
		flyDist = 0;
		knockedOut = false;
		kickYaw = kickYawOld = kickPitch = kickPitchOld = 0;
		flinch = 0;
		flinchAt = null;
		lastPain = -100;
		shake = redPulse = 0;
		roll = rollOld = rollTarget = 0;
		dark = darkOld = darkTarget = 0;
		black = blackOld = blackTarget = 0;
		darkRate = 0.1F;
		blackRate = 0.2F;
		inBody = false;
		eyeHeight = eyeHeightOld = 1.62;

		// The most open way (the one they face counts too) is where the zombies come from
		double faceRad = Math.toRadians(p.yaw());
		double fx = -Math.sin(faceRad), fz = Math.cos(faceRad);
		double best = -1;
		for (int i = 0; i < 16; i++) {
			double a = i * Math.PI / 8;
			double dx = -Math.sin(a), dz = Math.cos(a);
			boolean thrown = kind == Kind.AMBUSHED || kind == Kind.CRAWLING || kind == Kind.TACKLED; // room ahead to be thrown / to crawl
			double score = room(dx, dz, 8) + (thrown ? flyRoom(-dx, -dz) * 1.5 : room(-dx, -dz, 2.5) * 1.2) + (dx * fx + dz * fz) * 1.2 + RANDOM.nextDouble() * 0.4;
			if (score > best) {
				best = score;
				awayX = dx;
				awayZ = dz;
			}
		}
		look = lookOld = new Vec3(sx + awayX * 6, sy + 1.5, sz + awayZ * 6);
		double rx = -awayZ, rz = awayX; // to the right, looking "away"

		switch (kind) {
			case PINNED -> {
				fallBody(mc, 0.075);
				armR = spawn(awayX * 0.6 + rx, awayZ * 0.6 + rz, 6.0);
				armR.speed = 0.14;
				armL = spawn(awayX * 0.6 - rx, awayZ * 0.6 - rz, 8.0);
				armL.startTick = 60;
				armL.speed = 0.1;
				legs = spawn(awayX, awayZ, 9.0);
				legs.startTick = 95;
				legs.speed = 0.1;
				last = spawn(-awayX + rx * 0.4, -awayZ + rz * 0.4, 6.0);
				last.startTick = 150;
				last.speed = 0.07;
			}
			case OVERWHELMED -> {
				eyeHeight = eyeHeightOld = 1.0; // on your knees
				for (int i = 0; i < 13; i++) {
					double a = Math.atan2(awayZ, awayX) + Math.PI + (i + 0.5) * (Math.PI * 2 / 13) + (RANDOM.nextDouble() - 0.5) * 0.3;
					Walker w = spawn(Math.cos(a), Math.sin(a), 6.5 + RANDOM.nextDouble() * 5);
					w.speed = 0.022 + RANDOM.nextDouble() * 0.012;
					w.startTick = RANDOM.nextInt(20);
				}
				main = spawn(awayX, awayZ, 9.0);
				main.speed = 0.0;
			}
			case DISMEMBERED -> {
				fallBody(mc, 0.075);
				main = spawn(awayX * 0.5 + rx, awayZ * 0.5 + rz, 5.0);
				main.speed = 0.12;
				for (int i = 0; i < 2; i++) {
					Walker w = spawn(awayX + (i == 0 ? rx : -rx) * 0.5, awayZ + (i == 0 ? rz : -rz) * 0.5, 10.0);
					w.startTick = 60 + i * 40;
					w.speed = 0.04;
				}
			}
			case BEATEN -> {
				main = spawn(awayX, awayZ, 3.4);
				main.speed = 0.1;
				for (int i = 0; i < 2; i++) {
					Walker w = spawn(awayX + (i == 0 ? rx : -rx) * 0.8, awayZ + (i == 0 ? rz : -rz) * 0.8, 11.0);
					w.startTick = 70 + i * 30;
					w.speed = 0.03;
				}
			}
			case CRAWLING -> {
				// Face down, crawling away from it (it comes from behind)
				crawlDir = new Vec3(-awayX, 0, -awayZ);
				crawlSide = new Vec3(awayZ, 0, -awayX); // the body's right, face down heading that way
				crawlOrigin = new Vec3(sx, sy, sz);
				crawlDist = crawlStride = 0;
				crawlPeriod = 26;
				crawlStart = -26;
				crawlRight = false;
				Vec3 sh = crawlOrigin.add(crawlDir.scale(0.85));
				plantR = fromR = onGround(sh.add(crawlSide.scale(0.3)));
				plantL = fromL = onGround(sh.subtract(crawlSide.scale(0.3)).add(crawlDir.scale(0.15)));
				crawler = CutsceneClone.of(mc);
				crawler.setId(nextId--);
				crawler.setSilent(true);
				crawler.noPhysics = true;
				crawler.setNoGravity(true);
				crawler.snapTo(sx, sy, sz, 0.0F, 0.0F);
				level.addEntity(crawler);
				BodyRig.attach(crawler.getId(), GameOverCutscene::crawlPose);
				inBody = true;
				look = lookOld = new Vec3(sx - awayX * 6, sy, sz - awayZ * 6);
				main = spawn(awayX, awayZ, 4.0);
				main.speed = 0;
			}
			case CHOKED -> {
				main = spawn(awayX, awayZ, 3.2);
				main.speed = 0.07;
				for (int i = 0; i < 2; i++) {
					Walker w = spawn(awayX + (i == 0 ? rx : -rx) * 0.9, awayZ + (i == 0 ? rz : -rz) * 0.9, 11.0);
					w.startTick = 90 + i * 25;
					w.speed = 0.03;
				}
			}
			case LIFTED, SLAMMED -> {
				// A ring of them closing in on every side but the front, and the one in front that comes for you
				double base = Math.atan2(awayZ, awayX);
				for (int i = 0; i < 11; i++) {
					double a = base + 0.55 + (i + 0.5) * ((Math.PI * 2 - 1.1) / 11) + (RANDOM.nextDouble() - 0.5) * 0.2;
					Walker w = spawn(Math.cos(a), Math.sin(a), 6.0 + RANDOM.nextDouble() * 4);
					w.speed = 0.03 + RANDOM.nextDouble() * 0.015;
					w.startTick = RANDOM.nextInt(20);
				}
				main = spawn(awayX, awayZ, 4.6);
				main.speed = 0.0;
			}
			case AMBUSHED -> {
				main = spawn(awayX, awayZ, 13.0);
				main.speed = 0;
			}
			case MISSED -> {
				swingTick = dodgeEnd = limpFrom = -1;
				main = spawn(awayX, awayZ, 6.5);
				main.speed = 0;
				Walker w = spawn(awayX - rx * 0.6, awayZ - rz * 0.6, 12.0);
				w.startTick = 110;
				w.speed = 0.045;
				giveSword(mc);
			}
			case TACKLED -> {
				// Running for it, away from where they come from (it comes from behind, faster than you)
				crawlDir = new Vec3(-awayX, 0, -awayZ);
				crawlSide = new Vec3(awayZ, 0, -awayX); // the body's right, face down heading that way
				flyDist = Mth.clamp(flyRoom(-awayX, -awayZ) - 2.6, 0.8, 6.0); // how far you get before a wall
				look = lookOld = new Vec3(sx - awayX * 8, sy + 1.3, sz - awayZ * 8);
				eyeHeight = eyeHeightOld = 1.55;
				fallFrom = null;
				main = spawn(awayX, awayZ, 6.5);
				main.speed = 0;
				Walker w = spawn(awayX + rx * 0.4, awayZ + rz * 0.4, 12.0);
				w.startTick = 95;
				w.speed = 0.045;
			}
			case DRAGGED -> {
				dragBiteFrom = dragBiteAt = -1;
				main = spawn(awayX, awayZ, 4.0);
				main.speed = 0.055;
				for (int i = 0; i < 3; i++) {
					Walker w = spawn(awayX + (i - 1) * 0.5, awayZ + (i - 1) * 0.5, 9 + i * 2);
					w.startTick = 70;
					w.speed = 0.045;
				}
			}
		}
		level.playLocalSound(sx, sy + 1, sz, SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0F, 0.75F, false);
		nextBeat = 0;
		inHead(DREAD, 0.9F, 1.0F);
	}

	/** The camera view you had before the cutscene forced first person (put back when it ends). */
	private static net.minecraft.client.CameraType cameraBefore;

	public static void stop() {
		takeSword();
		if (cameraBefore != null) {
			Minecraft.getInstance().options.setCameraType(cameraBefore);
			cameraBefore = null;
		}
		removeHanger();
		if (crawler != null) {
			BodyRig.detach(crawler.getId());
			if (level != null) {
				level.removeEntity(crawler.getId(), Entity.RemovalReason.DISCARDED);
			}
			crawler = null;
		}
		active = false;
		for (var instance : HEAD_SOUNDS) {
			Minecraft.getInstance().getSoundManager().stop(instance);
		}
		HEAD_SOUNDS.clear();
		if (level != null) {
			for (Walker w : walkers) {
				BodyRig.detach(w.zombie.getId());
				level.removeEntity(w.zombie.getId(), Entity.RemovalReason.DISCARDED);
			}
		}
		walkers.clear();
		byId.clear();
		main = armR = armL = legs = last = null;
		if (level != null) {
			for (int id : props.keySet()) {
				level.removeEntity(id, Entity.RemovalReason.DISCARDED);
			}
		}
		props.clear();
		heldArm = null;
		headOff = false;
		flashCut = 0;
		setGrey(false);
		if (body != null) {
			body.remove();
			body = null;
		}
		bodyId = Integer.MIN_VALUE;
		inBody = false;
		level = null;
	}

	/** The body falls onto its back (feet toward the zombies), the camera going with it. */
	private static void fallBody(Minecraft mc, double push) {
		// Over backward (feet toward the zombies), sliding back with the blow (not through a wall)
		double slide = Math.max(0, Math.min(push * 5, flyRoom(-awayX, -awayZ) - 1.9));
		CutsceneClone clone = CutsceneClone.of(mc);
		clone.setId(nextId--);
		clone.setSilent(true);
		clone.noPhysics = true;
		clone.setNoGravity(true);
		clone.snapTo(sx, sy, sz, 0.0F, 0.0F);
		level.addEntity(clone);
		body = RigBody.fall(clone, level, new Vec3(sx, sy, sz), new Vec3(-awayX, 0, -awayZ), slide);
		bodyId = clone.getId();
		inBody = true;
		fx(new Vec3(sx, sy + 0.2, sz), BODY_FALL, 1.0F, 1.0F);
	}

	/**
	 * Which way a walker heads toward its goal (unit, flat): straight there, or around your body if it lies in the way
	 * (round the side it is already on), instead of walking through it.
	 */
	private static Vec3 steer(Walker w, double ux, double uz, double dist) {
		if (body == null) {
			return new Vec3(ux, 0, uz);
		}
		final double r = 0.42;
		Vec3 hips = point(Corpses.HIPS), neck = point(Corpses.NECK), head = point(Corpses.HEAD);
		Vec3[] parts = { hips, hips.lerp(neck, 0.5), neck, head };
		double px = -uz, pz = ux; // to its left
		double bestAlong = Double.MAX_VALUE;
		Vec3 out = null;
		for (Vec3 c : parts) {
			double cx = c.x - w.x, cz = c.z - w.z;
			double along = cx * ux + cz * uz, side = cx * px + cz * pz;
			double gx = c.x - w.goal.x, gz = c.z - w.goal.z;
			if (along <= 0.05 || along >= dist || Math.abs(side) >= r || gx * gx + gz * gz < r * r || along >= bestAlong) {
				continue;
			}
			bestAlong = along;
			double sign = side >= 0 ? -1 : 1; // pass it on the other side from where it is
			double tx = c.x + px * sign * (r + 0.35) - w.x, tz = c.z + pz * sign * (r + 0.35) - w.z;
			double tl = Math.sqrt(tx * tx + tz * tz);
			if (tl > 1.0E-3) {
				out = new Vec3(tx / tl, 0, tz / tl);
			}
		}
		return out != null ? out : new Vec3(ux, 0, uz);
	}

	/** A zombie `dist` blocks out from the spot in direction (dx, dz) (closer if a wall is in the way). */
	private static Walker spawn(double dx, double dz, double dist) {
		double l = Math.sqrt(dx * dx + dz * dz);
		dx /= l;
		dz /= l;
		double reach = Math.max(0.8, Math.min(dist, room(dx, dz, dist))); // never past a wall or the edge of the map
		double x = sx + dx * reach, z = sz + dz * reach;
		double y = ground(x, sy + 1.0, z);
		// Checks skipped: on Peaceful the game would refuse to make a zombie at all
		Entity made = EntityTypes.ZOMBIE.create(level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true));
		if (!(made instanceof Zombie zombie)) {
			throw new IllegalStateException("could not make a cutscene zombie");
		}
		zombie.setId(nextId--);
		float yaw = (float) Math.toDegrees(Math.atan2(dx, -dz));
		zombie.snapTo(x, y, z, yaw, 0.0F);
		zombie.setYHeadRot(yaw);
		zombie.yBodyRot = yaw;
		zombie.setSilent(true);
		zombie.setAggressive(true);
		level.addEntity(zombie);
		Walker w = new Walker(zombie, x, y, z);
		w.yaw = w.yawOld = yaw;
		BodyRig.attach(zombie.getId(), w);
		w.goal = new Vec3(sx, sy, sz);
		w.faceAt = new Vec3(sx, sy + 1, sz);
		w.speed = 0.06;
		walkers.add(w);
		byId.put(zombie.getId(), w);
		return w;
	}

	// ------------------------------------------------------------------ every tick

	private static void tick(Minecraft mc) {
		if (!active) {
			return;
		}
		if (mc.level != level || mc.player == null) {
			stop(); // another world: the player left
			return;
		}
		// First person only (F5 does nothing): the scene is seen through your eyes
		if (!debugView && mc.options.getCameraType() != net.minecraft.client.CameraType.FIRST_PERSON) {
			if (cameraBefore == null) {
				cameraBefore = mc.options.getCameraType();
			}
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
		}
		if (mc.isPaused()) {
			return;
		}
		if (debugPaused) {
			if (debugSteps <= 0) {
				return;
			}
			debugSteps--;
		} else if (debugSpeed < 1.0F) {
			debugAcc += debugSpeed;
			if (debugAcc < 1.0F) {
				return;
			}
			debugAcc -= 1.0F;
		}
		age++;
		eyeHeightOld = eyeHeight;
		rollOld = roll;
		darkOld = dark;
		blackOld = black;
		shake *= 0.8F;
		staticLevel += (staticTarget - staticLevel) * 0.25F;
		camDXOld = camDX;
		camDZOld = camDZ;
		kickYawOld = kickYaw;
		kickPitchOld = kickPitch;
		kickYaw *= 0.6F;
		kickPitch *= 0.6F;
		redPulse *= 0.88F;
		if (flinch > 0) {
			flinch--;
		}
		whiteOld = white;
		white *= 0.82F;
		if (headCam != null) {
			headCamOld = headCam;
		}
		if (age >= sceneLength + SCREEN) {
			stop();
			return;
		}
		if (age >= sceneLength) {
			if (age == sceneLength) {
				// Not cut off with the cutscene: it rings out past the end of the screen
				Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(GAME_OVER, 1.0F, 1.0F));
			}
			return;
		}
		boolean panicking = (kind == Kind.AMBUSHED || kind == Kind.LIFTED || kind == Kind.SLAMMED || kind == Kind.TACKLED && hitTick < 0 || kind == Kind.MISSED && bodyId == Integer.MIN_VALUE) && !knockedOut;
		if ((inBody || panicking) && !knockedOut && age >= nextBeat) {
			boolean out = kind == Kind.DRAGGED && age < WAKE;
			inHead(HEARTBEAT, out ? 0.5F : 0.75F, out ? 0.8F : 1.0F);
			boolean choking = kind == Kind.CHOKED && chokeStart >= 0;
			boolean bleeding = kind == Kind.CRAWLING && leaveTick >= 0;
			nextBeat = age + (out ? 30 : panicking ? 12 : choking ? 14 + (age - chokeStart) / 5 : bleeding ? 14 + (age - leaveTick) / 4
				: Math.max(11, 24 - age / 15));
		}
		try {
			if (bodyId != Integer.MIN_VALUE && inBody && !knockedOut && !headOff) {
				struggle(struggleLevel());
			}
			switch (kind) {
				case PINNED -> pinned();
				case OVERWHELMED -> overwhelmed(mc);
				case DRAGGED -> dragged(mc);
				case DISMEMBERED -> dismembered(mc);
				case BEATEN -> beaten(mc);
				case AMBUSHED -> ambushed(mc);
				case LIFTED, SLAMMED -> lifted(mc);
				case CHOKED -> choked(mc);
				case CRAWLING -> crawling(mc);
				case TACKLED -> tackled(mc);
				case MISSED -> missed(mc);
			}
			for (Walker w : walkers) {
				moveWalker(w);
			}
			if (body != null) {
				body.hideHead = (inBody || headOff) && !debugView;
				body.hideArmR = (gone & GONE_R_ARM) != 0;
				body.hideArmL = (gone & GONE_L_ARM) != 0;
				body.tick();
			}
			if (hanger != null && main != null) {
				placeHanger();
			}
			if (debugView) {
				float[] e = scene(1.0F);
				if (e != null) {
					level.addParticle(ParticleTypes.END_ROD, e[0], e[1], e[2], 0, 0, 0);
				}
				if (main != null) {
					Vec3 hd = main.hands(), fc = main.face(1.0F);
					level.addParticle(ParticleTypes.FLAME, hd.x, hd.y, hd.z, 0, 0, 0);
					level.addParticle(ParticleTypes.HAPPY_VILLAGER, fc.x, fc.y, fc.z, 0, 0, 0);
				}
			}
		} catch (RuntimeException e) {
			TacticalMovement.LOGGER.warn("[tactical] game-over cutscene failed", e);
			stop();
			return;
		}
		roll += (rollTarget - roll) * 0.15F;
		dark += (darkTarget - dark) * darkRate;
		black += (blackTarget - black) * blackRate;
	}

	private static void moveWalker(Walker w) {
		w.leanOld = w.lean;
		w.kneelOld = w.kneel;
		w.lastX = w.x;
		w.lastZ = w.z;
		if (age < w.startTick) {
			return;
		}
		double dx = w.goal.x - w.x, dz = w.goal.z - w.z;
		double dist = Math.sqrt(dx * dx + dz * dz);
		// Sent somewhere new: it isn't there any more (it gets up, turns and walks over, instead of sliding there on its knees)
		if (w.arrived && dist > 0.6) {
			w.arrived = false;
		}
		// Going somewhere (not just shuffling into place, and not moving while holding on to something): it walks there
		boolean holding = w.faceWhileMoving || w.reach > 0.3F || w.armUse > 0.5F;
		boolean travel = dist > 0.45 && w.speed > 0 && !holding;
		double lx = w.faceAt.x - w.x, lz = w.faceAt.z - w.z;
		float lookYaw = (float) Math.toDegrees(Math.atan2(-lx, lz));
		float bodyWant = lookYaw;
		if (travel) {
			Vec3 dir = steer(w, dx / dist, dz / dist, dist);
			float travelYaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
			// Facing the way it walks; turning to what it wants as it gets there
			float there = (float) Mth.clamp((dist - 0.45) / 0.7, 0, 1);
			bodyWant = lookYaw + Mth.wrapDegrees(travelYaw - lookYaw) * there;
			// It turns before it sets off (a big turn: on the spot first), stands up first if it was down, speeds up and
			// slows down as it gets there
			float err = Math.abs(Mth.wrapDegrees(travelYaw - w.yaw));
			double want = w.speed * Mth.clamp(1 - (err - 30) / 60.0, 0, 1);
			if (w.kneel > 0.35F) {
				want = 0;
			}
			want = Math.min(want, Math.max(0.025, (dist - 0.2) * 0.3));
			double accel = Math.max(0.006, w.speed * 0.12);
			w.cur += Mth.clamp(want - w.cur, -accel * 2, accel);
			double step = Math.min(dist, Math.max(0, w.cur));
			w.x += dir.x * step;
			w.z += dir.z * step;
		} else if (dist > 0.05 && w.speed > 0) {
			// Shuffling the last bit into place, or moved while holding on: as before
			double step = Math.min(dist, w.speed);
			w.x += dx / dist * step;
			w.z += dz / dist * step;
			w.cur = Math.min(w.cur, step);
		} else {
			w.cur = 0;
		}
		if (!w.arrived && dist < 0.3) {
			w.arrived = true;
		}
		w.y = ground(w.x, w.y + 0.6, w.z);
		// Walking off it gets up first; once there, what the scene wants (kneeling down beside it)
		float kneelWant = travel ? 0 : w.kneelTarget;
		w.kneel += (kneelWant - w.kneel) * (travel ? Math.max(w.kneelRate, 0.16F) : w.kneelRate);
		w.yawOld = w.yaw;
		// It turns at a natural rate (faster when it is running), not snapping round
		float turnMax = 9 + 14 * w.sprintW;
		w.yaw = w.yawOld + Mth.clamp(Mth.wrapDegrees(bodyWant - w.yawOld), -turnMax, turnMax);
		float turned = Math.abs(Mth.wrapDegrees(w.yaw - w.yawOld));
		// The head looks at what it wants (as far as a neck turns), the body following
		w.headTurnOld = w.headTurn;
		w.headTurn += (Mth.clamp(Mth.wrapDegrees(lookYaw - w.yaw), -65, 65) - w.headTurn) * 0.3F;
		float yaw = w.yaw + w.headTurn;
		w.walkedOld = w.walked;
		double mx = w.x - w.lastX, mz = w.z - w.lastZ;
		double moved = Math.sqrt(mx * mx + mz * mz);
		w.walked += moved;
		w.phaseOld = w.phase;
		w.phase += Math.min(moved * Mth.lerp(w.sprintW, 4.6F, 3.2F), 0.2 + 0.28 * w.sprintW);
		w.pace += (moved - w.pace) * 0.25;
		if (moved > 1.0E-3) {
			w.moving = w.moving.lerp(new Vec3(mx / moved, 0, mz / moved), 0.35).normalize();
		}
		w.walkWOld = w.walkW;
		w.sprintWOld = w.sprintW;
		float wantWalk = (float) Mth.clamp((w.pace - 0.004) / 0.025, 0, 1), wantSprint = (float) Mth.clamp((w.pace - 0.12) / 0.22, 0, 1);
		if (turned > 1.5F && w.kneel < 0.3F) {
			// Turning on the spot it steps round (its feet move), not spinning on them
			wantWalk = Math.max(wantWalk, Math.min(1.0F, turned / 10.0F) * 0.55F);
			w.phase += turned * 0.012;
		}
		w.walkW += (wantWalk - w.walkW) * 0.2F;
		w.sprintW += (wantSprint - w.sprintW) * 0.15F;
		w.armUseOld = w.armUse;
		w.armUse += ((w.armCalled ? 1.0F : 0.0F) - w.armUse) * 0.18F;
		w.armCalled = false;
		w.zombie.moveOrInterpolateTo(new Vec3(w.x, w.y, w.z), w.yaw, w.headPitch);
		w.zombie.lerpHeadTo(yaw + w.headJitter, 2);
		w.zombie.yBodyRot = w.yaw;
		w.lean += (w.leanTarget - w.lean) * w.leanRate;
		if (inBody && bodyId != Integer.MIN_VALUE && !headOff) {
			// Its head never comes down into yours (the camera would be inside it): it bends less instead
			// (MISSED: it's eating your face, so it comes right down, measured from your eyes, just short of them)
			boolean faceDown = w == main && (kind == Kind.MISSED || kind == Kind.DRAGGED && dragBiteAt >= 0);
			Vec3 cam = faceDown ? point(Corpses.HEAD).add(0, 0.24, 0) : point(Corpses.HEAD);
			double minDist = faceDown ? 0.36 : 0.75;
			for (int i = 0; i < 15 && w.lean > -10 && w.face(1.0F).distanceTo(cam) < minDist; i++) {
				w.lean -= 3;
			}
		}
		w.armInOld = w.armIn;
		w.reachOld = w.reach;
		w.reachROld = w.reachR;
		w.reachLOld = w.reachL;
		if (!Float.isNaN(w.armR)) {
			w.armROld = w.armR;
			w.armLOld = w.armL;
			w.armR += (w.armRTarget - w.armR) * w.armRRate;
			w.armL += (w.armLTarget - w.armL) * w.armLRate;
		}
		if (--w.nextGroan <= 0) {
			w.nextGroan = 35 + RANDOM.nextInt(60);
			level.playLocalSound(w.x, w.y + 1.5, w.z, SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE, 1.0F, 0.85F + RANDOM.nextFloat() * 0.3F,
				false);
		}
	}

	// ---------------------------------------------------------------- PINNED: eaten limb by limb

	private static void pinned() {
		Vec3 head = point(Corpses.HEAD), neck = point(Corpses.NECK), hips = point(Corpses.HIPS);
		Vec3 handR = point(Corpses.R_HAND), handL = point(Corpses.L_HAND);
		Vec3 footR = point(Corpses.R_FOOT), footL = point(Corpses.L_FOOT);

		// 1. Your right arm: it kneels beside it, pulls it up to its mouth and bites into it
		boolean rDown = eatLimb(armR, Corpses.R_HAND, handR, neck, 20);
		// 2. Your left arm, the same, a little later
		boolean lDown = age >= armL.startTick && eatLimb(armL, Corpses.L_HAND, handL, neck, 26);
		// 3. Your legs: it kneels at your feet and pulls one leg up, then the other
		boolean legsDown = false;
		if (age >= legs.startTick) {
			int leg = age < 185 ? Corpses.R_FOOT : Corpses.L_FOOT;
			legsDown = eatLimb(legs, leg, leg == Corpses.R_FOOT ? footR : footL, hips, 18);
		}
		// 4. The last one comes round behind your head and down over your face
		if (age >= last.startTick) {
			last.goal = head.add(-awayX * 0.75, 0, -awayZ * 0.75);
			last.faceAt = head;
			if (last.arrived) {
				last.kneelTarget = 1;
				last.leanTarget = age >= 236 ? 88 : 52;
				last.leanRate = age >= 236 ? 0.35F : 0.15F;
				last.headPitch = 40;
				if (age == 240 || age == 248) {
					tear(head.add(-awayX * 0.2, 0.1, -awayZ * 0.2), last, true);
				}
			} else {
				last.leanTarget = 10;
			}
		}

		// Your free left hand fights on its own side: now and then it swings across and shoves at the chest of the one on your
		// other arm (not up where your right hand is held at its mouth), otherwise it flails and claws at the air
		if (rDown && !lDown && age < 120) {
			int c = age % 16;
			if (c < 6) {
				double[][] fr = bodyFrame(1.0F);
				Vec3 right = fr == null ? new Vec3(-awayZ, 0, awayX) : new Vec3(fr[1][0], fr[1][1], fr[1][2]);
				BodyRig.Joints z = BodyRig.solve(armR.pose(1.0F), false);
				Vec3 chest = z.mid.lerp(z.neck, 0.4).subtract(right.scale(0.15));
				bodyPull(Corpses.L_HAND, chest.x, chest.y, chest.z, c < 3 ? 0.45 : 0.2);
			}
		}
		// Your legs kick until they are taken (fainter as you weaken)
		if (!legsDown && age > 25 && age < 200 && age % 9 < 3) {
			int leg = (age / 9) % 2 == 0 ? Corpses.R_FOOT : Corpses.L_FOOT;
			Vec3 f = leg == Corpses.R_FOOT ? footR : footL;
			double kick = age < 120 ? 0.35 : 0.18;
			bodyPull(leg, f.x + awayX * 0.15, f.y + kick, f.z + awayZ * 0.15, 0.5);
		}

		// What you look at: whatever is being eaten right now
		Vec3 want;
		float rate = 0.1F;
		if (age < 30 || !rDown) {
			want = armR.face(1.0F);
			rate = 0.2F;
		} else if (age < 100 || !lDown) {
			want = handR.lerp(armR.face(1.0F), 0.5);
		} else if (age < 150 || !legsDown) {
			want = handL.lerp(armL.face(1.0F), 0.5);
		} else if (age < 222) {
			want = (age < 185 ? footR : footL).lerp(legs.face(1.0F), 0.5).add(0, 0.2, 0);
			rate = 0.08F;
		} else {
			want = last.face(1.0F);
			rate = 0.25F;
		}
		if (flinch > 0 && flinchAt != null && age < 222) {
			// Your eyes go to the bite
			want = flinchAt;
			rate = 0.2F;
		}
		look(want, rate);

		// Fading: darker, then black as the last one comes down on you
		if (age > 130) {
			darkTarget = Math.min(0.7F, (age - 130) / 110.0F);
			rollTarget = 10;
		}
		if (age > 250) {
			blackTarget = 1;
			blackRate = 0.2F;
		}
	}

	/**
	 * A zombie eating a limb: it walks up, kneels outside the limb (`from` is the body's middle, to know which side is
	 * outside), holds the hand / foot up to its mouth and bites into it every `every` ticks. True once it is eating.
	 */
	private static boolean eatLimb(Walker w, int limb, Vec3 at, Vec3 from, int every) {
		double ox = at.x - from.x, oz = at.z - from.z;
		double ol = Math.sqrt(ox * ox + oz * oz);
		if (ol < 1.0E-3) {
			ox = awayX;
			oz = awayZ;
			ol = 1;
		}
		w.goal = at.add(ox / ol * 0.85, 0, oz / ol * 0.85);
		w.faceAt = at;
		if (!w.arrived) {
			w.leanTarget = 10;
			return false;
		}
		w.kneelTarget = 1;
		int c = Math.floorMod(age + w.zombie.getId(), every); // ids are negative: floorMod, not %
		boolean dip = c < 5;
		w.leanTarget = dip ? 48 : 34;
		w.leanRate = dip ? 0.4F : 0.25F;
		w.headPitch = dip ? 55 : 25;
		w.headJitter = dip ? 0 : (float) Math.sin(age * 2.1 + w.zombie.getId()) * 30;
		// The limb held up at its mouth (jerked about as it tears)
		Vec3 m = w.mouth();
		double j = dip ? 0 : (RANDOM.nextDouble() - 0.5) * 0.15;
		bodyPull(limb, m.x + j, m.y - 0.1, m.z + j, 0.4);
		if (c == 5) {
			tear(at, w, limb == Corpses.R_HAND || limb == Corpses.L_HAND);
		}
		return true;
	}

	// ---------------------------------------------------------------- OVERWHELMED: surrounded, one rushes you

	private static final int RUSH = 92, HIT = 112;

	private static void overwhelmed(Minecraft mc) {
		double baseYaw = Math.atan2(awayZ, awayX);
		if (age < RUSH) {
			// On your knees, looking around: they are everywhere, coming closer
			double u = age / (double) RUSH;
			double a = baseYaw + Math.sin(u * Math.PI * 1.5) * 2.4;
			eyeHeight = 1.0 + Math.sin(age * 0.15) * 0.02;
			look(new Vec3(sx + Math.cos(a) * 6, sy + 1.2, sz + Math.sin(a) * 6), 0.14F);
			rollTarget = (float) (Math.sin(age * 0.05) * 3);
			darkTarget = 0.15F;
			for (Walker w : walkers) {
				if (w != main) {
					w.goal = new Vec3(sx, sy, sz);
					w.faceAt = new Vec3(sx, sy + 1, sz);
					w.leanTarget = 6;
					double dx = w.x - sx, dz = w.z - sz;
					if (dx * dx + dz * dz < 2.6 * 2.6) {
						w.speed = 0; // a ring around you
					}
				}
			}
			return;
		}
		if (bodyId == Integer.MIN_VALUE && age < HIT + 30) {
			// A sprinter: screams and runs at you (it hits when it gets to you, not on a clock)
			main.speed = 0.36;
			main.goal = new Vec3(sx, sy, sz);
			main.faceAt = new Vec3(sx, sy + 1, sz);
			main.leanTarget = 25;
			look(main.face(1.0F), 0.4F);
			if ((age - RUSH) % 14 == 0) {
				// The sprinter's scream, loud (played at you: it is right on you)
				level.playLocalSound(sx, sy + 1.0, sz, SPRINT, SoundSource.HOSTILE, 1.0F, 1.0F, false);
			}
			double dx = main.x - sx, dz = main.z - sz;
			if (dx * dx + dz * dz > 1.2 * 1.2) {
				return;
			}
		}
		if (bodyId == Integer.MIN_VALUE) {
			// It hits you: down you go, onto your back
			awayX = main.x - sx;
			awayZ = main.z - sz;
			double l = Math.sqrt(awayX * awayX + awayZ * awayZ);
			if (l < 1.0E-3) {
				awayX = 1;
				awayZ = 0;
			} else {
				awayX /= l;
				awayZ /= l;
			}
			fallBody(mc, 0.12);
			hitTick = age;
			main.zombie.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
			shake = 2.0F;
			redPulse = 1.0F;
			level.playLocalSound(sx, sy + 1, sz, SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0F, 0.6F, false);
			level.playLocalSound(sx, sy + 1, sz, HIT_PLAYER, SoundSource.HOSTILE, 1.0F, 0.8F, false);
			fx(new Vec3(sx, sy + 1.6, sz), PUNCH, 1.2F, 0.9F);
		}
		// Kneeling astride you, biting over and over
		Vec3 head = point(Corpses.HEAD), neck = point(Corpses.NECK);
		main.goal = neck.add(awayX * 0.35, 0, awayZ * 0.35);
		main.faceAt = head;
		main.speed = 0.2;
		main.kneelTarget = 1;
		// First it climbs down onto you (you landing, it dropping to its knees over you, bending in, snarling at your
		// face); only then the biting starts
		int since = age - Math.max(hitTick, 0);
		boolean biting = since >= 26 && main.kneel > 0.75F;
		if (!biting) {
			float in = Mth.clamp((since - 8) / 18.0F, 0, 1);
			main.leanTarget = 10 + 30 * in;
			main.leanRate = 0.15F;
			main.headPitch = 10 + 15 * in;
			main.headJitter = (float) Math.sin(age * 0.9) * 12 * in;
			if (since == 18) {
				level.playLocalSound(main.x, main.y + 1.2, main.z, SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE, 1.0F, 0.7F, false);
			}
		} else {
			int c = (since - 26) % 7;
			main.leanTarget = c < 3 ? 62 : 40;
			main.leanRate = 0.6F;
			main.headPitch = c < 3 ? 50 : 20;
			main.headJitter = (float) Math.sin(age * 2.7) * 20;
			if (c == 2) {
				tear(neck.lerp(head, 0.4), main, true);
			}
		}
		look(main.face(1.0F), 0.35F);
		double rx = -awayZ, rz = awayX;
		Vec3 face = main.face(1.0F);
		double k = age < 175 ? 0.3 : 0.04;
		bodyPull(Corpses.R_HAND, face.x + rx * 0.2, face.y - 0.25, face.z + rz * 0.2, k);
		bodyPull(Corpses.L_HAND, face.x - rx * 0.2, face.y - 0.25, face.z - rz * 0.2, k);
		// The ring closes over you
		for (Walker w : walkers) {
			if (w != main) {
				double dx = w.x - head.x, dz = w.z - head.z;
				double d = Math.sqrt(dx * dx + dz * dz);
				w.goal = d > 0.01 ? head.add(dx / d * 1.4, 0, dz / d * 1.4) : head;
				w.faceAt = head;
				w.speed = 0.05;
				w.leanTarget = w.arrived ? 40 + (float) Math.sin(age * 0.3 + w.zombie.getId()) * 6 : 8;
			}
		}
		darkTarget = Math.min(0.8F, 0.2F + (age - HIT) / 120.0F);
		if (age > 205) {
			blackTarget = 1;
			blackRate = 0.15F;
		}
	}

	// ---------------------------------------------------------------- TACKLED: running, shoved down from behind, bitten

	/** How long the fall onto your front takes (ticks), and where your eyes were when it hit you. */
	private static final int TACKLE_FALL = 9;
	private static Vec3 fallFrom;
	/** How long the scene runs (a scene can cut itself shorter): starts as its kind's length. */
	private static int sceneLength = 200;
	private static int biteStart = -1;

	private static void tackled(Minecraft mc) {
		lookBackOld = lookBack;
		if (hitTick < 0) {
			biteStart = -1;
			// Running flat out (head bobbing, swaying with each stride), glancing back once: it is right behind you, faster
			double ran = Math.min(flyDist, Math.max(0, age - 4) * 0.16 * Mth.clamp(age / 8.0, 0.3, 1.0));
			camDX = crawlDir.x * ran;
			camDZ = crawlDir.z * ran;
			double px = sx + camDX, pz = sz + camDZ;
			boolean stuck = ran >= flyDist - 1.0E-3;
			double stride = Math.sin(age * 0.62);
			eyeHeight = 1.55 + (stuck ? 0 : Math.abs(stride) * 0.07 - 0.035);
			rollTarget = (float) (stuck ? 0 : stride * 4.5);
			if (!stuck && age % 5 == 0) {
				level.playLocalSound(px, sy, pz, SoundEvents.STONE_STEP, SoundSource.PLAYERS, 0.35F, 0.9F + RANDOM.nextFloat() * 0.2F, false);
			}
			Vec3 eyesNow = new Vec3(px, sy + eyeHeight, pz);
			boolean glanceBack = age >= 22 && age < 36;
			look(glanceBack ? main.face(1.0F) : eyesNow.add(crawlDir.scale(8)).add(0, -0.5, 0), glanceBack ? 0.32F : 0.25F);
			darkTarget = 0.12F;
			if (age == 24) {
				level.playLocalSound(main.x, main.y + 1.5, main.z, SPRINT, SoundSource.HOSTILE, 1.0F, 1.0F, false);
				shake = Math.max(shake, 0.6F);
			}
			if (age >= 6) {
				main.goal = new Vec3(px, sy, pz);
				main.faceAt = new Vec3(px, sy + 1, pz);
				main.speed = 0.3;
				main.leanTarget = 14;
				if ((age - 6) % 14 == 0 && age > 30) {
					level.playLocalSound(main.x, main.y + 1.5, main.z, SPRINT, SoundSource.HOSTILE, 0.9F, 1.1F, false);
				}
			}
			double dx = main.x - px, dz = main.z - pz;
			if (dx * dx + dz * dz > 1.15 * 1.15 && age < 110) {
				// Arms coming up for you as it closes in (only once it's close: until then it runs)
				return;
			}
			// It shoves you in the back: you pitch forward onto your front
			hitTick = age;
			fallFrom = eyesNow;
			main.arms(-1.6F, 0.7F, -1.6F, 0.7F);
			main.zombie.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
			fx(eyesNow, PUNCH, 1.3F, 0.7F);
			level.playLocalSound(px, sy + 1, pz, SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0F, 0.8F, false);
			level.playLocalSound(main.x, main.y + 1.5, main.z, ATTACK, SoundSource.HOSTILE, 1.1F, 0.9F, false);
			shake = 1.4F;
			kickPitch = 18;
			// Where you land: on from where you were, face down, hands thrown out in front
			double land = Math.min(0.6, Math.max(0, flyRoom(crawlDir.x, crawlDir.z) - 2.2));
			Vec3 o = new Vec3(px, sy, pz).add(crawlDir.scale(land));
			crawlOrigin = new Vec3(o.x, ground(o.x, sy + 1.0, o.z), o.z);
			crawlDist = crawlStride = 0;
			crawlPeriod = 30;
			crawlStart = age - crawlPeriod; // still: no pull going on
			crawlRight = false;
			Vec3 sh = crawlOrigin.add(crawlDir.scale(0.85));
			plantR = fromR = onGround(sh.add(crawlSide.scale(0.34)).add(crawlDir.scale(0.3)));
			plantL = fromL = onGround(sh.subtract(crawlSide.scale(0.34)).add(crawlDir.scale(0.2)));
			weak = 0;
			look(new Vec3(px, sy, pz).add(crawlDir.scale(3)), 0.3F);
			return;
		}
		int t = age - hitTick;
		BodyRig.Joints j = BodyRig.solve(crawlPose(1.0F), false);
		if (t == TACKLE_FALL - 3) {
			makeCrawler(mc, j.pelvis);
		}
		if (t == TACKLE_FALL) {
			// Down: the wind knocked out of you, face in the dirt
			inBody = true;
			fx(j.pelvis, BODY_FALL, 1.2F, 0.9F);
			level.playLocalSound(j.eyes.x, j.eyes.y, j.eyes.z, PLAYER_PAIN, SoundSource.PLAYERS, 0.9F, 0.9F, false);
			shake = Math.max(shake, 1.6F);
			redPulse = 0.8F;
			rollTarget = 0;
			kickPitch = 10;
		}
		if (inBody) {
			turnHeadBack();
		}
		crawlBody(j);
		Vec3 eyes = j.eyes;
		Vec3 ahead = eyes.add(crawlDir.scale(1.2)).add(0, -0.5, 0);
		// It comes round to your side and drops onto you, kneeling over your back
		Vec3 spot = j.pelvis.add(crawlSide.scale(0.5)).add(crawlDir.scale(0.35));
		Vec3 bite = j.shoulderR.lerp(j.neck, 0.4).add(0, 0.05, 0);
		if (t < 14) {
			main.goal = new Vec3(main.x, main.y, main.z); // stumbling to a stop over you
			main.faceAt = j.pelvis;
			main.leanTarget = 20;
		} else {
			main.goal = spot;
			main.faceAt = bite;
			main.speed = 0.06;
			if (main.arrived) {
				main.kneelTarget = 1;
				main.kneelRate = 0.15F;
			}
		}
		if (inBody) {
			crawlTick(false, t < 40 ? 1.2F : Math.max(0.3F, 1.2F - weak)); // clawing at the ground, going nowhere
		}
		boolean onYou = main.arrived && main.kneel > 0.75F;
		if (t < TACKLE_FALL + 6) {
			look(ahead, 0.25F); // stunned, face down
			darkTarget = 0.35F;
		} else if (biteStart < 0 && !onYou) {
			// You look back over your shoulder for it
			look(main.face(1.0F), 0.22F);
			darkTarget = 0.2F;
			main.leanTarget = 15;
			if (t == TACKLE_FALL + 10) {
				level.playLocalSound(main.x, main.y + 1.5, main.z, SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE, 1.0F, 0.75F, false);
			}
		} else if (biteStart < 0) {
			biteStart = age;
			// A few bites (about two and a half seconds), then it fades out
			sceneLength = Math.min(sceneLength, biteStart + 14 + 50 + 28);
		}
		if (biteStart >= 0) {
			int since = age - biteStart;
			if (since < 14) {
				// It bends in over your shoulder, snarling, you watching it come
				float in = since / 14.0F;
				main.leanTarget = 20 + 25 * in;
				main.leanRate = 0.15F;
				main.headPitch = 15 + 20 * in;
				main.headJitter = (float) Math.sin(age * 0.9) * 12;
				main.arms(-1.1F, 0.15F, -1.1F, 0.15F);
				look(main.face(1.0F), 0.25F);
				if (since == 4) {
					level.playLocalSound(main.x, main.y + 1.2, main.z, SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE, 1.1F, 0.65F, false);
				}
			} else {
				// Biting into your neck and shoulder, again and again; you go weaker, your head sinking
				int c = (since - 14) % 8;
				main.leanTarget = c < 3 ? 58 : 42;
				main.leanRate = 0.5F;
				main.headPitch = c < 3 ? 55 : 25;
				main.headJitter = (float) Math.sin(age * 2.7) * 20;
				main.arms(-1.15F, 0.2F, -1.15F, 0.2F);
				if (c == 2) {
					tear(bite, main, true);
					shake = Math.max(shake, 1.1F);
					redPulse = Math.max(redPulse, 0.7F);
					kickYaw = (float) ((RANDOM.nextDouble() - 0.5) * 8);
					if (age - lastPain > 14) {
						lastPain = age;
						level.playLocalSound(eyes.x, eyes.y, eyes.z, PLAYER_PAIN, SoundSource.PLAYERS, 0.9F * (1 - weak) + 0.1F, 0.9F, false);
					}
				}
				weak = Math.min(1.0F, weak + 0.016F);
				look(main.face(1.0F), 0.15F); // eyes on it the whole time
				darkTarget = Math.min(0.85F, 0.25F + weak * 0.6F);
			}
		}
		if (age > sceneLength - 28) {
			blackTarget = 1;
			blackRate = 0.12F;
		}
	}

	// ---------------------------------------------------------------- MISSED: sword up, shaking; you swing, it ducks, it has you

	/** It starts coming / it lunges / how close it gets before you swing. */
	private static final int MISS_COME = 22, MISS_SPOT = 58;
	private static int swingTick = -1, dodgeEnd = -1, limpFrom = -1;
	/** What you really had in your hand (the sword is put there for the scene, on this side only). */
	private static net.minecraft.world.item.ItemStack heldBefore;

	/** Whether the first-person hands draw during the cutscene (only standing with the sword, before it gets you). */
	public static boolean showsHands() {
		return active && kind == Kind.MISSED && bodyId == Integer.MIN_VALUE && age < sceneLength;
	}

	private static void giveSword(Minecraft mc) {
		if (mc.player == null) {
			return;
		}
		heldBefore = mc.player.getMainHandItem().copy();
		mc.player.setItemInHand(InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(com.mzcod.tacticalmovement.TacticalItems.MELEE));
	}

	private static void takeSword() {
		Minecraft mc = Minecraft.getInstance();
		if (heldBefore != null && mc.player != null && mc.player.getMainHandItem().is(com.mzcod.tacticalmovement.TacticalItems.MELEE)) {
			mc.player.setItemInHand(InteractionHand.MAIN_HAND, heldBefore);
		}
		heldBefore = null;
	}

	private static void missed(Minecraft mc) {
		Vec3 eyes = new Vec3(sx, sy + eyeHeight, sz);
		Vec3 you = new Vec3(sx, sy, sz);
		if (bodyId == Integer.MIN_VALUE) {
			// On your feet, sword up, shaking all over, breathing hard
			eyeHeight = 1.6 + Math.sin(age * 0.5) * 0.025;
			shake = Math.max(shake, swingTick < 0 ? 0.32F : 0.2F);
			rollTarget = (float) Math.sin(age * 0.23) * 3;
			darkTarget = 0.15F;
			main.faceAt = new Vec3(sx, sy + 1, sz);
			if (age < MISS_SPOT) {
				// Looking every way, frantic (it is coming up from where you aren't looking)
				if (glance == null || age % 10 == 0) {
					double away = Math.atan2(awayZ, awayX);
					double a = away + Math.PI * (0.4 + RANDOM.nextDouble() * 1.2) * (RANDOM.nextBoolean() ? 1 : -1);
					glance = new Vec3(sx + Math.cos(a) * 6, sy + 1.0 + RANDOM.nextDouble() * 0.9, sz + Math.sin(a) * 6);
				}
				look(glance, 0.36F);
				if (age >= MISS_COME) {
					main.goal = you;
					main.speed = 0.05;
				}
				return;
			}
			if (age == MISS_SPOT) {
				// There: you spin round to it, and it comes at you
				level.playLocalSound(main.x, main.y + 1.5, main.z, ATTACK, SoundSource.HOSTILE, 1.1F, 0.85F, false);
				shake = Math.max(shake, 0.9F);
			}
			double dx = main.x - sx, dz = main.z - sz, d = Math.sqrt(dx * dx + dz * dz);
			if (swingTick < 0) {
				look(main.face(1.0F), 0.4F);
				main.goal = you;
				main.speed = 0.11;
				main.leanTarget = 12;
				main.arms(-1.4F, 0.2F, -1.4F, 0.2F);
				if (d > 1.9 && age < MISS_SPOT + 60) {
					return;
				}
				// You swing at it, wild
				swingTick = age;
				if (mc.player != null) {
					mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
				}
				level.playLocalSound(sx, sy + 1.4, sz, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 1.1F, false);
				level.playLocalSound(sx, sy + 1.4, sz, SoundEvents.PLAYER_ATTACK_NODAMAGE, SoundSource.PLAYERS, 0.8F, 0.9F, false);
				return;
			}
			int st = age - swingTick;
			look(main.face(1.0F), 0.35F);
			if (st < 3) {
				kickYaw = -6.0F * (st + 1); // the swing drags your view round with it
			}
			if (st < 12) {
				// It ducks under the blade (dropping low, bending away to the side), then comes up at you
				Vec3 side = new Vec3(-dz, 0, dx).normalize();
				main.goal = new Vec3(main.x, main.y, main.z).add(side.scale(0.04));
				main.speed = 0.04;
				main.leanTarget = st < 8 ? 48 : 20;
				main.leanRate = 0.45F;
				main.kneelTarget = st < 8 ? 0.35F : 0;
				main.kneelRate = 0.4F;
				main.headPitch = st < 8 ? 35 : 10;
				main.arms(-0.6F, 0.4F, -0.6F, 0.4F);
				if (st == 3) {
					level.playLocalSound(main.x, main.y + 1.2, main.z, SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE, 1.0F, 0.7F, false);
				}
				return;
			}
			// Up and into you, both hands out
			main.goal = you;
			main.speed = 0.16;
			main.kneelTarget = 0;
			main.leanTarget = 15;
			main.headPitch = 0;
			main.arms(-1.6F, 0.5F, -1.6F, 0.5F);
			if (d > 0.95 && st < 34) {
				return;
			}
			// It shoves you over: down onto your back, the sword gone
			awayX = dx / Math.max(1.0E-3, d);
			awayZ = dz / Math.max(1.0E-3, d);
			fallBody(mc, 0.1);
			hitTick = age;
			main.zombie.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
			shake = 1.8F;
			redPulse = 0.8F;
			kickPitch = -12;
			fx(eyes, PUNCH, 1.2F, 0.8F);
			level.playLocalSound(sx, sy + 1, sz, SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0F, 0.7F, false);
			level.playLocalSound(main.x, main.y + 1.5, main.z, HIT_PLAYER, SoundSource.HOSTILE, 1.0F, 0.85F, false);
			takeSword();
			return;
		}
		// On top of you: it drops to its knees astride you, leans in, and bites, over and over
		Vec3 head = point(Corpses.HEAD), neck = point(Corpses.NECK);
		main.goal = neck.add(awayX * 0.35, 0, awayZ * 0.35);
		main.faceAt = head;
		main.speed = 0.15;
		main.kneelTarget = 1;
		main.kneelRate = 0.18F;
		look(main.face(1.0F), 0.3F);
		int since = age - hitTick;
		boolean onYou = since >= 22 && main.kneel > 0.75F;
		double rx = -awayZ, rz = awayX;
		Vec3 face = main.face(1.0F);
		if (!onYou && dodgeEnd < 0) {
			float in = Mth.clamp((since - 6) / 16.0F, 0, 1);
			main.leanTarget = 10 + 30 * in;
			main.leanRate = 0.15F;
			main.headPitch = 10 + 15 * in;
			main.headJitter = (float) Math.sin(age * 0.9) * 12 * in;
			darkTarget = 0.3F;
			// Your hands up against it, pushing at its face
			bodyPull(Corpses.R_HAND, face.x + rx * 0.2, face.y - 0.25, face.z + rz * 0.2, 0.25 * in);
			bodyPull(Corpses.L_HAND, face.x - rx * 0.2, face.y - 0.25, face.z - rz * 0.2, 0.25 * in);
			return;
		}
		if (dodgeEnd < 0) {
			dodgeEnd = age; // (when the biting started)
			limpFrom = age + 8 * (3 + RANDOM.nextInt(3)); // you fight it for 3-5 bites
			// A few bites (about two and a half seconds), then it fades out
			sceneLength = Math.min(sceneLength, age + 50 + 30);
		}
		// Eating your face: right down over it (as close as it can come without the camera going inside its head), the
		// head working, tearing at you
		main.goal = head.add(awayX * 0.55, 0, awayZ * 0.55);
		int c = (age - dodgeEnd) % 8;
		main.leanTarget = c < 4 ? 88 : 72;
		main.leanRate = 0.5F;
		main.headPitch = c < 4 ? 60 : 35;
		main.headJitter = (float) Math.sin(age * 2.3) * (c < 4 ? 28 : 12);
		main.arms(-1.25F, 0.2F, -1.25F, 0.2F); // its hands down on you, pinning you
		if (c == 3) {
			tear(head, main, true);
			redPulse = Math.max(redPulse, 0.7F);
			kickPitch = (float) ((RANDOM.nextDouble() - 0.5) * 8);
			kickYaw = (float) ((RANDOM.nextDouble() - 0.5) * 10);
		}
		if (age < limpFrom) {
			// Still fighting it: hands at its face, shoving, weaker with each bite
			double k = 0.3 * (1 - (age - dodgeEnd) / (double) Math.max(1, limpFrom - dodgeEnd) * 0.6);
			bodyPull(Corpses.R_HAND, face.x + rx * 0.2, face.y - 0.25, face.z + rz * 0.2, k);
			bodyPull(Corpses.L_HAND, face.x - rx * 0.2, face.y - 0.25, face.z - rz * 0.2, k);
		} else {
		// Your arms gone limp: they fall away to the ground at your sides, only twitching as it bites
		double[][] fr = bodyFrame(1.0F);
		Vec3 right = fr == null ? new Vec3(rx, 0, rz) : new Vec3(fr[1][0], fr[1][1], fr[1][2]);
		Vec3 sh = neck.lerp(point(Corpses.HIPS), 0.25);
		double gy = ground(sh.x, sh.y + 0.5, sh.z) + 0.07;
		double twitch = c == 4 ? 0.06 : 0;
		Vec3 limpR = sh.add(right.scale(0.62)), limpL = sh.subtract(right.scale(0.62));
		bodyPull(Corpses.R_HAND, limpR.x, gy + twitch, limpR.z, 0.12);
		bodyPull(Corpses.L_HAND, limpL.x, gy + twitch * 0.5, limpL.z, 0.12);
		}
		darkTarget = Math.min(0.85F, 0.3F + (age - dodgeEnd) / 70.0F);
		if (age > sceneLength - 30) {
			blackTarget = 1;
			blackRate = 0.12F;
		}
	}

	// ---------------------------------------------------------------- DISMEMBERED: arms torn off, then the head

	private static final int RIP_R = 78, TOSS_R = 102, RIP_L = 160, TOSS_L = 184, HEAD_PULL = 205, RIP_HEAD = 246;

	private static void dismembered(Minecraft mc) {
		Vec3 head = point(Corpses.HEAD), neck = point(Corpses.NECK), hips = point(Corpses.HIPS);
		Vec3 handR = point(Corpses.R_HAND), handL = point(Corpses.L_HAND);
		double[][] frame = bodyFrame(1.0F);
		Vec3 up = frame == null ? new Vec3(0, 1, 0) : new Vec3(frame[0][0], frame[0][1], frame[0][2]);
		Vec3 right = frame == null ? new Vec3(-awayZ, 0, awayX) : new Vec3(frame[1][0], frame[1][1], frame[1][2]);
		Vec3 shoulderR = neck.add(right.scale(0.32)).subtract(up.scale(0.12));
		Vec3 shoulderL = neck.subtract(right.scale(0.32)).subtract(up.scale(0.12));

		if (age < TOSS_R) {
			// The right arm: kneels beside it, grabs it and pulls, harder and harder, until it comes off
			tearArm(Corpses.R_HAND, handR, shoulderR, right, RIP_R, true);
			lookAt(handR.lerp(main.face(1.0F), 0.3), 0.1F);
		} else if (age < TOSS_L) {
			if (age == TOSS_R) {
				toss();
			}
			tearArm(Corpses.L_HAND, handL, shoulderL, right.scale(-1), RIP_L, false);
			lookAt(age < TOSS_R + 18 ? main.face(1.0F) : handL.lerp(main.face(1.0F), 0.3), 0.08F);
		} else {
			if (age == TOSS_L) {
				toss();
			}
			// The head: it kneels behind it, takes it in both hands and pulls it slowly off
			main.goal = head.add(-awayX * 0.85, 0, -awayZ * 0.85);
			main.faceAt = head;
			main.speed = 0.07;
			if (main.arrived || age > HEAD_PULL) {
				main.kneelTarget = headOff ? 0.3F : 1;
				main.leanTarget = headOff ? 8 : 34;
				main.leanRate = 0.12F;
			}
			if (age >= HEAD_PULL && !headOff) {
				// Slowly up and away from the neck: the whole body comes with it, straining
				double u = Mth.clamp((age - HEAD_PULL) / (double) (RIP_HEAD - HEAD_PULL), 0, 1);
				Vec3 h = main.hands();
				Vec3 to = head.add(up.scale(0.15 + u * 0.35)).lerp(h, 0.3);
				double j = (RANDOM.nextDouble() - 0.5) * 0.06 * u;
				bodyPull(Corpses.HEAD, to.x + j, to.y + j, to.z + j, 0.35);
				shake = Math.max(shake, (float) (0.3 + u * 1.2));
				darkTarget = (float) (0.2 + u * 0.55);
				if (age % 6 == 0) {
					gush(neck.lerp(head, 0.5), up, 2 + (int) (u * 4));
				}
				if (age == HEAD_PULL + 4 || age == HEAD_PULL + 24) {
					level.playLocalSound(head.x, head.y, head.z, PLAYER_PAIN, SoundSource.PLAYERS, 0.9F, 0.6F, false);
				}
				if ((age - HEAD_PULL) % 14 == 0) {
					fx(neck, STRAIN, 0.8F + (float) u * 0.7F, 1.0F - (float) u * 0.3F);
				}
			}
			if (age == RIP_HEAD) {
				// Off: the camera goes with the head, in its hands, looking down at the body
				headOff = true;
				inBody = false;
				headCam = headCamOld = head;
				white = whiteOld = 1.0F;
				setGrey(true);
				shake = 2.5F;
				fx(neck, FLESH_TEAR, 1.5F, 0.75F);
				fx(neck, BONE_SNAP, 1.5F, 0.8F);
				fx(neck, BLOOD_GUSH, 1.2F, 0.9F);
				level.playLocalSound(head.x, head.y, head.z, HIT_PLAYER, SoundSource.HOSTILE, 1.0F, 0.5F, false);
				inHead(FLASHBACK, 1.0F, 1.0F);
				inHead(HEARTBEAT, 0.9F, 0.7F);
			}
			if (headOff) {
				headCam = headCam.lerp(main.hands().add(0, 0.15, 0), 0.25);
				gush(neck, up, 3);
				look(neck, 0.4F);
				// The flashback: grey cuts back to standing there, white flicker, then black
				int t = age - RIP_HEAD;
				flashCut = t >= 4 && t < 9 ? 1 : t >= 14 && t < 18 ? 2 : 0;
				if (t == 4 || t == 14) {
					white = Math.max(white, 0.7F);
				}
				if (t >= 24) {
					blackTarget = 1;
					blackRate = 0.35F;
				}
			} else if (age > TOSS_L + 10) {
				lookAt(main.face(1.0F), 0.08F);
			}
		}

		// The held arm swings in its hands; a thrown one lies where it fell
		if (heldArm != null) {
			Vec3 h = main.hands();
			placeProp(heldArm, h, main.zombie.yBodyRot);
			heldArm.armX = (float) Math.sin(age * 0.35) * 0.4F;
		}
		// The others watch from close by
		for (Walker w : walkers) {
			if (w != main) {
				int i = walkers.indexOf(w);
				double a = Math.atan2(awayZ, awayX) + (i == 1 ? 0.7 : -0.7);
				w.goal = hips.add(Math.cos(a) * 1.8, 0, Math.sin(a) * 1.8);
				w.faceAt = hips;
				w.leanTarget = w.arrived ? 20 : 6;
			}
		}
		if (age > 130 && !headOff) {
			darkTarget = Math.max(darkTarget, Math.min(0.5F, (age - 130) / 150.0F));
		}
	}

	/** Grabs an arm and pulls it off at `rip` (the arm is then held up). */
	private static void tearArm(int hand, Vec3 at, Vec3 shoulder, Vec3 outward, int rip, boolean first) {
		Vec3 out = new Vec3(outward.x, 0, outward.z);
		if (out.lengthSqr() < 1.0E-4) {
			out = new Vec3(-awayZ, 0, awayX);
		}
		out = out.normalize();
		if (age < rip) {
			main.goal = shoulder.add(out.scale(1.25));
			main.faceAt = shoulder;
			main.speed = first ? 0.12 : 0.07;
			if (!main.arrived) {
				main.leanTarget = 8;
				main.kneelTarget = 0;
				return;
			}
			main.kneelTarget = 1;
			main.leanTarget = 26;
			main.leanRate = 0.2F;
			// Pulling: the arm drawn out and up, jerked about, harder and harder
			double u = Mth.clamp((age - (rip - 40)) / 40.0, 0, 1);
			Vec3 h = main.hands();
			double j = (RANDOM.nextDouble() - 0.5) * (0.05 + u * 0.15);
			Vec3 to = h.add(out.scale(u * 0.35)).add(0, u * 0.2, 0);
			bodyPull(hand, to.x + j, to.y + j, to.z - j, 0.45);
			shake = Math.max(shake, (float) (0.2 + u * 0.8));
			if (age == rip - 30 || age == rip - 12) {
				level.playLocalSound(at.x, at.y, at.z, PLAYER_PAIN, SoundSource.PLAYERS, 0.8F, 0.9F, false);
			}
			if (main.arrived && age % 16 == 0) {
				fx(at, STRAIN, 0.6F + (float) u * 0.6F, 1.1F - (float) u * 0.2F);
			}
			return;
		}
		if (age == rip) {
			// It comes off: the arm in its hands, blood pouring out of the shoulder
			gone |= first ? GONE_R_ARM : GONE_L_ARM;
			CutsceneClone arm = CutsceneClone.of(Minecraft.getInstance());
			arm.setId(nextId--);
			arm.setSilent(true);
			arm.noPhysics = true;
			arm.setNoGravity(true);
			Vec3 h = main.hands();
			arm.snapTo(h.x, h.y - 1.375, h.z, main.zombie.yBodyRot, 0.0F);
			level.addEntity(arm);
			heldArm = new Prop(arm, first);
			props.put(arm.getId(), heldArm);
			shake = 2.2F;
			redPulse = 1.0F;
			BloodFx.screenSplat();
			BloodFx.screenSplat();
			level.playLocalSound(shoulder.x, shoulder.y, shoulder.z, HIT_PLAYER, SoundSource.HOSTILE, 1.2F, 0.6F, false);
			fx(shoulder, FLESH_TEAR, 1.4F, 0.9F);
			fx(shoulder, BONE_SNAP, 1.3F, 1.0F);
			fx(shoulder, BLOOD_GUSH, 1.0F, 1.0F);
			level.playLocalSound(shoulder.x, shoulder.y, shoulder.z, PLAYER_PAIN, SoundSource.PLAYERS, 1.0F, 0.7F, false);
			flinch = 6;
			flinchAt = shoulder;
		}
		// Stands up with it, holding it up
		main.kneelTarget = 0;
		main.leanTarget = 0;
		main.headPitch = 0;
		if (age < rip + 24) {
			gush(shoulder, out, 3);
		}
	}

	/** The held arm is thrown down: it lies on the ground in front of the zombie. */
	private static void toss() {
		if (heldArm == null) {
			return;
		}
		double yaw = Math.toRadians(main.zombie.yBodyRot);
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);
		double px = main.x + fx * 0.9, pz = main.z + fz * 0.9;
		heldArm.armX = -(float) Math.PI / 2; // straight out along the ground
		placeProp(heldArm, new Vec3(px, ground(px, main.y + 0.6, pz) + 0.12, pz), main.zombie.yBodyRot + 60);
		fx(new Vec3(px, main.y, pz), SPLAT, 1.0F, 1.0F);
		heldArm = null;
	}

	/** Puts a torn-off arm so its shoulder end is at `pivot`. */
	private static void placeProp(Prop prop, Vec3 pivot, float yawDeg) {
		double yaw = Math.toRadians(yawDeg);
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw); // the copy's right
		double side = prop.right ? 0.3125 : -0.3125;
		Vec3 pos = new Vec3(pivot.x - rx * side, pivot.y - 1.375, pivot.z - rz * side);
		prop.entity.moveOrInterpolateTo(pos, yawDeg, 0.0F);
		prop.entity.yBodyRot = yawDeg;
		prop.entity.yBodyRotO = yawDeg;
		prop.entity.setYHeadRot(yawDeg);
	}

	/** Blood pouring out of a wound, sprayed along `dir`. */
	private static void gush(Vec3 at, Vec3 dir, int drops) {
		BlockParticleOption blood = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState());
		for (int i = 0; i < drops; i++) {
			level.addParticle(blood, at.x, at.y + 0.05, at.z, dir.x * 0.12 + (RANDOM.nextDouble() - 0.5) * 0.1, 0.12 + RANDOM.nextDouble() * 0.12,
				dir.z * 0.12 + (RANDOM.nextDouble() - 0.5) * 0.1);
		}
		if (RANDOM.nextInt(4) == 0) {
			BloodFx.spray(at);
		}
	}

	/** Look somewhere, unless a bite has your eyes just now. */
	private static void lookAt(Vec3 want, float rate) {
		if (flinch > 0 && flinchAt != null) {
			want = flinchAt;
			rate = 0.2F;
		}
		look(want, rate);
	}

	/** The screen in grey (the flashback), using the same effect as being downed. */
	private static void setGrey(boolean on) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || grey == on) {
			grey = on && mc.player != null;
			return;
		}
		grey = on;
		mc.player.setActivePostEffects(on ? List.of(TacticalMovement.id("grayscale_9")) : List.of());
	}

	// ---------------------------------------------------------------- BEATEN: punched down, then beaten in the face

	private static final int PUNCH_1 = 30, PUNCH_2 = 52, BEAT = 80, FINAL = 226;
	/** Arm angles (model xRot): pulled back for a hook, thrown forward; raised over its head, driven down; hovering. */
	private static final float HOOK_BACK = 0.5F, HOOK = -1.75F, GUARD = -1.25F, RAISED = -2.9F, DOWN = -1.4F, HOVER = -1.85F;

	private static void beaten(Minecraft mc) {
		if (bodyId == Integer.MIN_VALUE) {
			// Standing: it walks right up and swings, twice
			main.goal = new Vec3(sx + awayX * 0.95, sy, sz + awayZ * 0.95);
			main.faceAt = new Vec3(sx, sy + 1.6, sz);
			main.leanTarget = 8;
			look(main.face(1.0F), 0.3F);
			boolean right = age <= PUNCH_1;
			int hitAt = right ? PUNCH_1 : PUNCH_2;
			float swing = age >= hitAt - 9 && age < hitAt - 2 ? HOOK_BACK : age >= hitAt - 2 && age < hitAt + 4 ? HOOK : GUARD;
			float rate = swing == HOOK ? 0.85F : 0.3F;
			if (right) {
				main.arms(swing, rate, GUARD, 0.2F);
			} else {
				main.arms(GUARD, 0.2F, swing, rate);
			}
			if (age > PUNCH_1) {
				eyeHeight += (1.42 - eyeHeight) * 0.25; // staggered, half bent over
			}
			if (age == PUNCH_1) {
				punchImpact(true, new Vec3(sx, sy + eyeHeight, sz), main.face(1.0F), 1.0F);
				rollTarget = -10;
			} else if (age == PUNCH_2) {
				punchImpact(false, new Vec3(sx, sy + eyeHeight, sz), main.face(1.0F), 1.3F);
				// Down you go, onto your back
				fallBody(mc, 0.18);
				rollTarget = 0;
				blackTarget = 0.85F;
				blackRate = 0.6F;
				blink = 5;
			}
			return;
		}
		Vec3 head = point(Corpses.HEAD), hips = point(Corpses.HIPS);
		double tx = hips.x - head.x, tz = hips.z - head.z, tl = Math.sqrt(tx * tx + tz * tz);
		if (tl < 1.0E-3) {
			tx = awayX;
			tz = awayZ;
			tl = 1;
		}
		tx /= tl;
		tz /= tl;
		// It climbs on top of you, knees either side of your chest, facing your face
		main.goal = head.add(tx * 1.0, 0, tz * 1.0);
		main.faceAt = head;
		main.speed = 0.08;
		main.headPitch = 35;
		if (age > PUNCH_2 + 14 && main.arrived) {
			main.kneelTarget = 1;
		}
		if (blink > 0) {
			blink--;
		}
		blackTarget = blink > 0 ? 0.85F : (float) (0.05 + swell * 0.2 + (age > FINAL - 30 ? 0.15 : 0));
		blackRate = blink > 0 ? 0.6F : 0.25F;
		darkTarget = 0.15F + swell * 0.5F;
		lookAt(main.face(1.0F), 0.12F);
		if (nextPunch == Integer.MAX_VALUE) {
			// The last blow landed: arms down, it kneels there over you
			main.arms(HOVER, 0.15F, HOVER, 0.15F);
			main.leanTarget = 30;
			return;
		}
		if (age < BEAT || main.kneel < 0.6F) {
			main.arms(HOVER, 0.2F, HOVER, 0.2F);
			main.leanTarget = 20;
			// Dazed, you get your hands up a little
			Vec3 face = main.face(1.0F);
			Vec3 guard = head.lerp(face, 0.25);
			bodyPull(Corpses.R_HAND, guard.x + tz * 0.2, guard.y, guard.z - tx * 0.2, 0.08);
			bodyPull(Corpses.L_HAND, guard.x - tz * 0.2, guard.y, guard.z + tx * 0.2, 0.08);
			return;
		}
		if (nextPunch == 0) {
			nextPunch = age + 7;
		}
		// Over and over: one fist raised high, then driven down into your face; the other holds you down
		boolean right = punchHand == 0;
		boolean striking = nextPunch - age <= 2;
		float arm = striking ? DOWN : RAISED;
		float rate = striking ? 0.85F : 0.4F;
		if (right) {
			main.arms(arm, rate, HOVER, 0.25F);
		} else {
			main.arms(HOVER, 0.25F, arm, rate);
		}
		main.leanTarget = striking ? 40 : 22;
		main.leanRate = striking ? 0.7F : 0.3F;
		if (age < BEAT + 60) {
			// Your hands still try to cover your face (then they give up)
			Vec3 guard = head.lerp(main.face(1.0F), 0.2);
			bodyPull(right ? Corpses.L_HAND : Corpses.R_HAND, guard.x, guard.y, guard.z, 0.05);
		}
		if (age >= nextPunch) {
			boolean last = age >= FINAL;
			Vec3 face = main.face(1.0F);
			Vec3 toZombie = face.subtract(head).normalize();
			punchImpact(right, head, face, last ? 1.6F : 1.0F);
			// Your head is knocked aside into the ground
			double side = right ? 1 : -1;
			bodyPull(Corpses.HEAD, head.x + tz * 0.3 * side, head.y - 0.06, head.z - tx * 0.3 * side, 0.7);
			bodyPull(Corpses.HIPS, hips.x, hips.y + 0.08, hips.z, 0.5);
			swell = Math.min(1.0F, swell + 0.045F);
			if (swell > 0.35F && RANDOM.nextInt(3) == 0) {
				blink = 2 + RANDOM.nextInt(3); // the lights go out for a moment
			}
			if (last) {
				white = Math.max(white, 0.9F);
				blink = 0;
				blackTarget = 1;
				nextPunch = Integer.MAX_VALUE;
				fx(head.add(toZombie.scale(0.4)), BONE_SNAP, 1.4F, 0.7F);
				return;
			}
			punchHand ^= 1;
			int beat = age - BEAT;
			int period = age > FINAL - 40 ? 6 : Math.max(7, 15 - beat / 12);
			nextPunch = age + period + RANDOM.nextInt(3);
		}
	}

	/** A punch lands on your face at `head` (the fist coming from `from`): the screen bursts, the camera is knocked aside. */
	private static void punchImpact(boolean right, Vec3 head, Vec3 from, float power) {
		Vec3 toZombie = from.subtract(head).normalize();
		Vec3 hit = head.add(toZombie.scale(0.45));
		// In the world: sparks of the blow, blood, now and then a tooth
		for (int i = 0; i < 10; i++) {
			level.addParticle(ParticleTypes.CRIT, hit.x, hit.y, hit.z, (RANDOM.nextDouble() - 0.5) * 0.7, (RANDOM.nextDouble() - 0.3) * 0.5,
				(RANDOM.nextDouble() - 0.5) * 0.7);
		}
		BlockParticleOption blood = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState());
		for (int i = 0; i < 5 + swell * 6; i++) {
			level.addParticle(blood, hit.x, hit.y, hit.z, (RANDOM.nextDouble() - 0.5) * 0.3, 0.1 + RANDOM.nextDouble() * 0.2,
				(RANDOM.nextDouble() - 0.5) * 0.3);
		}
		if (RANDOM.nextInt(4) == 0) {
			BlockParticleOption tooth = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.BONE_BLOCK.defaultBlockState());
			level.addParticle(tooth, hit.x, hit.y, hit.z, (RANDOM.nextDouble() - 0.5) * 0.3, 0.25, (RANDOM.nextDouble() - 0.5) * 0.3);
		}
		if (RANDOM.nextInt(10) < 3 + swell * 6) {
			BloodFx.screenSplat();
		}
		if (RANDOM.nextInt(3) == 0) {
			BloodFx.spray(hit);
		}
		// On screen: the burst where the fist lands, the camera knocked the other way
		double side = right ? 1 : -1;
		impactTick = age;
		impactX = (float) (0.5 - side * 0.1 + (RANDOM.nextDouble() - 0.5) * 0.1);
		impactY = (float) (0.5 + RANDOM.nextDouble() * 0.12);
		impactScale = power;
		kickYaw = (float) (side * (14 + RANDOM.nextDouble() * 8) * power);
		kickYawOld = kickYaw * 0.3F;
		kickPitch = (float) (-(4 + RANDOM.nextDouble() * 5) * power);
		roll += (float) (side * 12 * power);
		shake = Math.max(shake, 1.6F * power);
		redPulse = Math.max(redPulse, 0.85F);
		white = Math.max(white, 0.3F * power);
		// The sound of it
		fx(hit, FACE_PUNCH, 1.3F, 1.0F);
		if (RANDOM.nextInt(5) < 2 || power > 1.2F) {
			// The zombie snarls as it swings (from its mouth, not your face)
			Vec3 mouth = main.face(1.0F);
			level.playLocalSound(mouth.x, mouth.y, mouth.z, ATTACK, SoundSource.HOSTILE, 1.0F, 0.85F + RANDOM.nextFloat() * 0.3F, false);
		}
		if (power > 1.2F) {
			fx(hit, PUNCH, 1.2F, 0.85F);
		}
		if (RANDOM.nextInt(10) < 3) {
			fx(hit, BONE_SNAP, 0.6F, 1.3F);
		}
		if (age - lastPain > 30 && RANDOM.nextInt(2) == 0) {
			lastPain = age;
			level.playLocalSound(head.x, head.y, head.z, PLAYER_PAIN, SoundSource.PLAYERS, 0.7F, 0.7F + RANDOM.nextFloat() * 0.15F, false);
		}
	}

	// ---------------------------------------------------------------- your body, alive

	/** Where each hand is flailing toward (a new spot every so often). */
	private static Vec3 flailR, flailL;

	/** How hard you fight, scene by scene (the scenes that animate the body themselves: 0). */
	private static float struggleLevel() {
		return switch (kind) {
			case PINNED -> 0.8F * (1.0F - age / (float) sceneLength);
			case OVERWHELMED -> 0.7F;
			case DRAGGED -> age < WAKE ? 0.0F : age < DROP ? 0.45F : 0.6F;
			case DISMEMBERED -> 0.65F;
			case BEATEN -> 0.55F * (1.0F - swell);
			case AMBUSHED -> 0.45F;
			case MISSED -> limpFrom >= 0 && age >= limpFrom ? 0.0F : 0.4F; // limp after a few bites
			default -> 0.0F;
		};
	}

	/** Struggling on the ground: legs kicking in turn (knees drawn up), hands flailing and grabbing, hips squirming. */
	private static void struggle(float k) {
		if (k <= 0.01F) {
			return;
		}
		double[][] fr = bodyFrame(1.0F);
		if (fr == null) {
			return;
		}
		Vec3 right = new Vec3(fr[1][0], fr[1][1], fr[1][2]);
		Vec3 hips = point(Corpses.HIPS), neck = point(Corpses.NECK);
		// Bursts: fighting hard, then a moment's weakness
		k *= 0.6F + 0.4F * (float) Math.sin(age * 0.11);
		boolean legs = !(kind == Kind.DRAGGED && age < DROP); // its hands have your ankles
		if (legs) {
			for (int f = 0; f < 2; f++) {
				int foot = f == 0 ? Corpses.R_FOOT : Corpses.L_FOOT;
				double lift = Math.max(0, Math.sin(age * 0.33 + f * Math.PI)) * k;
				if (lift > 0.05) {
					Vec3 cur = point(foot);
					Vec3 to = new Vec3(Mth.lerp(lift * 0.5, cur.x, hips.x), hips.y + 0.05 + lift * 0.35, Mth.lerp(lift * 0.5, cur.z, hips.z));
					bodyPull(foot, to.x, to.y, to.z, 0.25 * k);
				}
			}
		}
		if (flailR == null || age % 9 == 0) {
			flailR = neck.add(right.scale(0.3 + RANDOM.nextDouble() * 0.3)).add(0, 0.2 + RANDOM.nextDouble() * 0.4, 0)
				.add((RANDOM.nextDouble() - 0.5) * 0.4, 0, (RANDOM.nextDouble() - 0.5) * 0.4);
		}
		if (flailL == null || age % 9 == 4) {
			flailL = neck.add(right.scale(-0.3 - RANDOM.nextDouble() * 0.3)).add(0, 0.2 + RANDOM.nextDouble() * 0.4, 0)
				.add((RANDOM.nextDouble() - 0.5) * 0.4, 0, (RANDOM.nextDouble() - 0.5) * 0.4);
		}
		bodyPull(Corpses.R_HAND, flailR.x, flailR.y, flailR.z, 0.12 * k);
		bodyPull(Corpses.L_HAND, flailL.x, flailL.y, flailL.z, 0.12 * k);
		double sq = Math.sin(age * 0.27) * 0.04 * k;
		bodyPull(Corpses.HIPS, hips.x + right.x * sq, hips.y, hips.z + right.z * sq, 0.3);
	}

	// ---------------------------------------------------------------- CRAWLING: crawling away, a leg broken, an arm, left to die

	private static Vec3 onGround(Vec3 p) {
		return new Vec3(p.x, ground(p.x, p.y + 0.6, p.z) + 0.06, p.z);
	}

	private static double smooth(double x) {
		x = Mth.clamp(x, 0, 1);
		return x * x * (3 - 2 * x);
	}

	/**
	 * The crawling body this frame. Each pull: one hand reaches out ahead and grips (the first 40%), then you drag yourself up
	 * to it (the rest): chest heaving up off the ground, hips rolling toward that arm, the knee on that side drawn up along the
	 * ground and shoving back, the other leg dragging, the other hand pushing off where it gripped last time.
	 */
	private static BodyRig.Pose crawlPose(float pt) {
		float t = age + pt;
		double u = Mth.clamp((t - crawlStart) / crawlPeriod, 0, 1);
		double reachU = Mth.clamp(u / 0.4, 0, 1), pullU = Mth.clamp((u - 0.4) / 0.6, 0, 1);
		double pullArc = Math.sin(pullU * Math.PI);
		double side = crawlRight ? 1 : -1;
		float k = 1.0F - weak;
		BodyRig.Pose p = new BodyRig.Pose();
		Vec3 down = new Vec3(0, -1, 0);
		float lb = Mth.lerp(pt, lookBackOld, lookBack);
		lb = lb * lb * (3 - 2 * lb);
		p.up = crawlDir;
		// Rolled up onto the hip on the side you look back over (and rocking with the pull otherwise)
		p.front = BodyRig.rot(down, crawlDir, side * 0.14 * Math.sin(u * Math.PI) * k * (1 - lb) - lookSide * 0.3 * lb);
		double dist = crawlDist + crawlStride * smooth(pullU);
		p.pelvis = crawlOrigin.add(crawlDir.scale(dist)).add(0, 0.13 + 0.03 * pullArc * k, 0);
		p.bend = (float) ((-0.3 * pullArc * k - 0.06 * k) * (1 - lb) - 0.18 * lb);
		// Chest twisted round, head turned right back over the shoulder
		p.twist = (float) (-lookSide * 0.2 * lb); // (a + twist turns the chest to the left)
		p.headYaw = (float) (lookSide * 1.35 * lb);
		p.headPitch = (float) ((-0.95 + 0.75 * weak) * (1 - lb) - 0.3 * lb);
		p.hideHead = !debugView;
		Vec3 right = p.right();
		// Hands: the reaching one lifts and goes out to its new grip; the other stays where it grips
		Vec3 hr = plantR, hl = plantL;
		if (reachU < 1) {
			double e = smooth(reachU);
			Vec3 h = (crawlRight ? fromR : fromL).lerp(crawlRight ? plantR : plantL, e).add(0, Math.sin(reachU * Math.PI) * 0.2 * k, 0);
			if (crawlRight) {
				hr = h;
			} else {
				hl = h;
			}
		}
		p.elbowR = right.add(crawlDir.scale(-0.6)).add(0, 0.6, 0);
		p.elbowL = right.scale(-1).add(crawlDir.scale(-0.6)).add(0, 0.6, 0);
		Vec3 shoulderR = p.pelvis.add(crawlDir.scale(0.7)).add(right.scale(0.375));
		if (heldHand != null) {
			hr = heldHandOld.lerp(heldHand, pt);
			p.elbowR = new Vec3(0, -1, 0);
		} else if (armBroken) {
			// Wrenched up behind your back, the elbow the wrong way
			hr = shoulderR.add(crawlDir.scale(-0.3)).add(right.scale(0.08)).add(0, 0.42, 0);
			p.elbowR = crawlDir.add(right.scale(0.8));
		}
		p.handR = hr;
		p.handL = hl;
		// Legs: the knee on the reaching side drawn up and out along the ground, then shoving back; the other dragging
		double draw = (reachU < 1 ? smooth(reachU) : 1 - smooth(pullU)) * k;
		Vec3 hipR = p.pelvis.add(right.scale(0.125)), hipL = p.pelvis.subtract(right.scale(0.125));
		for (int f = 0; f < 2; f++) {
			boolean isRight = f == 0;
			double fs = isRight ? 1 : -1;
			Vec3 hip = isRight ? hipR : hipL;
			boolean frog = fs == side && !(isRight && legBroken);
			Vec3 foot = frog
				? hip.add(right.scale(fs * (0.08 + 0.3 * draw))).add(crawlDir.scale(-0.72 + 0.38 * draw)).add(0, -0.07, 0)
				: hip.add(right.scale(fs * 0.04)).add(crawlDir.scale(-0.74)).add(0, -0.07, 0);
			Vec3 pole = frog ? right.scale(fs).add(0, -0.25, 0) : new Vec3(0, -1, 0);
			if (isRight) {
				p.footR = foot;
				p.kneeR = pole;
			} else {
				p.footL = foot;
				p.kneeL = pole;
			}
		}
		if (heldFoot != null) {
			p.footR = heldFootOld.lerp(heldFoot, pt);
			p.kneeR = new Vec3(0, -1, 0);
		} else if (legBroken) {
			// Snapped at the knee: the shin folded out sideways
			p.footR = hipR.add(right.scale(0.55)).add(crawlDir.scale(-0.32)).add(0, -0.07, 0);
			p.kneeR = crawlDir.scale(-1).add(right.scale(-0.4));
		}
		return p;
	}

	/** Starts the next pull when this one is done: which hand, how far (not into a wall), where that hand will grip. */
	private static void crawlTick(boolean move, float strength) {
		if (age - crawlStart < crawlPeriod) {
			return;
		}
		crawlDist += crawlStride;
		crawlStart = age;
		crawlPeriod = armBroken ? 46 : legBroken ? 34 : 26;
		crawlRight = legBroken ? !armBroken : !crawlRight;
		double stride = move ? (legBroken ? 0.22 : 0.32) * strength : 0;
		Vec3 pel = crawlOrigin.add(crawlDir.scale(crawlDist));
		Vec3 headAt = pel.add(crawlDir.scale(1.1)).add(0, 0.3, 0);
		if (stride > 0 && blocked(headAt, crawlDir.x * (stride + 0.3), crawlDir.z * (stride + 0.3))) {
			stride = 0; // a wall: you claw at it, getting nowhere
		}
		crawlStride = stride;
		double side = crawlRight ? 1 : -1;
		double out = 0.55 + 0.25 * Math.min(1.0, strength);
		Vec3 grip = onGround(pel.add(crawlDir.scale(stride + out + 0.45)).add(crawlSide.scale(side * 0.28)));
		if (crawlRight) {
			fromR = plantR;
			plantR = grip;
		} else {
			fromL = plantL;
			plantL = grip;
		}
		Vec3 hips = pel.add(0, 0.2, 0);
		if (stride > 0) {
			fx(hips, DRAG, 0.45F, 1.3F);
		}
		if (age - lastPain > 40 && RANDOM.nextInt(2) == 0) {
			lastPain = age;
			level.playLocalSound(hips.x, hips.y, hips.z, PLAYER_PAIN, SoundSource.PLAYERS, 0.4F * Math.min(1.0F, strength) + 0.1F, 0.95F, false);
		}
	}

	/** A bone snaps: the crack, the scream, the jolt. */
	private static void snapBone(Vec3 at, Vec3 head) {
		fx(at, BONE_SNAP, 1.7F, 0.9F);
		fx(at, FLESH_TEAR, 0.9F, 1.1F);
		level.playLocalSound(head.x, head.y, head.z, PLAYER_PAIN, SoundSource.PLAYERS, 1.2F, 0.7F, false);
		lastPain = age;
		BloodFx.screenSplat();
		gush(at, new Vec3(0, 1, 0), 6);
		shake = 2.2F;
		redPulse = 1.0F;
		kickPitch = -10;
		kickYaw = (float) ((RANDOM.nextDouble() - 0.5) * 16);
	}

	private static boolean near(Walker w, Vec3 goal) {
		double dx = w.x - goal.x, dz = w.z - goal.z;
		return dx * dx + dz * dz < 0.35 * 0.35;
	}

	private static void crawling(Minecraft mc) {
		heldFootOld = heldFoot;
		heldHandOld = heldHand;
		lookBackOld = lookBack;
		turnHeadBack();
		BodyRig.Joints j = BodyRig.solve(crawlPose(1.0F), false);
		crawlBody(j);
		crawlingScene(mc, j);
	}

	/** Lying face down: how far your head (and chest) turns back over a shoulder, from where you are looking. */
	private static void turnHeadBack() {
		if (look != null) {
			Vec3 pel = crawlOrigin.add(crawlDir.scale(crawlDist));
			Vec3 lv = look.subtract(pel.add(crawlDir.scale(0.9)));
			double lh = Math.sqrt(lv.x * lv.x + lv.z * lv.z);
			if (lh > 1.0E-3) {
				double fwd = (lv.x * crawlDir.x + lv.z * crawlDir.z) / lh, sd = (lv.x * crawlSide.x + lv.z * crawlSide.z) / lh;
				float target = (float) Mth.clamp((0.35 - fwd) / 1.1, 0, 1) * (kind == Kind.TACKLED ? 1 : 1 - weak);
				if (target > 0.05F && Math.abs(sd) > 0.15) {
					lookSide = sd > 0 ? 1 : -1;
				}
				lookBack += (target - lookBack) * 0.18F;
			}
		}
	}

	/** The face-down body's entity goes with the body (light, sounds, being drawn). */
	private static void crawlBody(BodyRig.Joints j) {
		if (crawler == null) {
			return;
		}
		crawler.xo = crawler.getX();
		crawler.yo = crawler.getY();
		crawler.zo = crawler.getZ();
		crawler.setPos(j.pelvis.x, j.pelvis.y, j.pelvis.z);
	}

	/** A face-down body for you (drawn by crawlPose). */
	private static void makeCrawler(Minecraft mc, Vec3 at) {
		crawler = CutsceneClone.of(mc);
		crawler.setId(nextId--);
		crawler.setSilent(true);
		crawler.noPhysics = true;
		crawler.setNoGravity(true);
		crawler.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
		level.addEntity(crawler);
		BodyRig.attach(crawler.getId(), GameOverCutscene::crawlPose);
	}

	private static void crawlingScene(Minecraft mc, BodyRig.Joints j) {
		Vec3 eyes = j.eyes, hips = j.pelvis, dir = crawlDir;
		Vec3 feet = j.footR.lerp(j.footL, 0.5);
		Vec3 ahead = eyes.add(dir.scale(1.0)).add(0, -0.45, 0);
		if (legTick < 0) {
			// Crawling for your life; it follows, closing in; you keep looking back at it
			crawlTick(true, 1.0F);
			Vec3 goal = feet.subtract(dir.scale(0.9));
			if (age >= 15) {
				main.goal = goal;
				main.faceAt = feet;
				main.speed = 0.04;
				main.leanTarget = 10;
			}
			double zd = main.zombie.position().distanceTo(feet);
			boolean back = (age >= 45 && age < 62) || (age >= 95 && age < 110) || (zd < 1.8 && age % 30 < 12);
			look(back ? main.face(1.0F) : ahead, back ? 0.2F : 0.15F);
			darkTarget = 0.15F;
			if ((age > 20 && near(main, goal)) || age >= 170) {
				legTick = age;
				heldFoot = heldFootOld = j.footR;
			}
			return;
		}
		int lt = age - legTick;
		if (!legBroken) {
			// It drops to its knees, grabs your leg, lifts it and twists it round
			main.goal = feet.subtract(dir.scale(0.75));
			main.faceAt = j.footR;
			main.speed = 0.03;
			main.kneelTarget = 1;
			main.kneelRate = 0.15F;
			main.leanTarget = 32;
			main.leanRate = 0.2F;
			main.arms(-1.0F, 0.2F, -1.0F, 0.2F);
			main.armIn += (0.3F - main.armIn) * 0.2F;
			double tw = Mth.clamp((lt - 14) / 18.0, 0, 1);
			Vec3 grip = main.hands().add(crawlSide.scale(tw * 0.35)).add(0, tw * 0.12, 0);
			heldFoot = lt < 6 ? heldFoot.lerp(grip, 0.3) : grip;
			crawlTick(false, 1.3F); // clawing at the ground, going nowhere
			look(lt < 8 ? main.face(1.0F) : j.footR, 0.2F);
			shake = Math.max(shake, 0.2F + (float) tw * 0.8F);
			if (lt == 6) {
				Vec3 m = main.face(1.0F);
				level.playLocalSound(m.x, m.y, m.z, ATTACK, SoundSource.HOSTILE, 1.2F, 0.8F, false);
				level.playLocalSound(eyes.x, eyes.y, eyes.z, PLAYER_PAIN, SoundSource.PLAYERS, 0.8F, 0.9F, false);
			}
			if (lt == 16) {
				fx(j.footR, STRAIN, 0.9F, 1.0F);
			}
			if (lt == 32) {
				snapBone(j.kneeR, eyes);
				legBroken = true;
				heldFoot = heldFootOld = null;
			}
			return;
		}
		if (armTick < 0) {
			// It lets go and gets up; you drag yourself on with one arm, the leg trailing; it comes round to that arm
			int since = lt - 32;
			crawlTick(true, 0.8F);
			main.kneelTarget = since < 14 ? 1 : 0;
			main.kneelRate = 0.12F;
			main.leanTarget = 12;
			main.arms(-0.6F, 0.15F, -0.6F, 0.15F);
			main.armIn *= 0.8F;
			Vec3 goal = j.handR.add(crawlSide.scale(0.85)).add(dir.scale(0.2));
			if (since >= 18) {
				main.goal = goal;
				main.faceAt = j.handR;
				main.speed = 0.045;
			}
			look(since < 20 ? j.footR : main.face(1.0F), 0.15F);
			if (since == 24) {
				Vec3 m = main.face(1.0F);
				level.playLocalSound(m.x, m.y, m.z, SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE, 1.0F, 0.75F, false);
			}
			if ((since >= 24 && near(main, goal)) || since > 90) {
				armTick = age;
				heldHand = heldHandOld = j.handR;
			}
			return;
		}
		int at = age - armTick;
		if (!armBroken) {
			// It kneels by your arm, takes the hand, and wrenches it up behind your back
			main.faceAt = j.handR;
			main.speed = 0.02;
			main.kneelTarget = 1;
			main.kneelRate = 0.15F;
			main.leanTarget = 30;
			main.arms(-1.05F, 0.2F, -1.05F, 0.2F);
			main.armIn += (0.3F - main.armIn) * 0.2F;
			double tw = Mth.clamp((at - 10) / 16.0, 0, 1);
			Vec3 behind = j.shoulderR.add(dir.scale(-0.3)).add(0, 0.45, 0);
			Vec3 grip = main.hands().lerp(behind, tw);
			heldHand = at < 5 ? heldHand.lerp(grip, 0.3) : grip;
			look(at < 6 ? main.face(1.0F) : j.handR, 0.2F);
			shake = Math.max(shake, 0.2F + (float) tw * 0.8F);
			if (at == 5) {
				Vec3 m = main.face(1.0F);
				level.playLocalSound(m.x, m.y, m.z, ATTACK, SoundSource.HOSTILE, 1.2F, 0.85F, false);
				level.playLocalSound(eyes.x, eyes.y, eyes.z, PLAYER_PAIN, SoundSource.PLAYERS, 0.9F, 0.85F, false);
			}
			if (at == 12) {
				fx(j.elbowR, STRAIN, 0.9F, 1.1F);
			}
			if (at == 28) {
				snapBone(j.elbowR, eyes);
				armBroken = true;
				heldHand = heldHandOld = null;
			}
			return;
		}
		if (leaveTick < 0) {
			// It stands over you a moment, roars, and walks off: it is done with you
			int since = at - 28;
			main.kneelTarget = 0;
			main.leanTarget = 15;
			main.headPitch = 30;
			main.faceAt = eyes;
			main.arms(-0.4F, 0.15F, -0.4F, 0.15F);
			main.armIn *= 0.8F;
			look(main.face(1.0F), 0.12F);
			crawlTick(false, 0.5F);
			if (since == 8) {
				Vec3 m = main.face(1.0F);
				level.playLocalSound(m.x, m.y, m.z, ATTACK, SoundSource.HOSTILE, 1.3F, 0.7F, false);
			}
			if (since >= 22) {
				leaveTick = age;
				main.armR = main.armL = Float.NaN; // its own shamble again
				main.headPitch = 0;
				main.goal = new Vec3(sx + awayX * 14, sy, sz + awayZ * 14);
				main.speed = 0.03;
			}
			return;
		}
		// Left there, bleeding out: reaching weakly with the one arm left, slower, then not at all
		int d = age - leaveTick;
		double p = Mth.clamp(d / (double) Math.max(1, sceneLength - 25 - leaveTick), 0, 1);
		weak = (float) Math.min(1.0, p * 1.2);
		main.faceAt = main.goal.add(0, 1.5, 0);
		main.leanTarget = 8;
		if (p < 0.7) {
			crawlTick(false, (float) (0.7 * (1 - p)));
		}
		look(d < 50 ? main.face(1.0F) : ahead.add(0, -0.2, 0), 0.06F);
		darkTarget = 0.3F + (float) p * 0.6F;
		darkRate = 0.04F;
		blackTarget = p > 0.75 ? (float) ((p - 0.75) / 0.25) : p > 0.4 && RANDOM.nextInt(40) == 0 ? 0.6F : (float) (p * 0.2);
		blackRate = 0.08F;
		if (age % 6 == 0) {
			BlockParticleOption blood = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState());
			level.addParticle(blood, j.kneeR.x, j.kneeR.y + 0.1, j.kneeR.z, 0, 0, 0);
			level.addParticle(blood, j.elbowR.x, j.elbowR.y + 0.1, j.elbowR.z, 0, 0, 0);
		}
		if (d % 40 == 20 && p < 0.8) {
			level.playLocalSound(eyes.x, eyes.y, eyes.z, PLAYER_PAIN, SoundSource.PLAYERS, 0.3F, 0.55F, false);
		}
		if (p >= 0.97) {
			knockedOut = true; // the heart stops
		}
	}

	// ---------------------------------------------------------------- CHOKED: shoved down, strangled slowly

	private static final int SHOVE = 28, CHOKE_LEN = 150;
	/** When it got to you and stood over you (it waits, then gets down). */
	private static int overTick;

	/** Its hands round your throat, either side of your neck, pressing in and down (squeeze 0..1); `rate` how fast they take over. */
	private static void throatGrip(Vec3 neck, double tx, double tz, float rate, float squeeze) {
		double sideX = tz, sideZ = -tx; // its right, across your neck (facing you, its right is on your left)
		double in = 0.09 - 0.025 * squeeze;
		main.reachR = new Vec3(neck.x + sideX * in, neck.y + 0.06 - 0.03 * squeeze, neck.z + sideZ * in);
		main.reachL = new Vec3(neck.x - sideX * in, neck.y + 0.06 - 0.03 * squeeze, neck.z - sideZ * in);
		if (main.reachROld == null) {
			main.reachROld = main.reachR;
			main.reachLOld = main.reachL;
		}
		main.reach += (1 - main.reach) * rate;
	}

	private static void choked(Minecraft mc) {
		if (bodyId == Integer.MIN_VALUE) {
			// It walks up to you, and shoves you down
			main.goal = new Vec3(sx + awayX * 0.9, sy, sz + awayZ * 0.9);
			main.faceAt = new Vec3(sx, sy + 1.6, sz);
			main.speed = 0.07;
			main.leanTarget = 8;
			look(main.face(1.0F), 0.3F);
			double dx = main.x - sx, dz = main.z - sz;
			boolean close = dx * dx + dz * dz < 1.15 * 1.15;
			if (age < SHOVE || (!close && age < SHOVE + 40)) {
				main.arms(close ? -0.9F : -1.6F, 0.3F, close ? -0.9F : -1.6F, 0.3F); // drawn back to shove
				return;
			}
			main.arms(-1.75F, 0.9F, -1.75F, 0.9F);
			main.leanTarget = 20;
			main.zombie.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
			Vec3 eyes = new Vec3(sx, sy + 1.62, sz);
			fx(eyes, PUNCH, 1.3F, 0.7F);
			level.playLocalSound(eyes.x, eyes.y, eyes.z, HIT_PLAYER, SoundSource.HOSTILE, 1.1F, 0.8F, false);
			level.playLocalSound(eyes.x, eyes.y, eyes.z, SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0F, 0.7F, false);
			shake = 2.0F;
			redPulse = 0.9F;
			kickPitch = -12;
			fallBody(mc, 0.16);
			blackTarget = 0.7F;
			blackRate = 0.6F;
			blink = 4;
			return;
		}
		Vec3 head = point(Corpses.HEAD), hips = point(Corpses.HIPS), neck = point(Corpses.NECK);
		double tx = hips.x - head.x, tz = hips.z - head.z, tl = Math.sqrt(tx * tx + tz * tz);
		if (tl < 1.0E-3) {
			tx = awayX;
			tz = awayZ;
			tl = 1;
		}
		tx /= tl;
		tz /= tl;
		if (blink > 0) {
			blink--;
		}
		// It walks over and stands above you, looking down; then slowly gets down on top of you
		// (1.25 back: its hands close round your throat ~0.4 from your eyes, not round the camera)
		main.goal = head.add(tx * 0.95, 0, tz * 0.95);
		main.faceAt = head;
		main.speed = 0.05;
		main.headPitch = 30;
		if (main.arrived && overTick < 0) {
			overTick = age;
		}
		if (chokeStart < 0) {
			blackTarget = blink > 0 ? 0.7F : 0.1F;
			blackRate = 0.4F;
			darkTarget = 0.2F;
			look(main.face(1.0F), 0.1F);
			int over = overTick < 0 ? -1 : age - overTick;
			if (over < 22) {
				// Standing over you, arms hanging, looking you over
				main.arms(-0.5F, 0.15F, -0.5F, 0.15F);
				main.armIn = 0;
				main.leanTarget = 18;
				main.leanRate = 0.15F;
				if (over == 6) {
					Vec3 m = main.face(1.0F);
					level.playLocalSound(m.x, m.y, m.z, SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE, 1.0F, 0.7F, false);
				}
				if (age < SHOVE + 110) {
					return;
				}
			}
			// Down onto its knees, slowly, hands coming for your throat
			main.kneelTarget = 1;
			main.kneelRate = 0.06F;
			main.leanTarget = 28;
			main.leanRate = 0.08F;
			main.arms(-1.43F, 0.08F, -1.43F, 0.08F);
			throatGrip(neck, tx, tz, 0.06F, 0.0F);
			main.armIn += (0.38F - main.armIn) * 0.08F;
			if (main.kneel > 0.8F || age > SHOVE + 140) {
				// The hands close round your throat
				chokeStart = age;
				Vec3 m = main.face(1.0F);
				level.playLocalSound(m.x, m.y, m.z, ATTACK, SoundSource.HOSTILE, 1.2F, 0.75F, false);
				level.playLocalSound(head.x, head.y, head.z, PLAYER_PAIN, SoundSource.PLAYERS, 0.8F, 0.6F, false);
				shake = Math.max(shake, 1.0F);
			}
			return;
		}
		int c = age - chokeStart;
		double p = Mth.clamp(c / (double) CHOKE_LEN, 0, 1); // how far gone you are
		// Both hands round your throat: squeezing in waves (thumbs pressing in, leaning its weight down), trembling
		float squeeze = (float) Math.max(0, Math.sin(age * 0.45));
		float tremble = (float) Math.sin(age * 1.9) * 0.035F;
		main.arms(-1.43F + squeeze * 0.06F + tremble, 0.35F, -1.43F + squeeze * 0.06F - tremble, 0.35F);
		throatGrip(neck, tx, tz, 0.3F, squeeze);
		main.armIn += (0.34F + squeeze * 0.08F - main.armIn) * 0.35F;
		main.leanTarget = 30 + squeeze * 4;
		main.leanRate = 0.3F;
		main.headPitch = 38 + squeeze * 10;
		main.headJitter = (float) Math.sin(age * 1.3) * 5;
		if (squeeze > 0.95F) {
			shake = Math.max(shake, 0.5F);
		}
		bodyPull(Corpses.NECK, neck.x, neck.y - 0.02, neck.z, 0.2);
		// You fight it: hands clawing at its wrists, legs kicking; weaker and weaker, then nothing
		double fight = Math.max(0, 1 - p * 1.4);
		if (fight > 0) {
			BodyRig.Joints z = BodyRig.solve(main.pose(1.0F), false);
			Vec3 wristR = z.handR.lerp(z.elbowR, 0.3), wristL = z.handL.lerp(z.elbowL, 0.3);
			double j = (RANDOM.nextDouble() - 0.5) * 0.08 * fight;
			bodyPull(Corpses.R_HAND, wristR.x + j, wristR.y + j, wristR.z - j, 0.25 * fight);
			bodyPull(Corpses.L_HAND, wristL.x - j, wristL.y - j, wristL.z + j, 0.25 * fight);
			if (RANDOM.nextInt(6) == 0) {
				int foot = RANDOM.nextBoolean() ? Corpses.R_FOOT : Corpses.L_FOOT;
				Vec3 f = point(foot);
				bodyPull(foot, f.x, f.y + 0.3 * fight, f.z, 0.5);
			}
			kickYaw += (float) ((RANDOM.nextDouble() - 0.5) * 3 * fight);
			kickPitch += (float) ((RANDOM.nextDouble() - 0.5) * 3 * fight);
		}
		look(main.face(1.0F), 0.1F);
		shake = Math.max(shake, 0.25F + (float) fight * 0.3F);
		// Fading: darker, the colour draining, blacking out for moments, static creeping in
		darkTarget = 0.2F + (float) p * 0.7F;
		darkRate = 0.05F;
		blackTarget = (float) (p * 0.2) + (p > 0.6 && p < 0.85 && RANDOM.nextInt(24) == 0 ? 0.4F : 0.0F);
		blackRate = 0.3F;
		if (p > 0.6) {
			setGrey(true);
		}
		double sp = Mth.clamp((p - 0.4) / 0.6, 0, 1);
		staticTarget = (float) (sp * sp * 0.9) + (float) (sp * (RANDOM.nextFloat() - 0.5) * 0.12);
		if (c % 34 == 10 && fight > 0.1) {
			// Gagging, choking for air
			level.playLocalSound(head.x, head.y, head.z, PLAYER_PAIN, SoundSource.PLAYERS, 0.25F + (float) fight * 0.5F, 0.5F, false);
		}
		if (c % 22 == 0) {
			fx(neck, STRAIN, 0.5F, 1.2F);
		}
		if (c == 70) {
			Vec3 m = main.face(1.0F);
			level.playLocalSound(m.x, m.y, m.z, ATTACK, SoundSource.HOSTILE, 1.1F, 0.7F, false);
		}
		if (c == CHOKE_LEN) {
			// Gone: the static takes everything, then nothing
			staticOutTick = age;
			knockedOut = true;
			inHead(TV_STATIC, 0.9F, 1.0F);
		}
		if (c > CHOKE_LEN + 10) {
			black = blackOld = blackTarget = 1.0F;
			staticTarget = staticLevel = 0;
		}
	}

	// ---------------------------------------------------------------- LIFTED: surrounded, picked up, head torn off

	private static final int LIFT = 50, TWIST = 52, RIP = 96;

	private static void lifted(Minecraft mc) {
		Vec3 eyes = new Vec3(sx + camDX, sy + eyeHeight, sz + camDZ);
		// The ring closes in and stops around you
		for (Walker w : walkers) {
			if (w != main) {
				w.goal = new Vec3(sx, sy, sz);
				w.faceAt = eyes;
				w.leanTarget = 6;
				double dx = w.x - sx, dz = w.z - sz;
				if (dx * dx + dz * dz < 2.5 * 2.5) {
					w.speed = 0;
				}
			}
		}
		if (grabTick < 0) {
			eyeHeight = 1.62 + Math.sin(age * 0.5) * 0.015; // panting
			darkTarget = 0.12F + age * 0.0015F;
			if (age < 60) {
				// Looking around: they are on every side
				double base = Math.atan2(awayZ, awayX);
				double a = base + Math.sin(age / 60.0 * Math.PI * 2.2) * 2.3;
				look(new Vec3(sx + Math.cos(a) * 6, sy + 1.3, sz + Math.sin(a) * 6), 0.15F);
			} else {
				// ...and then you see the one coming straight for you
				look(main.face(1.0F), 0.1F);
			}
			if (age >= 30) {
				main.goal = new Vec3(sx, sy, sz);
				main.faceAt = eyes;
				main.speed = 0.042;
				main.arms(-1.6F, 0.1F, -1.6F, 0.1F); // reaching for you
			}
			double dx = main.x - sx, dz = main.z - sz;
			if (dx * dx + dz * dz > 0.85 * 0.85 && age < 125) {
				return;
			}
			// It has you by the throat
			grabTick = age;
			hanger = CutsceneClone.of(mc);
			hanger.setId(nextId--);
			hanger.setSilent(true);
			hanger.noPhysics = true;
			hanger.setNoGravity(true);
			hanger.snapTo(sx, sy, sz, 0.0F, 0.0F);
			level.addEntity(hanger);
			BodyRig.attach(hanger.getId(), GameOverCutscene::hangPose);
			hangKick = 0.5F;
			hangLimp = hangLimpOld = 0;
			main.speed = 0;
			main.goal = new Vec3(main.x, main.y, main.z);
			shake = Math.max(shake, 1.2F);
			redPulse = 0.6F;
			Vec3 m = main.face(1.0F);
			level.playLocalSound(m.x, m.y, m.z, ATTACK, SoundSource.HOSTILE, 1.3F, 0.8F, false);
			level.playLocalSound(eyes.x, eyes.y, eyes.z, PLAYER_PAIN, SoundSource.PLAYERS, 0.8F, 0.75F, false);
		}
		int t = age - grabTick;
		Vec3 face = main.face(1.0F);
		double tx = main.x - sx, tz = main.z - sz, tl = Math.max(1.0E-3, Math.sqrt(tx * tx + tz * tz));
		tx /= tl;
		tz /= tl;
		if (kind == Kind.SLAMMED) {
			slam(t, eyes, face, tx, tz, tl);
			return;
		}
		if (t < RIP) {
			// Slowly up off your feet, drawn in toward its face, choking and kicking
			double u = Mth.clamp(t / (double) LIFT, 0, 1);
			u = u * u * (3 - 2 * u);
			eyeHeight = 1.62 + u * 0.6 + Math.sin(age * 0.9) * 0.012;
			camDX = tx * u * 0.3;
			camDZ = tz * u * 0.3;
			main.arms(-1.75F - (float) u * 0.75F, 0.15F, -1.75F - (float) u * 0.75F, 0.15F);
			main.leanTarget = (float) (-8 * u);
			main.headPitch = (float) (-25 * u);
			look(face.add(0, -0.05, 0), 0.25F);
			shake = Math.max(shake, 0.35F + (float) u * 0.3F);
			if (age % 9 == 0) {
				kickPitch += (float) ((RANDOM.nextDouble() - 0.5) * 4);
				kickYaw += (float) ((RANDOM.nextDouble() - 0.5) * 4);
			}
			if (t == 18 || t == 44) {
				level.playLocalSound(eyes.x, eyes.y, eyes.z, PLAYER_PAIN, SoundSource.PLAYERS, 0.7F, 0.6F, false);
			}
			darkTarget = 0.25F + (float) u * 0.15F;
		}
		if (t >= TWIST && t < RIP) {
			// Its hands twist your head round, further and further, then pull
			double v = (t - TWIST) / (double) (RIP - TWIST);
			rollTarget = (float) (75 * v * v);
			darkTarget = 0.4F + (float) v * 0.35F;
			redPulse = Math.max(redPulse, (float) v * 0.4F);
			shake = Math.max(shake, 0.5F + (float) v * 1.2F);
			eyeHeight += v * 0.12; // pulled up off your shoulders
			if ((t - TWIST) % 12 == 0) {
				fx(eyes.add(0, -0.3, 0), STRAIN, 0.9F + (float) v * 0.6F, 1.0F - (float) v * 0.3F);
			}
			if (t == RIP - 20) {
				level.playLocalSound(eyes.x, eyes.y, eyes.z, PLAYER_PAIN, SoundSource.PLAYERS, 0.9F, 0.5F, false);
			}
		}
		if (t == RIP) {
			// Off. Lights out
			Vec3 neck = eyes.add(0, -0.35, 0);
			fx(neck, BONE_SNAP, 1.6F, 0.75F);
			fx(neck, FLESH_TEAR, 1.5F, 0.8F);
			fx(neck, BLOOD_GUSH, 1.3F, 0.9F);
			fx(neck, PUNCH, 1.2F, 0.5F);
			level.playLocalSound(face.x, face.y, face.z, ATTACK, SoundSource.HOSTILE, 1.3F, 0.7F, false);
			for (int i = 0; i < 4; i++) {
				gush(neck, new Vec3(0, 1, 0), 6);
			}
			rollTarget = 160;
			roll += 40;
			shake = 3.0F;
			redPulse = 1.0F;
			blackoutTick = age;
			knockedOut = true;
		}
		if (t > RIP) {
			eyeHeight += 0.04; // the head lifted away
			if (t > RIP + 12) {
				black = blackOld = blackTarget = 1.0F;
			}
		}
	}

	private static final int OVERHEAD = 40, HOLD = 62, SLAM = 70, SLAM_HIT = 77;

	/** Your hanging body: under the camera, facing it, a little back from the eyes (the neck in its hands). */
	private static void placeHanger() {
		double ex = sx + camDX, ey = sy + eyeHeight, ez = sz + camDZ;
		double lx = main.x - ex, lz = main.z - ez, l = Math.max(1.0E-3, Math.sqrt(lx * lx + lz * lz));
		float yaw = (float) Math.toDegrees(Math.atan2(-lx, lz));
		hanger.xo = hanger.getX();
		hanger.yo = hanger.getY();
		hanger.zo = hanger.getZ();
		hanger.setPos(ex - lx / l * 0.08, ey - 1.62, ez - lz / l * 0.08);
		hanger.yBodyRotO = hanger.yBodyRot;
		hanger.yBodyRot = yaw;
		hanger.setYRot(yaw);
		hanger.setYHeadRot(yaw);
		hangLimpOld = hangLimp;
		if (kind == Kind.LIFTED && grabTick >= 0) {
			// Through the twist the fight goes out of you: the grip slips, then the arms drop
			int t = age - grabTick;
			float want = (float) Mth.clamp((t - TWIST - 8) / (double) (RIP - TWIST - 8), 0, 1);
			hangLimp += (want - hangLimp) * 0.15F;
		}
		hangKick += ((knockedOut ? 0.08F : 0.5F) * (1 - hangLimp) - hangKick) * 0.2F;
	}

	/**
	 * Hanging by the throat: the body straight down under your eyes, facing it, both hands up clawing at its wrists, legs
	 * kicking (knees jerking up in turn), slowing as you go.
	 */
	private static BodyRig.Pose hangPose(float pt) {
		BodyRig.Pose p = new BodyRig.Pose();
		Vec3 eyes = new Vec3(sx + Mth.lerp(pt, camDXOld, camDX), sy + Mth.lerp(pt, eyeHeightOld, eyeHeight), sz + Mth.lerp(pt, camDZOld, camDZ));
		Vec3 to = main == null ? eyes.add(awayX, 0, awayZ) : main.face(pt);
		Vec3 front = new Vec3(to.x - eyes.x, 0, to.z - eyes.z);
		front = front.lengthSqr() < 1.0E-6 ? new Vec3(awayX, 0, awayZ) : front.normalize();
		p.up = new Vec3(0, 1, 0);
		p.front = front;
		Vec3 neck = eyes.subtract(0, 0.27, 0).subtract(front.scale(0.08));
		p.pelvis = neck.subtract(0, 0.75, 0);
		p.headPitch = -0.35F; // chin forced up by its grip
		p.hideHead = !debugView;
		Vec3 right = p.right();
		float t = age + pt;
		float kick = hangKick;
		// Hands on its wrists, just in front of your throat, shaking as you claw; losing their grip (sliding down its
		// forearms), then falling to hang at your sides
		float limp = Mth.lerp(pt, hangLimpOld, hangLimp);
		Vec3 grip = neck.add(front.scale(0.3)).add(0, -0.05 - 0.22 * Math.min(1.0, limp * 2), 0);
		double claw = Math.sin(t * 1.1) * 0.04 * kick;
		Vec3 gripR = grip.add(right.scale(0.12 + claw)).add(0, Math.sin(t * 0.9) * 0.05 * kick, 0);
		Vec3 gripL = grip.subtract(right.scale(0.12 - claw)).add(0, Math.cos(t * 0.9) * 0.05 * kick, 0);
		double drop = Mth.clamp((limp - 0.45) / 0.55, 0, 1);
		drop = drop * drop * (3 - 2 * drop);
		Vec3 sideR = neck.add(right.scale(0.42)).subtract(0, 0.72, 0).add(front.scale(0.04));
		Vec3 sideL = neck.subtract(right.scale(0.42)).subtract(0, 0.72, 0).add(front.scale(0.04));
		// (a little swing as they drop)
		double swing = Math.sin(t * 0.5) * 0.06 * drop * (1 - drop * 0.5);
		p.handR = gripR.lerp(sideR, drop).add(front.scale(swing));
		p.handL = gripL.lerp(sideL, drop).add(front.scale(-swing));
		p.elbowR = right.add(new Vec3(0, -1, 0)).lerp(front.scale(-1), drop);
		p.elbowL = right.scale(-1).add(new Vec3(0, -1, 0)).lerp(front.scale(-1), drop);
		// Legs kicking in turn: knee up and forward, then a stamp down at nothing
		for (int f = 0; f < 2; f++) {
			double ph = Math.max(0, Math.sin(t * 0.45 + f * Math.PI)) * kick * 2;
			Vec3 hip = p.pelvis.add(right.scale(f == 0 ? 0.125 : -0.125));
			Vec3 foot = hip.subtract(0, 0.74 - 0.35 * ph, 0).add(front.scale(0.3 * ph));
			if (f == 0) {
				p.footR = foot;
			} else {
				p.footL = foot;
			}
		}
		return p;
	}

	private static void removeHanger() {
		if (hanger != null) {
			BodyRig.detach(hanger.getId());
		}
		if (hanger != null && level != null) {
			level.removeEntity(hanger.getId(), Entity.RemovalReason.DISCARDED);
		}
		hanger = null;
	}

	/** SLAMMED: up off your feet, raised high over its head, then brought down into the ground in front of it. */
	private static void slam(int t, Vec3 eyes, Vec3 face, double tx, double tz, double dist) {
		if (t < SLAM) {
			double u = Mth.clamp(t / (double) OVERHEAD, 0, 1);
			u = u * u * (3 - 2 * u);
			double v = Mth.clamp((t - OVERHEAD) / (double) (HOLD - OVERHEAD), 0, 1);
			v = v * v * (3 - 2 * v);
			// Up, drawn in, then hoisted right up over its head
			// (Held up in front of it, not over it: from right above, a standing zombie looks like one lying on the ground)
			eyeHeight = 1.62 + u * 0.45 + v * 0.15 + Math.sin(age * 0.9) * 0.012;
			double in = Math.max(0, dist - 0.75) * u; // its arm's length away
			if (in < 0.0) {
				in = 0;
			}
			camDX = tx * in;
			camDZ = tz * in;
			float arms = -1.75F - (float) u * 0.45F - (float) v * 0.15F; // up and forward to your throat
			main.arms(arms, 0.15F, arms, 0.15F);
			main.leanTarget = (float) (-4 * u - 6 * v);
			main.headPitch = (float) (-20 * u - 10 * v);
			look(face.add(0, -0.1, 0), 0.25F);
			rollTarget = (float) Math.sin(age * 0.15) * 4 * (float) v;
			darkTarget = 0.25F + (float) (u * 0.1 + v * 0.15);
			shake = Math.max(shake, 0.35F + (float) (u * 0.3 + v * 0.5));
			if (age % 9 == 0) {
				kickPitch += (float) ((RANDOM.nextDouble() - 0.5) * 4);
				kickYaw += (float) ((RANDOM.nextDouble() - 0.5) * 4);
			}
			if (t == 18) {
				level.playLocalSound(eyes.x, eyes.y, eyes.z, PLAYER_PAIN, SoundSource.PLAYERS, 0.7F, 0.6F, false);
			}
			if (t == OVERHEAD + 4) {
				// It roars as it lifts you over its head
				level.playLocalSound(face.x, face.y, face.z, ATTACK, SoundSource.HOSTILE, 1.4F, 0.65F, false);
				level.playLocalSound(face.x, face.y, face.z, SPRINT, SoundSource.HOSTILE, 0.9F, 0.7F, false);
			}
			if (t >= HOLD) {
				// Held up there for a moment, trembling
				shake = Math.max(shake, 1.1F);
			}
			return;
		}
		// Down: hurled in an arc onto the ground in front of it
		double gx = main.x - tx * 2.45, gz = main.z - tz * 2.45; // where your head comes down
		double gy = ground(gx, sy + 1.0, gz);
		double fx = main.x - tx * 0.85, fz = main.z - tz * 0.85; // and your feet
		if (t < SLAM_HIT) {
			double k = (t - SLAM) / (double) (SLAM_HIT - SLAM);
			k = k * k; // faster and faster
			double from = Math.max(0, dist - 0.75);
			double fromX = tx * from, fromZ = tz * from;
			double toX = gx - sx, toZ = gz - sz;
			camDX = Mth.lerp(k, fromX, toX);
			camDZ = Mth.lerp(k, fromZ, toZ);
			double top = 1.62 + 0.45 + 0.15;
			eyeHeight = Mth.lerp(k, top, gy - sy + 0.18) + Math.sin(k * Math.PI) * 0.35;
			main.arms(-0.7F, 0.6F, -0.7F, 0.6F);
			main.leanTarget = 45;
			main.leanRate = 0.6F;
			main.headPitch = 40;
			// The ground rushes up at you, the world tipping over
			look(new Vec3(gx - tx * 1.5, gy - 0.3, gz - tz * 1.5), 0.5F);
			rollTarget = -25;
			shake = Math.max(shake, 1.5F);
			if (t == SLAM) {
				level.playLocalSound(face.x, face.y, face.z, ATTACK, SoundSource.HOSTILE, 1.4F, 0.6F, false);
			}
			return;
		}
		if (t == SLAM_HIT) {
			// Into the ground: everything breaks, lights out
			Vec3 hit = new Vec3(gx, gy + 0.2, gz);
			fx(hit, BODY_FALL, 1.8F, 0.6F);
			fx(hit, PUNCH, 1.6F, 0.45F);
			fx(hit, BONE_SNAP, 1.5F, 0.7F);
			fx(hit, SPLAT, 1.2F, 0.7F);
			level.playLocalSound(hit.x, hit.y, hit.z, HIT_PLAYER, SoundSource.HOSTILE, 1.3F, 0.6F, false);
			BlockParticleOption blood = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState());
			for (int i = 0; i < 25; i++) {
				level.addParticle(blood, hit.x + (RANDOM.nextDouble() - 0.5) * 0.6, hit.y, hit.z + (RANDOM.nextDouble() - 0.5) * 0.6,
					(RANDOM.nextDouble() - 0.5) * 0.4, 0.15 + RANDOM.nextDouble() * 0.25, (RANDOM.nextDouble() - 0.5) * 0.4);
			}
			BloodFx.screenSplat();
			BloodFx.screenSplat();
			kickPitch = 18;
			roll -= 25;
			shake = 4.0F;
			redPulse = 1.0F;
			white = Math.max(white, 0.6F);
			blackoutTick = age;
			knockedOut = true;
			// You are lying where you hit: a body on the ground (head away from it), looking up at it standing over you
			removeHanger();
			double ax = main.x - gx, az = main.z - gz, al = Math.sqrt(ax * ax + az * az);
			if (al > 1.0E-3) {
				awayX = ax / al;
				awayZ = az / al;
			}
			sx = fx;
			sy = ground(fx, sy + 1.0, fz);
			sz = fz;
			camDX = camDZ = camDXOld = camDZOld = 0;
			fallBody(Minecraft.getInstance(), 0.0);
			body.layDown(); // already down: no falling over again
			roll = rollOld = rollTarget = 0;
			look(main.face(1.0F), 0.0F);
			// It lets go: arms dropping, looking down at what it did
			main.arms(-0.35F, 0.3F, -0.35F, 0.3F);
			main.leanTarget = 25;
			main.leanRate = 0.3F;
			main.headPitch = 45;
		}
		if (t > SLAM_HIT + 12) {
			black = blackOld = blackTarget = 1.0F;
		}
	}

	// ---------------------------------------------------------------- AMBUSHED: a sprinter out of nowhere, one punch

	private static final int SPOT = 74;

	private static void ambushed(Minecraft mc) {
		Vec3 eyes = new Vec3(sx, sy + eyeHeight, sz);
		if (hitTick < 0) {
			eyeHeight = 1.62 + Math.sin(age * 0.55) * 0.02; // panting
			if (age < SPOT) {
				// Looking around, frantic: the eyes dart from one way to another (never quite the way it will come from)
				if (glance == null || age % 11 == 0) {
					double away = Math.atan2(awayZ, awayX);
					double a = away + Math.PI * (0.35 + RANDOM.nextDouble() * 1.3) * (RANDOM.nextBoolean() ? 1 : -1);
					glance = new Vec3(sx + Math.cos(a) * 6, sy + 1.2 + RANDOM.nextDouble() * 0.8, sz + Math.sin(a) * 6);
				}
				look(glance, 0.38F);
				rollTarget = (float) Math.sin(age * 0.2) * 3;
				darkTarget = 0.12F;
				main.faceAt = new Vec3(sx, sy + 1, sz);
				main.goal = new Vec3(main.x, main.y, main.z);
				return;
			}
			if (age == SPOT) {
				// A scream behind you: you spin round, and it is already coming
				level.playLocalSound(sx, sy + 1.0, sz, SPRINT, SoundSource.HOSTILE, 1.0F, 1.0F, false);
				shake = Math.max(shake, 0.8F);
			}
			main.goal = new Vec3(sx, sy, sz);
			main.faceAt = new Vec3(sx, sy + 1, sz);
			main.speed = 0.62;
			main.leanTarget = 6;
			look(main.face(1.0F), 0.45F);
			rollTarget = 0;
			if (age > SPOT && (age - SPOT) % 12 == 0) {
				level.playLocalSound(sx, sy + 1.0, sz, SPRINT, SoundSource.HOSTILE, 1.0F, 1.1F, false);
			}
			double dx = main.x - sx, dz = main.z - sz;
			if (dx * dx + dz * dz > 1.3 * 1.3 && age < SPOT + 45) {
				return;
			}
			// It slams into you: you are thrown back through the air
			hitTick = age;
			main.speed = 0;
			main.arrived = false;
			main.leanTarget = 10;
			main.zombie.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
			punchImpact(true, eyes, main.face(1.0F), 1.4F);
			fx(eyes, PUNCH, 1.6F, 0.8F);
			white = Math.max(white, 0.6F);
			rollTarget = 25;
			fallBody(mc, 0.0); // the throw itself carries you back (below)
			// The body lands with its head ~1.8 behind its hips: only as far as that still fits before a wall
			flyDist = Mth.clamp(flyRoom(-awayX, -awayZ) - 2.0, 0.0, 4.0);
			return;
		}
		int t = age - hitTick;
		if (t < 9) {
			// Flying backwards, a little up, then down (the ragdoll falls on its own)
			double step = flyDist * Math.pow(0.75, t) / 3.7;
			if (blocked(point(Corpses.HEAD), -awayX * step, -awayZ * step) || blocked(point(Corpses.HIPS), -awayX * step, -awayZ * step)) {
				flyDist = 0; // something in the way: drop where you are
			} else {
				bodyShift(-awayX * step, t < 3 ? 0.14 : 0, -awayZ * step);
			}
		}
		if (t == 9) {
			fx(point(Corpses.HIPS), BODY_FALL, 1.2F, 0.9F);
			shake = Math.max(shake, 1.5F);
			rollTarget = 0;
		}
		Vec3 head = point(Corpses.HEAD), hips = point(Corpses.HIPS);
		if (knockedOut) {
			blackTarget = 1;
			blackRate = 0.6F;
			main.arms(HOVER, 0.15F, HOVER, 0.15F);
			main.leanTarget = 25;
			return;
		}
		// Winded on the ground, the world blinking out
		blackTarget = t > 9 && t < 20 ? 0.8F : 0.25F;
		blackRate = 0.4F;
		darkTarget = 0.4F;
		lookAt(main.face(1.0F), 0.1F);
		if (t < 14) {
			return;
		}
		// It runs over, drops onto you
		double tx = hips.x - head.x, tz = hips.z - head.z, tl = Math.sqrt(tx * tx + tz * tz);
		if (tl < 1.0E-3) {
			tx = awayX;
			tz = awayZ;
			tl = 1;
		}
		main.goal = head.add(tx / tl, 0, tz / tl);
		main.faceAt = head;
		main.speed = 0.35;
		main.headPitch = 35;
		if (!main.arrived && age < sceneLength - 45) {
			return;
		}
		main.kneelTarget = 1;
		if (windStart == 0) {
			windStart = age;
			Vec3 m = main.face(1.0F);
			level.playLocalSound(m.x, m.y, m.z, ATTACK, SoundSource.HOSTILE, 1.2F, 0.8F, false);
		}
		int w = age - windStart;
		if (w < 18) {
			// The fist goes up, and hangs there, trembling: you see it coming
			main.arms(RAISED + (float) Math.sin(age * 1.7) * 0.06F, 0.3F, HOVER, 0.2F);
			main.leanTarget = 18;
			main.leanRate = 0.3F;
			darkTarget = 0.4F + w * 0.015F;
			return;
		}
		main.arms(DOWN, 0.9F, HOVER, 0.2F);
		main.leanTarget = 42;
		main.leanRate = 0.7F;
		if (w == 20) {
			// Lights out
			punchImpact(true, head, main.face(1.0F), 1.8F);
			bodyPull(Corpses.HEAD, head.x, head.y - 0.08, head.z, 0.7);
			knockedOut = true;
			blackRate = 0.8F;
			blackTarget = 1;
			inHead(TINNITUS, 0.5F, 0.9F);
		}
	}

	// ---------------------------------------------------------------- DRAGGED: knocked out, dragged away

	private static final int SWING = 34, IMPACT = 40, WAKE = 92, DROP = 190;

	/** DRAGGED: when it got down beside your head / when the bites began (-1 = not yet). */
	private static int dragBiteFrom = -1, dragBiteAt = -1;

	private static void dragged(Minecraft mc) {
		if (age < IMPACT) {
			// It walks right up to you
			main.goal = new Vec3(sx + awayX * 0.9, sy, sz + awayZ * 0.9);
			main.faceAt = new Vec3(sx, sy + 1.6, sz);
			main.leanTarget = 5;
			look(main.face(1.0F), 0.25F);
			if (age == SWING) {
				main.zombie.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
				main.leanTarget = 20;
			}
			return;
		}
		if (age == IMPACT) {
			// The hit: the world spins, you drop, and it goes black
			shake = 2.5F;
			redPulse = 1.0F;
			rollTarget = 55;
			look(new Vec3(sx - awayZ * 3, sy + 3.5, sz + awayX * 3), 0.0F);
			level.playLocalSound(sx, sy + 1.6, sz, HIT_PLAYER, SoundSource.HOSTILE, 1.2F, 0.7F, false);
			level.playLocalSound(sx, sy + 1.6, sz, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.HOSTILE, 1.2F, 0.6F, false);
			fx(new Vec3(sx, sy + 1.6, sz), PUNCH, 1.5F, 0.8F);
			inHead(TINNITUS, 0.55F, 1.0F);
			BloodFx.screenSplat();
			BloodFx.screenSplat();
			fallBody(mc, 0.1);
		}
		Vec3 feet = point(Corpses.R_FOOT).lerp(point(Corpses.L_FOOT), 0.5);
		if (age < WAKE) {
			if (age == IMPACT + 6) {
				blackTarget = 1;
				blackRate = 0.45F;
			}
			// In the dark it steps round to your feet and takes you by the ankles
			if (age > IMPACT + 14) {
				main.goal = feet.add(awayX * 0.6, 0, awayZ * 0.6);
				main.faceAt = feet;
				main.speed = 0.3;
				holdFeet();
			}
			return;
		}
		rollTarget = 8;
		darkTarget = 0.55F;
		int t = age - WAKE;
		blackRate = 0.07F;
		if (age < DROP) {
			// You come to being dragged, blacking in and out
			blackTarget = t < 10 ? 0.35F : (t > 34 && t < 52) || (t > 78 && t < 94) ? 0.92F : 0.3F;
			// It backs away facing you, and you come with it: the whole body slides along, the feet up in its hands
			main.goal = new Vec3(main.x + awayX, main.y, main.z + awayZ);
			main.faceAt = feet;
			// In tugs: a heave back (you slide), a step to reset; a little faster as it gets going
			double heave = Math.max(0, Math.sin(age * 0.32));
			main.speed = (0.012 + 0.07 * heave) * Math.min(1.0, t / 20.0 + 0.3);
			if (heave > 0.97 && age % 2 == 0) {
				shake = Math.max(shake, 0.5F);
			}
			// Your head lolls side to side with the jolts
			Vec3 hd = point(Corpses.HEAD);
			double loll = Math.sin(age * 0.16) * 0.25;
			bodyPull(Corpses.HEAD, hd.x - awayZ * loll, hd.y, hd.z + awayX * loll, 0.3);
			main.kneelTarget = 0;
			holdFeet();
			bodyShift(main.x - main.lastX, 0, main.z - main.lastZ);
			if (t % 22 == 0) {
				fx(point(Corpses.HIPS), DRAG, 1.0F, 1.0F);
			}
			if (age % 3 == 0) {
				// A blood trail
				Vec3 hips = point(Corpses.HIPS);
				BlockParticleOption blood = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState());
				level.addParticle(blood, hips.x, hips.y - 0.1, hips.z, 0, 0, 0);
			}
			// Your arms trail behind your head
			Vec3 head = point(Corpses.HEAD);
			double rx = -awayZ, rz = awayX;
			bodyPull(Corpses.R_HAND, head.x + rx * 0.35 - awayX * 0.5, head.y - 0.05, head.z + rz * 0.35 - awayZ * 0.5, 0.15);
			bodyPull(Corpses.L_HAND, head.x - rx * 0.35 - awayX * 0.5, head.y - 0.05, head.z - rz * 0.35 - awayZ * 0.5, 0.15);
			look(feet.add(0, 0.2, 0).lerp(main.face(1.0F), 0.4), 0.12F);
		} else {
			// It lets go (your legs drop), stands there a moment, then walks up beside your head, kneels and bites your face
			Vec3 head = point(Corpses.HEAD);
			if (age == DROP) {
				fx(feet, BODY_FALL, 0.7F, 1.3F);
				main.arrived = false;
			}
			main.reach *= 0.7F;
			main.arms(-1.45F, 0.15F, -1.45F, 0.15F);
			int d = age - DROP;
			if (d < 14) {
				main.speed = 0;
				main.faceAt = head;
				main.leanTarget = 15;
			} else {
				// Up the side of you (not over you), to beside your head
				Vec3 hips = point(Corpses.HIPS);
				double ux = head.x - hips.x, uz = head.z - hips.z, ul = Math.max(1.0E-3, Math.sqrt(ux * ux + uz * uz));
				double sxd = -uz / ul, szd = ux / ul;
				if ((main.x - head.x) * sxd + (main.z - head.z) * szd < 0) {
					sxd = -sxd;
					szd = -szd;
				}
				Vec3 beside = head.add(sxd * 0.75 - ux / ul * 0.1, 0, szd * 0.75 - uz / ul * 0.1);
				double toward = Math.sqrt((main.x - beside.x) * (main.x - beside.x) + (main.z - beside.z) * (main.z - beside.z));
				main.goal = toward > 1.2 ? hips.add(sxd * 0.85, 0, szd * 0.85) : beside;
				main.faceAt = toward > 1.2 ? main.goal : head;
				main.speed = 0.07;
				if (dragBiteFrom < 0 && (toward < 0.3 || age > DROP + 60)) {
					dragBiteFrom = age;
				}
				if (dragBiteFrom >= 0) {
					// Down on its knees by your head, then right down over your face eating it (as in MISSED): the whole body
					// dipping in on each bite, the head working and tearing, its hands pinning your shoulders
					main.goal = head.add(sxd * 0.5, 0, szd * 0.5);
					main.faceAt = head;
					main.speed = 0.05;
					main.kneelTarget = 1;
					main.kneelRate = 0.18F;
					int since = age - dragBiteFrom;
					boolean down = main.kneel > 0.75F && since >= 10;
					if (!down) {
						// Getting down to you: bending in
						float in = Mth.clamp(since / 12.0F, 0, 1);
						main.leanTarget = 15 + 30 * in;
						main.leanRate = 0.15F;
						main.headPitch = 10 + 20 * in;
						main.headJitter = (float) Math.sin(age * 0.9) * 10 * in;
					} else {
						if (dragBiteAt < 0) {
							dragBiteAt = age;
						}
						int c = (age - dragBiteAt) % 8;
						main.leanTarget = c < 4 ? 88 : 72;
						main.leanRate = 0.5F;
						main.headPitch = c < 4 ? 60 : 35;
						main.headJitter = (float) Math.sin(age * 2.3) * (c < 4 ? 28 : 12);
						main.arms(-1.25F, 0.2F, -1.25F, 0.2F); // its hands down on you
						if (c == 3) {
							tear(head, main, true);
							redPulse = Math.max(redPulse, 0.6F);
							kickPitch = (float) ((RANDOM.nextDouble() - 0.5) * 8);
							kickYaw = (float) ((RANDOM.nextDouble() - 0.5) * 10);
						}
						// Your limp arms twitch at the bites
						if (c == 4) {
							Vec3 hr = point(Corpses.R_HAND), hl = point(Corpses.L_HAND);
							bodyPull(Corpses.R_HAND, hr.x, hr.y + 0.06, hr.z, 0.3);
							bodyPull(Corpses.L_HAND, hl.x, hl.y + 0.04, hl.z, 0.3);
						}
						darkTarget = Math.min(0.85F, 0.55F + (age - dragBiteAt) / 90.0F);
					}
				} else {
					main.kneelTarget = 0;
					main.leanTarget = 10;
				}
			}
			look(main.face(1.0F), 0.2F);
			blackTarget = age > 270 ? 1 : 0.3F;
			blackRate = 0.25F;
		}
		// The others follow
		Vec3 head = point(Corpses.HEAD);
		for (Walker w : walkers) {
			if (w != main) {
				int i = walkers.indexOf(w);
				double a = Math.atan2(awayZ, awayX) + (i - 2) * 0.7;
				w.goal = head.add(Math.cos(a) * 2.0, 0, Math.sin(a) * 2.0);
				w.faceAt = head;
				w.leanTarget = w.arrived ? 30 : 6;
			}
		}
	}

	/** How far in front of the dragger its hands are (where it holds the ankles). */
	private static double reach() {
		Vec3 h = main.hands();
		return Math.max(0.6, Math.sqrt((h.x - main.x) * (h.x - main.x) + (h.z - main.z) * (h.z - main.z)));
	}

	/**
	 * The dragger has both your ankles: they are held up in front of it at hip height (pulled there firmly), and its hands
	 * reach for exactly where your ankles are (its elbows bending to them), so they are always in its grip.
	 */
	private static void holdFeet() {
		main.leanTarget = -10; // leaning back, taking your weight
		main.leanRate = 0.15F;
		double fx = -awayX, fz = -awayZ; // it faces you
		double ox = -fz * 0.11, oz = fx * 0.11; // across
		Vec3 hold = new Vec3(main.x + fx * 0.6, main.y + 0.72, main.z + fz * 0.6);
		bodyPull(Corpses.R_FOOT, hold.x + ox, hold.y, hold.z + oz, 0.85);
		bodyPull(Corpses.L_FOOT, hold.x - ox, hold.y, hold.z - oz, 0.85);
		// Its hands on your ankles (its right hand on whichever is on its right)
		Vec3 a = point(Corpses.R_FOOT), b = point(Corpses.L_FOOT);
		BodyRig.Joints z = BodyRig.solve(main.pose(1.0F), false);
		boolean aRight = a.subtract(b).dot(z.cR) > 0;
		main.arms(-1.0F, 0.2F, -1.0F, 0.2F);
		main.reachR = (aRight ? a : b).add(0, 0.04, 0);
		main.reachL = (aRight ? b : a).add(0, 0.04, 0);
		if (main.reachROld == null) {
			main.reachROld = main.reachR;
			main.reachLOld = main.reachL;
		}
		main.reach += (1 - main.reach) * 0.4F;
	}

	// ------------------------------------------------------------------ shared bits

	/** A bite that tears: blood sprays out of the wound (away from the eyes), the camera jolts, the screen splatters. */
	private static int lastBiteSound = -100;

	private static void tear(Vec3 wound, Walker w, boolean splat) {
		w.zombie.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
		Vec3 eyes = point(Corpses.HEAD);
		double toEyes = wound.distanceTo(eyes);
		if (toEyes > 0.5) {
			BloodFx.spray(wound.add(0, 0.1, 0));
		}
		// A gush up out of the wound (small drops; never from right at the camera)
		BlockParticleOption blood = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState());
		Vec3 off = toEyes < 0.6 ? wound.subtract(eyes).normalize().scale(0.5) : Vec3.ZERO;
		for (int i = 0; i < 6; i++) {
			level.addParticle(blood, wound.x + off.x, wound.y + 0.1, wound.z + off.z, (RANDOM.nextDouble() - 0.5) * 0.12,
				0.2 + RANDOM.nextDouble() * 0.15, (RANDOM.nextDouble() - 0.5) * 0.12);
		}
		if (splat && RANDOM.nextInt(10) < 5) {
			BloodFx.screenSplat();
		}
		// You feel it: a grunt of pain, your body jerks, your eyes snap to it
		if (bodyId != Integer.MIN_VALUE) {
			flinch = 5;
			flinchAt = wound.add(0, 0.2, 0);
			if (age - lastPain > 28 && RANDOM.nextInt(3) != 0) {
				lastPain = age;
				level.playLocalSound(eyes.x, eyes.y, eyes.z, PLAYER_PAIN, SoundSource.PLAYERS, 0.8F, 0.85F + RANDOM.nextFloat() * 0.25F, false);
			}
			bodyPull(Corpses.HEAD, eyes.x + (RANDOM.nextDouble() - 0.5) * 0.3, eyes.y + 0.08, eyes.z + (RANDOM.nextDouble() - 0.5) * 0.3,
				0.5);
		}
		shake = Math.max(shake, splat ? 1.2F : 0.7F);
		redPulse = Math.max(redPulse, splat ? 0.9F : 0.5F);
		float pitch = 0.8F + RANDOM.nextFloat() * 0.3F;
		// The sounds a bit sparser than the bites themselves (not every chomp, and the extra layers only now and then), so
		// a long feed doesn't turn into one continuous racket
		if (age - lastBiteSound >= 11 || age < lastBiteSound) {
			lastBiteSound = age;
			// Just the bite (no hit/punch sounds: it's biting, not hitting), in a spread of pitches so each one differs,
			// and now and then a snarl from it as it feeds
			level.playLocalSound(wound.x, wound.y, wound.z, BITE, SoundSource.HOSTILE, 1.0F, 0.85F + RANDOM.nextFloat() * 0.3F, false);
			// Under it, real chewing and wet squishing, pitched way down (meat, not food)
			level.playLocalSound(wound.x, wound.y, wound.z, SoundEvents.GENERIC_EAT.value(), SoundSource.HOSTILE, 0.6F, 0.8F + RANDOM.nextFloat() * 0.15F,
				false);
			level.playLocalSound(wound.x, wound.y, wound.z, RANDOM.nextBoolean() ? SoundEvents.SLIME_SQUISH : SoundEvents.SLIME_BLOCK_STEP,
				SoundSource.HOSTILE, 0.55F, 0.85F + RANDOM.nextFloat() * 0.2F, false);
			if (splat && RANDOM.nextInt(3) == 0) {
				fx(wound, FLESH_TEAR, 0.7F, 1.1F);
			}
			if (RANDOM.nextInt(4) == 0) {
				level.playLocalSound(w.x, w.y + 1.5, w.z, SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE, 0.6F, pitch, false);
			}
		}

	}

	private static void look(Vec3 at, float rate) {
		lookOld = look;
		if (rate <= 0) {
			look = at;
			lookOld = at;
		} else {
			// Lying in the body the head turns slowly (no whipping around after every zombie)
			look = look.lerp(at, inBody ? Math.min(rate, kind == Kind.AMBUSHED || kind == Kind.CRAWLING || kind == Kind.TACKLED ? 0.3F : 0.1F) : rate);
		}
	}

	private static Vec3 point(int p) {
		return body == null ? new Vec3(sx, sy + 0.2, sz) : body.point(p, 1.0F);
	}

	private static void bodyPull(int p, double x, double y, double z, double strength) {
		if (body != null) {
			body.pull(p, x, y, z, strength);
		}
	}

	private static void bodyShift(double dx, double dy, double dz) {
		if (body != null) {
			body.shift(dx, dy, dz);
		}
	}

	private static double[][] bodyFrame(float pt) {
		return body == null ? null : body.frame(pt);
	}

	private static double[] arr(Vec3 v) {
		return new double[] {v.x, v.y, v.z};
	}

	// ------------------------------------------------------------------ the camera

	/** {x, y, z, yaw, pitch, roll} of the camera this frame, or null when the cutscene is not moving it. */
	public static float[] camera(float partialTicks) {
		float[] view = scene(partialTicks);
		Minecraft mc = Minecraft.getInstance();
		if (view == null || !debugView || mc.player == null) {
			return view;
		}
		// Orbiting the scene (the mouse turns it): looking at the point between your eyes and its face
		Vec3 eyes = new Vec3(view[0], view[1], view[2]);
		Vec3 target = main != null ? eyes.lerp(main.face(partialTicks), 0.5) : eyes;
		float yaw = mc.player.getViewYRot(partialTicks), pitch = mc.player.getViewXRot(partialTicks);
		Vec3 cam = target.subtract(Vec3.directionFromRotation(pitch, yaw).scale(debugDist));
		return new float[] {(float) cam.x, (float) cam.y, (float) cam.z, yaw, pitch, 0.0F};
	}

	/** {x, y, z, yaw, pitch, roll} of the scene's own camera (your eyes) this frame. */
	private static float[] scene(float partialTicks) {
		if (!active || age >= sceneLength || look == null) {
			return null;
		}
		Vec3 eyes;
		if (flashCut != 0) {
			// The flashback: a moment of standing right there, before
			Vec3 stand = new Vec3(sx, sy + 1.62, sz);
			Vec3 to = flashCut == 1 ? stand.add(awayX * 5, -0.3, awayZ * 5) : stand.add(-awayZ * 5, 0.4, awayX * 5);
			double fx = to.x - stand.x, fy = to.y - stand.y, fz = to.z - stand.z;
			return new float[] {(float) stand.x, (float) stand.y, (float) stand.z, (float) Math.toDegrees(Math.atan2(-fx, fz)),
				(float) -Math.toDegrees(Math.atan2(fy, Math.sqrt(fx * fx + fz * fz))), 0.0F};
		}
		if (headOff && headCam != null) {
			eyes = headCamOld.lerp(headCam, partialTicks);
		} else if (kind == Kind.TACKLED && hitTick >= 0 && !inBody && fallFrom != null) {
			// Shoved: pitching forward and down onto your front
			double e = smooth((age - hitTick + partialTicks) / (double) TACKLE_FALL);
			Vec3 to = BodyRig.solve(crawlPose(partialTicks), false).eyes.add(0, 0.14, 0);
			eyes = fallFrom.lerp(to, e * e).add(crawlDir.scale(Math.sin(e * Math.PI) * 0.25));
		} else if (inBody && crawler != null) {
			float lb = Mth.lerp(partialTicks, lookBackOld, lookBack);
			eyes = BodyRig.solve(crawlPose(partialTicks), false).eyes.add(0, 0.14 + 0.18 * lb, 0);
		} else if (inBody && body != null) {
			eyes = body.joints(partialTicks).eyes.add(0, 0.24, 0);
		} else {
			eyes = new Vec3(sx + Mth.lerp(partialTicks, camDXOld, camDX), sy + Mth.lerp(partialTicks, eyeHeightOld, eyeHeight),
				sz + Mth.lerp(partialTicks, camDZOld, camDZ));
		}
		double s = shake * 0.04;
		eyes = eyes.add((RANDOM.nextDouble() - 0.5) * s, (RANDOM.nextDouble() - 0.5) * s, (RANDOM.nextDouble() - 0.5) * s);
		Vec3 at = lookOld.lerp(look, partialTicks);
		double dx = at.x - eyes.x, dy = at.y - eyes.y, dz = at.z - eyes.z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		if (inBody && body != null && kind != Kind.CRAWLING) {
			// Lying there your head only turns so far: down along your body, a little to either side and up
			double[] fr = arr(body.point(Corpses.R_FOOT, partialTicks)), fl = arr(body.point(Corpses.L_FOOT, partialTicks));
			if (fr != null && fl != null) {
				double bx = (fr[0] + fl[0]) / 2 - eyes.x, by = (fr[1] + fl[1]) / 2 + 0.8 - eyes.y, bz = (fr[2] + fl[2]) / 2 - eyes.z;
				float baseYaw = (float) Math.toDegrees(Math.atan2(-bx, bz));
				float basePitch = (float) -Math.toDegrees(Math.atan2(by, Math.sqrt(bx * bx + bz * bz)));
				// Watching your arms go, or the sprinter running at you, needs a wider turn
				boolean wide = kind == Kind.DISMEMBERED || kind == Kind.AMBUSHED || kind == Kind.MISSED;
				float turn = wide ? 80.0F : 45.0F;
				yaw = baseYaw + Mth.clamp(Mth.wrapDegrees(yaw - baseYaw), -turn, turn);
				pitch = Mth.clamp(pitch, basePitch - (kind == Kind.AMBUSHED ? 60.0F : kind == Kind.MISSED ? 85.0F : 30.0F), basePitch + 22.0F);
			}
		}
		yaw += Mth.lerp(partialTicks, kickYawOld, kickYaw);
		pitch += Mth.lerp(partialTicks, kickPitchOld, kickPitch);
		yaw += (float) ((RANDOM.nextDouble() - 0.5) * shake * 3.0);
		pitch += (float) ((RANDOM.nextDouble() - 0.5) * shake * 3.0);
		float r = Mth.lerp(partialTicks, rollOld, roll) + (float) ((RANDOM.nextDouble() - 0.5) * shake * 6.0);
		return new float[] {(float) eyes.x, (float) eyes.y, (float) eyes.z, yaw, Mth.clamp(pitch, -89.0F, 89.0F), r};
	}

	// ------------------------------------------------------------------ the overlay

	private static final Identifier VIGNETTE = TacticalMovement.id("textures/gui/vignette.png");
	private static final Identifier IMPACT_TEX = TacticalMovement.id("textures/gui/impact.png");
	private static final Identifier RING = TacticalMovement.id("textures/gui/ring.png");
	private static final Identifier LID_TOP = TacticalMovement.id("textures/gui/lid_top.png");
	private static final Identifier LID_BOTTOM = TacticalMovement.id("textures/gui/lid_bottom.png");

	/** A texture drawn centred on (cx, cy), `size` across. */
	private static void sprite(GuiGraphicsExtractor g, Identifier tex, int cx, int cy, int size, float alpha, int rgb) {
		int a = Math.round(Mth.clamp(alpha, 0, 1) * 255);
		if (a > 0 && size > 0) {
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex, cx - size / 2, cy - size / 2, 0.0F, 0.0F, size, size, 256, 256,
				256, 256, (a << 24) | rgb);
		}
	}

	/** A smooth round vignette (clear in the middle, solid at the edges) stretched over the screen in `rgb`. */
	private static void vignette(GuiGraphicsExtractor g, int w, int h, float alpha, int rgb) {
		int a = Math.round(Mth.clamp(alpha, 0, 1) * 255);
		if (a > 0) {
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, VIGNETTE, 0, 0, 0.0F, 0.0F, w, h, 256, 256, 256, 256,
				(a << 24) | rgb);
		}
	}

	private static final Identifier STATIC = TacticalMovement.id("textures/gui/static.png");

	/** Snow: a random patch of the noise texture stretched over the screen (a new patch every frame), tearing bands, a rolling bar. */
	private static void tvStatic(GuiGraphicsExtractor g, int w, int h, float level, float t) {
		level = Mth.clamp(level, 0, 1);
		var gui = net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED;
		int rw = Math.max(64, Math.min(500, w / 2)), rh = Math.max(36, Math.min(500, rw * h / Math.max(1, w)));
		g.fill(0, 0, w, h, Math.round(level * 0.55F * 255) << 24);
		g.blit(gui, STATIC, 0, 0, RANDOM.nextInt(512 - rw), RANDOM.nextInt(512 - rh), w, h, rw, rh, 512, 512,
			(Math.round(level * 255) << 24) | 0xFFFFFF);
		// Torn bands: strips of brighter snow, some with a colour fringe
		int bands = Math.round(level * 6);
		for (int i = 0; i < bands; i++) {
			int y = RANDOM.nextInt(h), bh = 1 + RANDOM.nextInt(Math.max(2, h / 14));
			g.blit(gui, STATIC, 0, y, RANDOM.nextInt(512 - rw), RANDOM.nextInt(400), w, bh, rw, Math.max(1, bh / 2), 512, 512,
				(Math.round(Math.min(1.0F, level * 1.6F) * 255) << 24) | 0xFFFFFF);
			if (RANDOM.nextInt(3) == 0) {
				int off = RANDOM.nextInt(9) - 4;
				g.fill(off, y, w + off, y + bh, (Math.round(level * 70) << 24) | (RANDOM.nextBoolean() ? 0xFF2020 : 0x20E0FF));
			}
		}
		// The picture rolling: a dark bar drifting down
		int roll = (int) ((t * 6) % (h + 40)) - 20;
		g.fill(0, roll, w, roll + Math.max(6, h / 16), Math.round(level * 0.5F * 255) << 24);
	}

	/** Swallowed by static: full snow, then it collapses into a thin flickering strip and dies, leaving black. */
	private static void staticOut(GuiGraphicsExtractor g, int w, int h, float st, float t) {
		if (st < 10) {
			tvStatic(g, w, h, 1.0F, t);
			return;
		}
		g.fill(0, 0, w, h, 0xFF000000);
		if (st < 18) {
			float k = (st - 10) / 8.0F;
			int half = Math.max(1, Math.round(h / 2.0F * (1 - k) * (1 - k)));
			var gui = net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED;
			g.blit(gui, STATIC, 0, h / 2 - half, RANDOM.nextInt(256), RANDOM.nextInt(256), w, half * 2, 256, Math.max(1, half), 512, 512,
				0xFFFFFFFF);
			return;
		}
		if (st < 34 && RANDOM.nextInt(4) == 0) {
			// A last few flickers of snow in the dark
			int y = RANDOM.nextInt(h);
			var gui = net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED;
			g.blit(gui, STATIC, 0, y, RANDOM.nextInt(256), RANDOM.nextInt(256), w, 1 + RANDOM.nextInt(3), 256, 2, 512, 512,
				(Math.round((1 - (st - 18) / 16.0F) * 200) << 24) | 0xFFFFFF);
		}
	}

	/** Like an old TV switched off: black slams in from above and below, a white-hot line, shrinking to a dot, gone. */
	private static void blackout(GuiGraphicsExtractor g, int w, int h, float bt) {
		int cy = h / 2, cx = w / 2;
		if (bt < 4) {
			float k = bt / 4.0F;
			k = k * k;
			int gap = Math.max(1, Math.round(h / 2.0F * (1 - k)));
			g.fill(0, 0, w, cy - gap, 0xFF000000);
			g.fill(0, cy + gap, w, h, 0xFF000000);
			// What is left of the picture burns brighter and redder as it closes
			g.fill(0, cy - gap, w, cy + gap, (Math.round(k * 200) << 24) | 0xFFD0C0);
			return;
		}
		g.fill(0, 0, w, h, 0xFF000000);
		if (bt < 9) {
			float k = (bt - 4) / 5.0F;
			int half = Math.max(1, Math.round(w / 2.0F * (1 - k * k)));
			int th = Math.max(1, Math.round(3 * (1 - k)));
			g.fill(cx - half, cy - th, cx + half, cy + th, 0xFFFFF4EC);
			g.fill(cx - half / 2, cy - th - 2, cx + half / 2, cy + th + 2, 0x40FF6040);
			return;
		}
		if (bt < 16) {
			float a = 1 - (bt - 9) / 7.0F;
			int r = Math.max(1, Math.round(3 * a));
			g.fill(cx - r, cy - r, cx + r, cy + r, (Math.round(a * 255) << 24) | 0xFFFFFF);
			g.fill(cx - r * 3, cy - r * 3, cx + r * 3, cy + r * 3, (Math.round(a * 50) << 24) | 0xFF8060);
		}
	}

	public static void renderOverlay(GuiGraphicsExtractor g, DeltaTracker delta) {
		int w = g.guiWidth(), h = g.guiHeight();
		float pt = delta.getGameTimeDeltaPartialTick(false);
		float t = age + pt;
		if (t < sceneLength && debugView) {
			debugReadout(g);
			return;
		}
		if (t < sceneLength) {
			// Dark, red-edged vision that closes in as you fade
			float d = Mth.lerp(pt, darkOld, dark);
			float red = Math.min(1.0F, 0.6F + redPulse * 0.4F);
			vignette(g, w, h, red, 0x3C0000);
			if (d > 0.01F) {
				vignette(g, w, h, Math.min(1.0F, d * 1.1F), 0x000000);
				vignette(g, w, h, d * d, 0x000000);
			}
			if (swell > 0.01F) {
				// Eyes swelling shut: dark lids closing in from above and below
				float wob = 1.0F + (float) Math.sin(t * 0.3) * 0.05F;
				int top = Math.round(h * 0.6F * swell * wob), bottom = Math.round(h * 0.38F * swell * wob);
				var gui = net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED;
				g.blit(gui, LID_TOP, 0, 0, 0.0F, 0.0F, w, top, 256, 128, 256, 128, 0xFF0A0000);
				g.blit(gui, LID_BOTTOM, 0, h - bottom, 0.0F, 0.0F, w, bottom, 256, 128, 256, 128, 0xFF0A0000);
			}
			if (redPulse > 0.05F) {
				g.fill(0, 0, w, h, (Math.round(redPulse * 70) << 24) | 0x700000);
			}
			BloodFx.drawSplats(g, delta);
			float wf = Mth.lerp(pt, whiteOld, white);
			if (wf > 0.02F) {
				g.fill(0, 0, w, h, (Math.round(Mth.clamp(wf, 0, 1) * 255) << 24) | 0xFFFFFF);
			}
			float b = Mth.lerp(pt, blackOld, black);
			if (b > 0.01F) {
				g.fill(0, 0, w, h, Math.round(Mth.clamp(b, 0, 1) * 255) << 24);
			}
			if (staticLevel > 0.01F) {
				tvStatic(g, w, h, staticLevel, t);
			}
			float st = t - staticOutTick;
			if (st >= 0 && st < 40) {
				staticOut(g, w, h, st, t);
			}
			float bt = t - blackoutTick;
			if (bt >= 0 && bt < 40) {
				blackout(g, w, h, bt);
			}
			return;
		}
		// GAME OVER
		float s = t - sceneLength;
		g.fill(0, 0, w, h, 0xFF000000);
		float in = Mth.clamp((s - 6) / 15.0F, 0, 1), out = Mth.clamp((SCREEN - s) / 20.0F, 0, 1);
		int alpha = Math.round(255 * Math.min(in, out));
		if (alpha < 8) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		String title = "GAME OVER";
		String sub = "You survived " + rounds + (rounds == 1 ? " round" : " rounds");
		drawCentered(g, font, title, w / 2.0F, h / 2.0F - 30, 4.0F, (alpha << 24) | 0xB01010);
		drawCentered(g, font, sub, w / 2.0F, h / 2.0F + 14, 1.6F, (alpha << 24) | 0xD8D0D0);
	}

	private static void debugReadout(GuiGraphicsExtractor g) {
		Font font = Minecraft.getInstance().font;
		List<String> lines = new ArrayList<>();
		lines.add(kind + "  tick " + age + "/" + sceneLength + (debugPaused ? "  PAUSED" : "") + (debugSpeed < 1 ? "  x" + debugSpeed : ""));
		float[] e = scene(1.0F);
		if (e != null) {
			lines.add(String.format(Locale.ROOT, "eyes  %+.2f %+.2f %+.2f from spot   yaw %.0f pitch %.0f roll %.0f", e[0] - sx, e[1] - sy, e[2] - sz, e[3],
				e[4], e[5]));
		}
		lines.add("inBody " + inBody + "  body " + (bodyId != Integer.MIN_VALUE) + "  black " + String.format(Locale.ROOT, "%.2f", black) + "  dark "
			+ String.format(Locale.ROOT, "%.2f", dark));
		if (main != null) {
			Vec3 f = main.face(1.0F), hd = main.hands();
			lines.add(String.format(Locale.ROOT, "zombie  %+.2f %+.2f %+.2f from spot   arrived %s", main.x - sx, main.y - sy, main.z - sz, main.arrived));
			lines.add(String.format(Locale.ROOT, "  kneel %.2f  lean %.1f  headPitch %.0f  yaw %.0f", main.kneel, main.lean, main.headPitch,
				main.zombie.yBodyRot));
			lines.add(String.format(Locale.ROOT, "  arms R %.2f  L %.2f  in %.2f", main.armR, main.armL, main.armIn));
			if (e != null) {
				Vec3 eyes = new Vec3(e[0], e[1], e[2]);
				lines.add(String.format(Locale.ROOT, "  face %.2f from eyes (%+.2f up)   hands %.2f from eyes", f.distanceTo(eyes), f.y - eyes.y,
					hd.distanceTo(eyes)));
			}
		}
		lines.add("/got pause  /got step [n]  /got speed <0.05-1>  /got debug [distance]");
		int y = 4;
		for (String line : lines) {
			g.fill(2, y - 1, 4 + font.width(line), y + 9, 0x90000000);
			g.text(font, line, 3, y, 0xFFFFFFFF, false);
			y += 11;
		}
	}

	private static void drawCentered(GuiGraphicsExtractor g, Font font, String text, float cx, float y, float scale, int color) {
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(cx - font.width(text) * scale / 2.0F, y);
		pose.scale(scale, scale);
		g.text(font, text, 0, 0, color, false);
		pose.popMatrix();
	}

	// ------------------------------------------------------------------ helpers

	/** The top of the ground at x, z, searching down from y (a few blocks). */
	private static double ground(double x, double y, double z) {
		Minecraft mc = Minecraft.getInstance();
		if (level == null || mc.player == null) {
			return y;
		}
		return groundUnder(level, x, y, z, 0.25, 6.0, y - 1);
	}

	/**
	 * The top of whatever something `half` wide (a footprint, not a thin ray, so thin blocks like iron bars, panes and
	 * fences hold it up instead of it dropping between their posts) would stand on at x, z, searching `depth` down from
	 * y. Anything reaching up past y is a wall it is in, not a floor (so being pushed into a block doesn't lift it up
	 * onto it). `miss` if there's nothing.
	 */
	public static double groundUnder(net.minecraft.world.level.Level lv, double x, double y, double z, double half, double depth, double miss) {
		AABB box = new AABB(x - half, y - depth, z - half, x + half, y, z + half);
		double best = Double.NEGATIVE_INFINITY;
		for (net.minecraft.world.phys.shapes.VoxelShape shape : lv.getBlockCollisions(null, box)) {
			for (AABB b : shape.toAabbs()) {
				if (b.maxY <= y + 1.0E-4 && b.maxY > best && b.intersects(box)) {
					best = b.maxY;
				}
			}
		}
		return best == Double.NEGATIVE_INFINITY ? miss : best;
	}

	/** How open the ground is around (x, y, z): the average walkable distance (up to 6) in 8 directions. */
	private static double openness(double x, double y, double z) {
		double sum = 0;
		for (int i = 0; i < 8; i++) {
			double a = i * Math.PI / 4;
			sum += roomAt(x, y, z, -Math.sin(a), Math.cos(a), 6);
		}
		return sum / 8;
	}

	/**
	 * Died somewhere cramped (in a corridor, against walls, in a doorway): move the scene to the most open standing spot
	 * close by, so the zombies have room to come at you and the bodies don't end up in the walls.
	 */
	private static void findOpenSpot() {
		double here = openness(sx, sy, sz);
		if (here >= 4.5) {
			return;
		}
		double bestScore = here + 0.6, bx = sx, by = sy, bz = sz;
		// Only a few blocks around where they went down: further than that the scene could end up off the map
		for (int r = 1; r <= SPOT_SEARCH; r++) {
			for (int i = -r; i <= r; i++) {
				for (int j = -r; j <= r; j++) {
					if (Math.max(Math.abs(i), Math.abs(j)) != r) {
						continue; // just the ring at this distance
					}
					double cx = Math.floor(sx + i) + 0.5, cz = Math.floor(sz + j) + 0.5;
					double gy = ground(cx, sy + 2.0, cz);
					if (Math.abs(gy - sy) > 1.6) {
						continue;
					}
					if (!level.noCollision(new AABB(cx - 0.45, gy + 0.05, cz - 0.45, cx + 0.45, gy + 1.9, cz + 0.45))
						|| level.noCollision(new AABB(cx - 0.2, gy - 0.3, cz - 0.2, cx + 0.2, gy - 0.05, cz + 0.2))) {
						continue; // no room to stand, or nothing to stand on
					}
					if (!lineOnFloor(sx, sy, sz, cx, cz)) {
						continue; // the way there crosses a drop: that's past the edge of the map
					}
					double score = openness(cx, gy, cz) - Math.sqrt(i * i + j * j) * 0.35;
					if (score > bestScore) {
						bestScore = score;
						bx = cx;
						by = gy;
						bz = cz;
					}
				}
			}
		}
		sx = bx;
		sy = by;
		sz = bz;
	}

	/** How far (in blocks) from where the player went down the scene may move to find room. */
	private static final int SPOT_SEARCH = 3;

	/**
	 * Is there floor under this spot (something within 1.6 blocks below the given height)? The edge of a floating map, or
	 * a hole, counts like a wall for every room check, so zombies and bodies never go off into the void.
	 */
	private static boolean hasFloor(double x, double y, double z) {
		return !level.noCollision(new AABB(x - 0.2, y - 1.6, z - 0.2, x + 0.2, y - 0.02, z + 0.2));
	}

	/** Floor all the way from (ox, oz) to (tx, tz)? */
	private static boolean lineOnFloor(double ox, double oy, double oz, double tx, double tz) {
		double dx = tx - ox, dz = tz - oz, len = Math.sqrt(dx * dx + dz * dz);
		for (double d = 0; d <= len; d += 0.5) {
			double f = len < 1.0E-6 ? 0 : d / len;
			if (!hasFloor(ox + dx * f, oy + 0.5, oz + dz * f)) {
				return false;
			}
		}
		return true;
	}

	/** How far (up to 7) a body could be thrown along the ground from the spot this way: anything from ankle height up stops it. */
	private static double flyRoom(double dx, double dz) {
		if (level == null) {
			return 0;
		}
		for (double d = 0.25; d <= 7.0; d += 0.25) {
			double x = sx + dx * d, z = sz + dz * d;
			AABB box = new AABB(x - 0.45, sy + 0.1, z - 0.45, x + 0.45, sy + 1.2, z + 0.45);
			if (!level.noCollision(box) || !hasFloor(x, sy + 0.5, z)) {
				return d - 0.25;
			}
		}
		return 7.0;
	}

	/** Would a body part at `p` hit something moving (dx, dz)? */
	private static boolean blocked(Vec3 p, double dx, double dz) {
		double x = p.x + dx, z = p.z + dz;
		return !level.noCollision(new AABB(x - 0.3, p.y - 0.1, z - 0.3, x + 0.3, p.y + 0.3, z + 0.3));
	}

	/** How many blocks (up to max) a zombie could walk from the spot in this direction before a wall. */
	private static double room(double dx, double dz, double max) {
		return roomAt(sx, sy, sz, dx, dz, max);
	}

	private static double roomAt(double ox, double oy, double oz, double dx, double dz, double max) {
		if (level == null) {
			return max;
		}
		for (double d = 0.5; d <= max; d += 0.5) {
			double x = ox + dx * d, z = oz + dz * d;
			AABB box = new AABB(x - 0.3, oy + 0.6, z - 0.3, x + 0.3, oy + 1.8, z + 0.3);
			if (!level.noCollision(box) || !hasFloor(x, oy + 0.5, z)) {
				return d - 0.5;
			}
		}
		return max;
	}
}
