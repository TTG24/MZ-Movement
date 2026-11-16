package com.mzcod.tacticalmovement.client.cutscene.body;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The cutscene's body of the player on the bending rig, driven the way the old ragdoll was (so the scene scripts read the
 * same): it falls over onto its back, lies there, and the scene pulls its hands, feet, hips, neck and head toward places,
 * shifts it along the ground. Instead of physics, every part eases toward where it is pulled (or back to lying at rest) on
 * springs, and the arms and legs bend at the elbow and knee to get there, so it moves smoothly and deliberately.
 */
public final class RigBody implements BodyRig.Animator {
	// The same points the ragdoll had (GameOverCutscene uses Corpses' numbers)
	public static final int HIPS = 0, NECK = 1, SHOULDER = 2, HEAD = 3, R_HAND = 4, L_HAND = 5, R_FOOT = 6, L_FOOT = 7;
	private static final int FALL_TICKS = 11;

	public final Entity entity;
	private final ClientLevel level;
	/** Where the pelvis lies (and last tick), and how far it is pulled away from there. */
	private Vec3 base, baseOld, off = Vec3.ZERO, offOld = Vec3.ZERO, offWant;
	private double offRate;
	/** The body's up (feet to head) and front (the way the chest faces), now and last tick. */
	private Vec3 up, upOld, front, frontOld;
	private float bend, bendOld, bendWant, headYaw, headYawOld, headYawWant, headPitch, headPitchOld, headPitchWant;
	private final Vec3[] limb = new Vec3[4], limbOld = new Vec3[4], want = new Vec3[4];
	private final double[] rate = new double[4];
	/** Falling over: from standing at the feet, toward the head's side, sliding along this far. */
	private int fallTick = -1;
	private Vec3 feet, toHead;
	private double slide;
	public boolean hideHead, hideArmR, hideArmL;

	private RigBody(Entity entity, ClientLevel level) {
		this.entity = entity;
		this.level = level;
	}

	/** A body standing at `feet` that falls straight over backward, its head going `toHead` (sliding back `slide` blocks). */
	public static RigBody fall(Entity entity, ClientLevel level, Vec3 feet, Vec3 toHead, double slide) {
		RigBody b = new RigBody(entity, level);
		b.feet = feet;
		b.toHead = new Vec3(toHead.x, 0, toHead.z).normalize();
		b.slide = slide;
		b.fallTick = 0;
		b.applyFall(0);
		b.baseOld = b.base;
		b.upOld = b.up;
		b.frontOld = b.front;
		for (int i = 0; i < 4; i++) {
			b.limb[i] = b.rest(i);
			b.limbOld[i] = b.limb[i];
		}
		BodyRig.attach(entity.getId(), b);
		return b;
	}

	/** Down at once (no falling over): flat on the back. */
	public void layDown() {
		fallTick = FALL_TICKS + 10;
		applyFall(fallTick);
		baseOld = base;
		upOld = up;
		frontOld = front;
		for (int i = 0; i < 4; i++) {
			limb[i] = rest(i);
			limbOld[i] = limb[i];
		}
	}

	public void remove() {
		BodyRig.detach(entity.getId());
		level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
	}

	/** Falling over by the angle for this tick (it speeds up like a real fall), with a little bounce as it lands. */
	private void applyFall(int t) {
		double k = Math.min(1.0, t / (double) FALL_TICKS);
		double a = Math.PI / 2 * k * k;
		Vec3 worldUp = new Vec3(0, 1, 0);
		up = worldUp.scale(Math.cos(a)).add(toHead.scale(Math.sin(a))).normalize();
		front = toHead.scale(-Math.cos(a)).add(worldUp.scale(Math.sin(a))).normalize();
		double bounce = t >= FALL_TICKS && t < FALL_TICKS + 5 ? Math.sin((t - FALL_TICKS) / 5.0 * Math.PI) * 0.06 : 0;
		base = feet.add(toHead.scale(slide * k)).add(up.scale(0.75)).add(0, 0.13 * Math.sin(a) + bounce, 0);
	}

	public boolean down() {
		return fallTick >= FALL_TICKS;
	}

	// ------------------------------------------------------------------ what the scene does to it

	/** Pulls a point toward (x, y, z): strength 0..1, how hard (how fast it gets there). Last pull of a tick wins. */
	public void pull(int p, double x, double y, double z, double strength) {
		Vec3 t = new Vec3(x, y, z);
		switch (p) {
			case R_HAND, L_HAND, R_FOOT, L_FOOT -> {
				int i = p - R_HAND;
				want[i] = t;
				rate[i] = Mth.clamp(0.15 + strength * 0.7, 0.05, 0.9);
			}
			case HIPS -> {
				offWant = t.subtract(base);
				offRate = Mth.clamp(strength * 0.6, 0.05, 0.8);
			}
			case NECK, SHOULDER -> {
				Vec3 d = t.subtract(point(NECK, 1.0F));
				bendWant = (float) Mth.clamp(bendWant + d.dot(front) * 2.5, -0.4, 1.0);
			}
			case HEAD -> {
				// Head pulled: it turns toward the pull, lifts (chin to chest) or is pressed back, the chest coming a little
				Vec3 d = t.subtract(point(HEAD, 1.0F));
				Vec3 right = front.cross(up).normalize();
				headYawWant = (float) Mth.clamp(d.dot(right) * 4 * strength * 2, -1.2, 1.2);
				headPitchWant = (float) Mth.clamp(d.dot(front) * 3 * strength * 2, -0.9, 0.9);
			}
			default -> {
			}
		}
	}

	/** Moves the whole body along (dragged, thrown). */
	public void shift(double dx, double dy, double dz) {
		Vec3 d = new Vec3(dx, dy, dz);
		base = base.add(d);
		if (feet != null && !down()) {
			feet = feet.add(d);
		}
		for (int i = 0; i < 4; i++) {
			limb[i] = limb[i].add(d);
		}
	}

	/** One tick: falling, settling onto the ground, every part easing toward where it is pulled (or back to rest). */
	public void tick() {
		baseOld = base;
		offOld = off;
		upOld = up;
		frontOld = front;
		bendOld = bend;
		headYawOld = headYaw;
		headPitchOld = headPitch;
		for (int i = 0; i < 4; i++) {
			limbOld[i] = limb[i];
		}
		if (fallTick >= 0 && fallTick < FALL_TICKS + 6) {
			Vec3 before = base;
			applyFall(++fallTick);
			if (fallTick == 1) {
				baseOld = before;
			}
		} else {
			// Lying: it settles onto the ground under it (after being thrown up, it comes back down)
			double g = ground(base) + 0.13;
			base = new Vec3(base.x, base.y + (g - base.y) * (base.y > g ? 0.35 : 0.6), base.z);
		}
		// Pulled away and springing back
		Vec3 offTarget = offWant != null ? offWant : Vec3.ZERO;
		off = off.add(offTarget.subtract(off).scale(offWant != null ? offRate : 0.2));
		// Lying on the ground the hips only shift a little: never lifted up off it (that read as floating)
		if (down()) {
			double h = Math.sqrt(off.x * off.x + off.z * off.z), max = 0.08;
			off = new Vec3(h > max ? off.x * max / h : off.x, Math.min(off.y, 0.02), h > max ? off.z * max / h : off.z);
		}
		offWant = null;
		bend += (bendWant - bend) * 0.3F;
		headYaw += (headYawWant - headYaw) * 0.3F;
		headPitch += (headPitchWant - headPitch) * 0.3F;
		bendWant = headYawWant = headPitchWant = 0;
		double floor = ground(base) + 0.06;
		for (int i = 0; i < 4; i++) {
			Vec3 target = want[i] != null ? want[i] : rest(i);
			double r = want[i] != null ? rate[i] : 0.22;
			Vec3 next = limb[i].add(target.subtract(limb[i]).scale(r));
			if (down() && next.y < floor) {
				next = new Vec3(next.x, floor, next.z);
			}
			limb[i] = next;
			want[i] = null;
		}
		Vec3 pel = base.add(off);
		entity.xo = entity.getX();
		entity.yo = entity.getY();
		entity.zo = entity.getZ();
		entity.setPos(pel.x, pel.y, pel.z);
	}

	/** Where a hand or foot lies at rest: arms along the sides, legs straight (flung up a little while falling). */
	private Vec3 rest(int i) {
		Vec3 right = front.cross(up).normalize();
		Vec3 pel = base.add(off);
		Vec3 neck = pel.add(up.scale(0.75));
		double falling = down() ? 0 : 1;
		return switch (i) {
			case 0 -> neck.add(right.scale(0.375 + 0.12)).subtract(up.scale(0.68 - 0.5 * falling)).add(front.scale(0.35 * falling));
			case 1 -> neck.subtract(right.scale(0.375 + 0.12)).subtract(up.scale(0.68 - 0.5 * falling)).add(front.scale(0.35 * falling));
			case 2 -> pel.add(right.scale(0.15)).subtract(up.scale(0.74)).add(front.scale(0.15 * falling));
			default -> pel.subtract(right.scale(0.15)).subtract(up.scale(0.74)).add(front.scale(0.15 * falling));
		};
	}

	private double ground(Vec3 at) {
		return com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene.groundUnder(level, at.x, at.y + 0.8, at.z, 0.15, 3.8, at.y - 0.13);
	}


	// ------------------------------------------------------------------ reading it

	@Override
	public BodyRig.Pose pose(float pt) {
		BodyRig.Pose p = new BodyRig.Pose();
		p.pelvis = baseOld.add(offOld).lerp(base.add(off), pt);
		p.up = upOld.lerp(up, pt).normalize();
		p.front = frontOld.lerp(front, pt).normalize();
		p.bend = Mth.lerp(pt, bendOld, bend);
		p.headYaw = Mth.lerp(pt, headYawOld, headYaw);
		p.headPitch = Mth.lerp(pt, headPitchOld, headPitch);
		p.handR = limbOld[0].lerp(limb[0], pt);
		p.handL = limbOld[1].lerp(limb[1], pt);
		p.footR = limbOld[2].lerp(limb[2], pt);
		p.footL = limbOld[3].lerp(limb[3], pt);
		p.hideHead = hideHead;
		p.hideArmR = hideArmR;
		p.hideArmL = hideArmL;
		return p;
	}

	public BodyRig.Joints joints(float pt) {
		return BodyRig.solve(pose(pt), false);
	}

	/** A point of the body (the ragdoll's numbering). */
	public Vec3 point(int p, float pt) {
		BodyRig.Joints j = joints(pt);
		return switch (p) {
			case HIPS -> j.pelvis.add(j.pU.scale(0.05));
			case NECK -> j.neck;
			case SHOULDER -> j.shoulderL;
			case HEAD -> j.headCenter;
			case R_HAND -> j.handR;
			case L_HAND -> j.handL;
			case R_FOOT -> j.footR;
			default -> j.footL;
		};
	}

	/** {up, right, behind} of the body, like the ragdoll's frame. */
	public double[][] frame(float pt) {
		BodyRig.Joints j = joints(pt);
		return new double[][] {{j.pU.x, j.pU.y, j.pU.z}, {j.pR.x, j.pR.y, j.pR.z}, {-j.pF.x, -j.pF.y, -j.pF.z}};
	}
}
