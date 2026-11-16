package com.mzcod.tacticalmovement.client.wounds;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mzcod.tacticalmovement.network.BloodPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

// Ragdoll corpses. A killed zombie is replaced on this client by a copy of it (same look, gear and wounds) whose body is a
// small physics skeleton (Verlet points held together by bone lengths), not an animation:
//  * the torso is three points (hips, neck, a shoulder) kept at fixed distances, so it is one solid piece that can tumble
//    any way; the head, both hands and both feet are points hanging off their joints by the bone length, so they swing
//    and flop with gravity;
//  * every point collides with the blocks around it, so a body slumps against walls, drapes over edges and lies flat;
//  * the killing hit knocks the whole body back and hits the part it struck harder (a headshot snaps the head back);
//  * players walking into a body shove the parts they touch; a body that has stopped moving goes to sleep (no cost)
//    until it is pushed again.
// The model is then posed from the points every frame: the torso placed and turned to match its three points, the head,
// arms and legs aimed at theirs. A headshot kill takes the head off. Only this client knows about them; the newest MAX stay.
public final class Corpses {
	public static final int MAX = 25;

	/** For the renderer: the corpse drawn by this render state (set by LivingEntityRendererCorpseMixin). */
	public static final RenderStateDataKey<Pose> POSE = RenderStateDataKey.create(() -> "tacticalmovement:corpse_pose");

	private static final Random RANDOM = new Random();

	// The skeleton's points
	public static final int HIPS = 0, NECK = 1, SHOULDER = 2, HEAD = 3, R_HAND = 4, L_HAND = 5, R_FOOT = 6, L_FOOT = 7, POINTS = 8;
	/** How big each point is for colliding with blocks (the torso and head are thicker than the limbs). */
	private static final double[] RADIUS = {0.14, 0.14, 0.13, 0.2, 0.1, 0.1, 0.1, 0.1};
	// Body measurements in blocks (Minecraft's humanoid model: 1 pixel = 1/16)
	private static final double SPINE = 0.75, HALF_SHOULDERS = 0.3125, HEAD_OFFSET = 0.25, ARM = 0.625, LEG = 0.75, HIP_SIDE = 0.119,
		SHOULDER_DROP = 0.125;
	private static final double GRAVITY = 0.032, DRAG = 0.985;
	/** For this many ticks after death the body is still partly held up, fading out: it slumps and buckles, not drops. */
	private static final int SLUMP_TICKS = 16;
	private static final int ITERATIONS = 6;

	private static final Deque<Integer> ORDER = new ArrayDeque<>();
	private static final Map<Integer, Body> BODIES = new HashMap<>();
	private static int nextId = -1_000_000;
	private static ClientLevel lastLevel;

	private Corpses() {
	}

	/** What the renderer needs for one frame of a corpse. */
	public record Pose(float offX, float offY, float offZ, Matrix4f torso, boolean headless, boolean red, float headX, float headZ,
		float rightArmX, float rightArmZ, float leftArmX, float leftArmZ, float rightLegX, float rightLegZ, float leftLegX,
		float leftLegZ) {
	}

	private static final class Body {
		final int id;
		final boolean headless;
		final double[][] pos = new double[POINTS][3];
		final double[][] old = new double[POINTS][3];
		final double[][] prev = new double[POINTS][3]; // where they were last tick, for smooth drawing
		int age;
		int still;
		boolean asleep;
		// The death animation it plays first (null = straight to ragdoll), and where it died (the animation's frame)
		DeathAnim anim;
		int animTicks;
		/** How far the animation's poses have been moved down because the body fell (no floor where it died). */
		double animShift;
		double footX, footY, footZ;
		double fwdX, fwdZ, rightX, rightZ;

		Body(int id, boolean headless) {
			this.id = id;
			this.headless = headless;
		}
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(Corpses::tick);
	}

	public static boolean isCorpse(int entityId) {
		return BODIES.containsKey(entityId);
	}

	// ------------------------------------------------------------------ spawning

	/** The zombie with this id was just killed by this hit: leave its body behind. */
	public static void spawn(Minecraft mc, BloodPayload hit) {
		ClientLevel level = mc.level;
		if (level == null || !(level.getEntity(hit.entityId()) instanceof LivingEntity dead)) {
			return;
		}
		Entity made = dead.getType().create(level, new net.minecraft.world.entity.EntitySpawnRequest(EntitySpawnReason.LOAD, true));
		if (!(made instanceof LivingEntity copy)) {
			return;
		}
		copy.setId(nextId--);
		copy.snapTo(dead.getX(), dead.getY(), dead.getZ(), 180.0F, 0.0F);
		if (copy instanceof Zombie z && dead instanceof Zombie d) {
			z.setBaby(d.isBaby());
		}
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			copy.setItemSlot(slot, dead.getItemBySlot(slot).copy());
		}
		copy.setCustomName(dead.getCustomName());
		copy.setCustomNameVisible(false);
		copy.setNoGravity(true);
		copy.setSilent(true);
		copy.setHealth(0.0F); // dead: never targetable
		// No pushing either way: Minecraft has every mob shove nearby players each tick (dead ones too) unless it has no physics
		copy.noPhysics = true;

		boolean headshot = hit.kind() == BloodPayload.HEADSHOT;
		Body b = new Body(copy.getId(), headshot);
		standUp(b, dead);
		double yawRad = Math.toRadians(dead.yBodyRot);
		b.footX = dead.getX();
		b.footY = dead.getY();
		b.footZ = dead.getZ();
		b.fwdX = -Math.sin(yawRad);
		b.fwdZ = Math.cos(yawRad);
		b.rightX = -Math.cos(yawRad);
		b.rightZ = -Math.sin(yawRad);
		b.anim = DeathAnim.pick(hit, b.fwdX * hit.dx() + b.fwdZ * hit.dz());
		if (b.anim != null) {
			b.animTicks = b.anim.ticks + RANDOM.nextInt(3);
		}

		// Knockback along the hit: the whole body a little, the part that was hit a lot more (that is what spins it)
		Vec3 dir = new Vec3(hit.dx(), hit.dy() * 0.3, hit.dz());
		if (dir.lengthSqr() < 1.0E-6) {
			dir = mc.player != null ? dead.position().subtract(mc.player.position()) : new Vec3(0, 0, 1);
		}
		dir = dir.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : dir.normalize();
		double power = hit.kind() == BloodPayload.SLASH ? 0.10 : headshot ? 0.28 : 0.18;
		power *= 0.8 + RANDOM.nextDouble() * 0.4;
		if (b.anim != null) {
			power *= 0.35; // the animation carries the body; the hit only nudges it
		}
		Vec3 base = dir.scale(power * 0.45).add(0, 0.05, 0);
		int struck = headshot ? HEAD : nearest(b, hit.x(), hit.y(), hit.z());
		for (int i = 0; i < POINTS; i++) {
			Vec3 v = base;
			if (i == struck) {
				v = v.add(dir.scale(power * 1.3));
			}
			if (headshot && i == NECK) {
				v = v.add(dir.scale(power * 0.7));
			}
			// A random twist of the whole body: one shoulder side forward, the other back
			if (i == SHOULDER) {
				double twist = (RANDOM.nextDouble() - 0.5) * 0.12;
				v = v.add(b.fwdX * twist, 0, b.fwdZ * twist);
			}
			// Limbs get a little random fling of their own
			if (i >= R_HAND) {
				v = v.add((RANDOM.nextDouble() - 0.5) * 0.06, RANDOM.nextDouble() * 0.04, (RANDOM.nextDouble() - 0.5) * 0.06);
			}
			for (int k = 0; k < 3; k++) {
				b.old[i][k] = b.pos[i][k] - (k == 0 ? v.x : k == 1 ? v.y : v.z);
				b.prev[i][k] = b.pos[i][k];
			}
		}

		copy.setPos(b.pos[HIPS][0], b.pos[HIPS][1], b.pos[HIPS][2]);
		copy.xo = copy.getX();
		copy.yo = copy.getY();
		copy.zo = copy.getZ();
		level.addEntity(copy);
		BODIES.put(copy.getId(), b);
		ORDER.addLast(copy.getId());
		WoundFx.transfer(hit.entityId(), copy.getId());
		if (headshot) {
			WoundFx.paintNeck(mc, copy.getId());
		}
		while (ORDER.size() > MAX) {
			int old = ORDER.removeFirst();
			BODIES.remove(old);
			level.removeEntity(old, Entity.RemovalReason.DISCARDED);
		}
	}

	/**
	 * A body for the game-over cutscene: the given (client-only) entity, standing at x, y, z facing yaw, collapses as a
	 * ragdoll, its upper body falling toward (fallX, fallZ). Returns its entity id.
	 */
	public static int spawnBody(Minecraft mc, LivingEntity copy, double x, double y, double z, float yaw, double fallX, double fallZ) {
		ClientLevel level = mc.level;
		copy.setId(nextId--);
		copy.snapTo(x, y, z, yaw, 0.0F);
		copy.yBodyRot = yaw;
		copy.yBodyRotO = yaw;
		copy.setNoGravity(true);
		copy.setSilent(true);
		copy.setHealth(0.0F);
		copy.noPhysics = true;
		Body b = new Body(copy.getId(), false);
		standUp(b, copy);
		double yawRad = Math.toRadians(yaw);
		b.footX = x;
		b.footY = y;
		b.footZ = z;
		b.fwdX = -Math.sin(yawRad);
		b.fwdZ = Math.cos(yawRad);
		b.rightX = -Math.cos(yawRad);
		b.rightZ = -Math.sin(yawRad);
		// Arms down at the sides rather than reaching forward like a zombie
		double sy = y + 1.5 - SHOULDER_DROP - ARM * 0.95;
		set(b.pos[R_HAND], x + b.rightX * (HALF_SHOULDERS + 0.05), sy, z + b.rightZ * (HALF_SHOULDERS + 0.05));
		set(b.pos[L_HAND], x - b.rightX * (HALF_SHOULDERS + 0.05), sy, z - b.rightZ * (HALF_SHOULDERS + 0.05));
		for (int i = 0; i < POINTS; i++) {
			double k = i == NECK || i == SHOULDER || i == HEAD ? 0.075 : i == HIPS ? 0.025 : i == R_HAND || i == L_HAND ? 0.05 : 0.0;
			double vx = fallX * k + (RANDOM.nextDouble() - 0.5) * 0.01, vz = fallZ * k + (RANDOM.nextDouble() - 0.5) * 0.01;
			b.old[i][0] = b.pos[i][0] - vx;
			b.old[i][1] = b.pos[i][1];
			b.old[i][2] = b.pos[i][2] - vz;
			System.arraycopy(b.pos[i], 0, b.prev[i], 0, 3);
		}
		copy.setPos(b.pos[HIPS][0], b.pos[HIPS][1], b.pos[HIPS][2]);
		copy.xo = copy.getX();
		copy.yo = copy.getY();
		copy.zo = copy.getZ();
		level.addEntity(copy);
		BODIES.put(copy.getId(), b);
		return copy.getId();
	}

	/** Lays a body made with spawnBody flat on its back at once (head away from where it faces, feet where it stood), still. */
	public static void layDown(int id) {
		layDown(id, false);
	}

	/** Lays a body flat at once: on its back (head away from where it faces), or face down (prone: head where it faces). */
	public static void layDown(int id, boolean prone) {
		Body b = BODIES.get(id);
		if (b == null) {
			return;
		}
		for (int i = 0; i < POINTS; i++) {
			double dx = b.pos[i][0] - b.footX, dz = b.pos[i][2] - b.footZ;
			double f = dx * b.fwdX + dz * b.fwdZ, r = dx * b.rightX + dz * b.rightZ, h = b.pos[i][1] - b.footY;
			// Tipped straight back: height becomes distance behind, forward becomes height
			double nf = prone ? h : -h, nh = Math.max(0.1, (prone ? -f : f) + 0.12);
			b.pos[i][0] = b.footX + b.fwdX * nf + b.rightX * r;
			b.pos[i][1] = b.footY + nh;
			b.pos[i][2] = b.footZ + b.fwdZ * nf + b.rightZ * r;
			System.arraycopy(b.pos[i], 0, b.old[i], 0, 3);
			System.arraycopy(b.pos[i], 0, b.prev[i], 0, 3);
		}
	}

	/** Where a point of a body is (between the last two ticks), or null if there is no such body. */
	public static double[] point(int id, int p, float partialTicks) {
		Body b = BODIES.get(id);
		if (b == null) {
			return null;
		}
		return new double[] {Mth.lerp(partialTicks, b.prev[p][0], b.pos[p][0]), Mth.lerp(partialTicks, b.prev[p][1], b.pos[p][1]),
			Mth.lerp(partialTicks, b.prev[p][2], b.pos[p][2])};
	}

	/** The body's directions this frame: {up (hips to neck), right, behind (its back)}, or null if there is no such body. */
	public static double[][] frameOf(int id, float partialTicks) {
		Body b = BODIES.get(id);
		if (b == null) {
			return null;
		}
		double[][] pts = new double[POINTS][];
		for (int i = 0; i < POINTS; i++) {
			pts[i] = new double[] {Mth.lerp(partialTicks, b.prev[i][0], b.pos[i][0]), Mth.lerp(partialTicks, b.prev[i][1], b.pos[i][1]),
				Mth.lerp(partialTicks, b.prev[i][2], b.pos[i][2])};
		}
		return frame(pts);
	}

	/** Pulls a point of a body toward a place (k = how much of the way per tick), e.g. a zombie dragging it by the feet. */
	public static void pull(int id, int p, double x, double y, double z, double k) {
		Body b = BODIES.get(id);
		if (b == null || lastLevel == null) {
			return;
		}
		b.asleep = false;
		b.still = 0;
		nudge(lastLevel, p, b.pos[p], (x - b.pos[p][0]) * k, (y - b.pos[p][1]) * k, (z - b.pos[p][2]) * k);
	}

	/** Slides a whole body along (dragged): every point moves, keeping its speed. */
	public static void shift(int id, double dx, double dy, double dz) {
		Body b = BODIES.get(id);
		if (b == null) {
			return;
		}
		b.asleep = false;
		b.still = 0;
		for (int i = 0; i < POINTS; i++) {
			b.pos[i][0] += dx;
			b.pos[i][1] += dy;
			b.pos[i][2] += dz;
			b.old[i][0] += dx;
			b.old[i][1] += dy;
			b.old[i][2] += dz;
		}
	}

	/** Removes a body made with spawnBody. */
	public static void remove(int id) {
		BODIES.remove(id);
		ORDER.remove((Integer) id);
		if (lastLevel != null) {
			lastLevel.removeEntity(id, Entity.RemovalReason.DISCARDED);
		}
	}

	/** The skeleton in the zombie's pose as it died: standing, arms reaching forward. */
	private static void standUp(Body b, LivingEntity dead) {
		double yaw = Math.toRadians(dead.yBodyRot);
		double fx = -Math.sin(yaw), fz = Math.cos(yaw); // its front
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw); // its right
		double x = dead.getX(), y = dead.getY(), z = dead.getZ();
		set(b.pos[HIPS], x, y + 0.75, z);
		set(b.pos[NECK], x, y + 1.5, z);
		set(b.pos[SHOULDER], x - rx * HALF_SHOULDERS, y + 1.5, z - rz * HALF_SHOULDERS);
		set(b.pos[HEAD], x, y + 1.5 + HEAD_OFFSET, z);
		double sy = y + 1.5 - SHOULDER_DROP;
		set(b.pos[R_HAND], x + rx * HALF_SHOULDERS + fx * ARM * 0.85, sy - 0.15, z + rz * HALF_SHOULDERS + fz * ARM * 0.85);
		set(b.pos[L_HAND], x - rx * HALF_SHOULDERS + fx * ARM * 0.85, sy - 0.15, z - rz * HALF_SHOULDERS + fz * ARM * 0.85);
		set(b.pos[R_FOOT], x + rx * HIP_SIDE, y + 0.02, z + rz * HIP_SIDE);
		set(b.pos[L_FOOT], x - rx * HIP_SIDE, y + 0.02, z - rz * HIP_SIDE);
	}

	private static int nearest(Body b, double x, double y, double z) {
		int best = NECK;
		double bestD = Double.MAX_VALUE;
		for (int i : new int[] {HIPS, NECK, HEAD, R_HAND, L_HAND, R_FOOT, L_FOOT}) {
			double dx = b.pos[i][0] - x, dy = b.pos[i][1] - y, dz = b.pos[i][2] - z;
			double d = dx * dx + dy * dy + dz * dz;
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ simulation

	private static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level != lastLevel) {
			ORDER.clear();
			BODIES.clear();
			lastLevel = level;
		}
		if (level == null || mc.isPaused()) {
			return;
		}
		for (Body b : BODIES.values()) {
			Entity e = level.getEntity(b.id);
			if (e == null) {
				continue;
			}
			b.age++;
			for (int i = 0; i < POINTS; i++) {
				System.arraycopy(b.pos[i], 0, b.prev[i], 0, 3);
			}
			pushByPlayers(level, b);
			if (!b.asleep) {
				step(level, b);
			}
			e.setPos(b.pos[HIPS][0], b.pos[HIPS][1], b.pos[HIPS][2]);
		}
	}

	private static void step(ClientLevel level, Body b) {
		// Move every point on by its own speed, with gravity, colliding with blocks one axis at a time
		double moved = 0;
		double hold = b.anim == null && b.age < SLUMP_TICKS ? 0.85 * (1.0 - b.age / (double) SLUMP_TICKS) : 0.0;
		// A death animation playing: how much it still drives the body (1 for most of it, fading to 0 at its end)
		double drive = 0;
		double[][] target = null;
		boolean supported = true;
		if (b.anim != null && b.age <= b.animTicks) {
			double t = b.age / (double) b.animTicks;
			drive = t < 0.6 ? 1.0 : 1.0 - (t - 0.6) / 0.4;
			target = b.anim.pose(b, t);
			// The animation plays alongside the ragdoll: it shapes the body, but it does not hold it in the air. With nothing
			// under the feet (killed mid-jump, on an edge, over a drop) the body falls at full weight and the animation's
			// poses move down with it, so it keeps playing on the way down and where it lands.
			supported = !free(level, b.pos[R_FOOT][0], b.pos[R_FOOT][1] - 0.12, b.pos[R_FOOT][2], RADIUS[R_FOOT])
				|| !free(level, b.pos[L_FOOT][0], b.pos[L_FOOT][1] - 0.12, b.pos[L_FOOT][2], RADIUS[L_FOOT]);
			double cy = 0, ty = 0;
			for (int i = 0; i < POINTS; i++) {
				cy += b.pos[i][1];
				ty += target[i][1];
			}
			if (!supported) {
				b.animShift = Math.min(b.animShift, (cy - ty) / POINTS);
			}
			for (double[] q : target) {
				q[1] += b.animShift;
			}
			hold = Math.max(hold, supported ? drive * 0.85 : 0.0);
		}
		for (int i = 0; i < POINTS; i++) {
			double[] p = b.pos[i], o = b.old[i];
			// The torso and head are still partly held up right after death (the legs giving way), fading out
			double g = GRAVITY * (i <= HEAD || drive > 0 ? 1.0 - hold : 1.0);
			double vx = (p[0] - o[0]) * DRAG, vy = (p[1] - o[1]) * DRAG - g, vz = (p[2] - o[2]) * DRAG;
			o[0] = p[0];
			o[1] = p[1];
			o[2] = p[2];
			boolean grounded = false;
			double r = RADIUS[i];
			if (free(level, p[0], p[1] + vy, p[2], r)) {
				p[1] += vy;
			} else {
				grounded = vy < 0;
				o[1] = p[1] + vy * 0.15; // a tiny bounce
			}
			if (free(level, p[0] + vx, p[1], p[2], r)) {
				p[0] += vx;
			} else {
				o[0] = p[0] + vx * 0.2;
			}
			if (free(level, p[0], p[1], p[2] + vz, r)) {
				p[2] += vz;
			} else {
				o[2] = p[2] + vz * 0.2;
			}
			if (grounded) {
				// Friction against the ground
				o[0] = p[0] - (p[0] - o[0]) * 0.55;
				o[2] = p[2] - (p[2] - o[2]) * 0.55;
			}
			moved = Math.max(moved, Math.abs(vx) + Math.abs(vy + g) + Math.abs(vz));
		}

		// The animation's pose pulls the body into place (moving points keep their speed when it lets go)
		if (target != null) {
			for (int i = 0; i < POINTS; i++) {
				double k = (supported ? 0.45 : 0.25) * drive;
				nudge(level, i, b.pos[i], (target[i][0] - b.pos[i][0]) * k, (target[i][1] - b.pos[i][1]) * k, (target[i][2] - b.pos[i][2]) * k);
			}
		}

		// Hold the skeleton together
		double hipToShoulder = Math.sqrt(SPINE * SPINE + HALF_SHOULDERS * HALF_SHOULDERS);
		for (int it = 0; it < ITERATIONS; it++) {
			link(level, b, HIPS, NECK, SPINE, 0.5);
			link(level, b, NECK, SHOULDER, HALF_SHOULDERS, 0.5);
			link(level, b, HIPS, SHOULDER, hipToShoulder, 0.5);

			double[][] f = frame(b.pos);
			double[] up = f[0], right = f[1];
			double[] n = b.pos[NECK], h = b.pos[HIPS];
			// Head: on the neck, mostly upright with the torso (a stiff neck, a little give)
			hang(level, b, HEAD, n, HEAD_OFFSET);
			pull(level, b, HEAD, add(n, up, HEAD_OFFSET), 0.05);
			// Arms swing freely from the shoulders
			hang(level, b, R_HAND, joint(n, right, up, HALF_SHOULDERS, -SHOULDER_DROP), ARM);
			hang(level, b, L_HAND, joint(n, right, up, -HALF_SHOULDERS, -SHOULDER_DROP), ARM);
			// Legs hang from the hips, a little stiffer (they mostly follow the body)
			double[] hipR = joint(h, right, up, HIP_SIDE, 0), hipL = joint(h, right, up, -HIP_SIDE, 0);
			hang(level, b, R_FOOT, hipR, LEG);
			hang(level, b, L_FOOT, hipL, LEG);
			// (legs swing freely: no stiffness at the hips)
		}

		// Asleep once it has stopped moving for a while
		if (moved < 0.003 && b.age > SLUMP_TICKS + 10 && (b.anim == null || b.age > b.animTicks)) {
			if (++b.still > 30) {
				b.asleep = true;
			}
		} else {
			b.still = 0;
		}
	}

	/** Keeps two torso points the given distance apart, each moving its share (unless that would put it in a block). */
	private static void link(ClientLevel level, Body b, int a, int c, double length, double share) {
		double[] p = b.pos[a], q = b.pos[c];
		double dx = q[0] - p[0], dy = q[1] - p[1], dz = q[2] - p[2];
		double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (d < 1.0E-6) {
			return;
		}
		double diff = (d - length) / d;
		nudge(level, a, p, dx * diff * share, dy * diff * share, dz * diff * share);
		nudge(level, c, q, -dx * diff * (1 - share), -dy * diff * (1 - share), -dz * diff * (1 - share));
	}

	/** Keeps a limb point at the bone length from its joint (only the limb moves: limbs are light). */
	private static void hang(ClientLevel level, Body b, int i, double[] joint, double length) {
		double[] p = b.pos[i];
		double dx = p[0] - joint[0], dy = p[1] - joint[1], dz = p[2] - joint[2];
		double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (d < 1.0E-6) {
			return;
		}
		double diff = (length - d) / d;
		nudge(level, i, p, dx * diff, dy * diff, dz * diff);
	}

	/** Softly draws a point towards where it would rest (how stiff that joint is). */
	private static void pull(ClientLevel level, Body b, int i, double[] target, double strength) {
		double[] p = b.pos[i];
		nudge(level, i, p, (target[0] - p[0]) * strength, (target[1] - p[1]) * strength, (target[2] - p[2]) * strength);
	}

	private static void nudge(ClientLevel level, int i, double[] p, double dx, double dy, double dz) {
		double r = RADIUS[i];
		if (free(level, p[0] + dx, p[1] + dy, p[2] + dz, r)) {
			p[0] += dx;
			p[1] += dy;
			p[2] += dz;
			return;
		}
		// Blocked: keep whichever parts of the move are free
		if (free(level, p[0], p[1] + dy, p[2], r)) {
			p[1] += dy;
		}
		if (free(level, p[0] + dx, p[1], p[2], r)) {
			p[0] += dx;
		}
		if (free(level, p[0], p[1], p[2] + dz, r)) {
			p[2] += dz;
		}
	}

	private static boolean free(ClientLevel level, double x, double y, double z, double r) {
		return level.noCollision(new AABB(x - r, y - r, z - r, x + r, y + r, z + r));
	}

	/**
	 * Players walking into a body shove the parts they touch, the way they walk. A shoved part drags the rest with it (the
	 * skeleton holds together), so pushing a foot swings the body round and pushing it off a ledge drops it.
	 */
	private static void pushByPlayers(ClientLevel level, Body b) {
		if (b.age < 8) {
			return;
		}
		for (var player : level.players()) {
			if (player.isSpectator()) {
				continue;
			}
			double mx = player.getX() - player.xo, mz = player.getZ() - player.zo;
			if (mx * mx + mz * mz < 0.0004) {
				continue;
			}
			for (int i = 0; i < POINTS; i++) {
				double[] p = b.pos[i];
				if (p[1] < player.getY() - 0.3 || p[1] > player.getY() + 1.8) {
					continue;
				}
				double dx = p[0] - player.getX(), dz = p[2] - player.getZ();
				if (dx * dx + dz * dz > 0.45 * 0.45) {
					continue;
				}
				// Moving the point (not its last position) gives it the player's speed
				nudge(level, i, p, mx * 0.7, 0.01, mz * 0.7);
				b.asleep = false;
				b.still = 0;
			}
		}
	}

	// ------------------------------------------------------------------ death animations

	/**
	 * Short death animations that hand over to the ragdoll. Each is a few key poses of the body in its own frame (where it
	 * died, facing the way it faced), blended smoothly; the ragdoll is pulled into them and then let go.
	 * A key: {time 0..1, hip height, hips forward, lean back (rad, - = forward), lean to its right (rad), turn (rad),
	 * head back (rad), right hand {right, up, forward}, left hand {right, up, forward}} (hands relative to the shoulder, in
	 * the torso's frame), and whether the legs fold under it (kneeling) instead of standing.
	 */
	private enum DeathAnim {
		STAGGER_BACK(15, false, new double[][] {
			{0, 0.75, 0, 0, 0, 0, 0, 0, -0.2, 1, 0, -0.2, 1},
			{0.4, 0.73, -0.35, 0.35, 0.05, 0.1, 0.5, 0.8, 0.6, 0.3, -0.8, 0.6, 0.3},
			{1, 0.62, -0.7, 0.95, 0.1, 0.2, 0.8, 1, 0.8, 0, -1, 0.8, 0}}),
		KNEES_BUCKLE(18, true, new double[][] {
			{0, 0.75, 0, 0, 0, 0, 0, 0, -0.2, 1, 0, -0.2, 1},
			{0.35, 0.47, 0.08, -0.2, 0, 0, -0.4, 0.1, -1, 0.2, -0.1, -1, 0.2},
			{0.7, 0.32, 0.18, -0.45, 0.05, 0.05, -0.6, 0.2, -1, 0.4, -0.2, -1, 0.4},
			{1, 0.28, 0.3, -0.95, 0.1, 0.1, -0.7, 0.3, -0.6, 0.8, -0.3, -0.6, 0.8}}),
		SPIN(15, false, new double[][] {
			{0, 0.75, 0, 0, 0, 0, 0, 0, -0.2, 1, 0, -0.2, 1},
			{0.5, 0.68, 0, 0.15, 0.3, 1.4, 0.2, 1, 0.3, 0, 0, -1, 0.1},
			{1, 0.55, 0.1, 0.2, 0.7, 2.5, 0.3, 1, 0.5, -0.2, 0.1, -1, 0}}),
		CLUTCH(19, false, new double[][] {
			{0, 0.75, 0, 0, 0, 0, 0, 0, -0.2, 1, 0, -0.2, 1},
			{0.3, 0.72, 0.1, -0.15, 0, 0, -0.2, -0.5, 0.55, 0.6, 0.5, 0.55, 0.6},
			{0.7, 0.55, 0.3, -0.35, -0.1, 0.15, -0.4, -0.5, 0.5, 0.6, 0.5, 0.5, 0.6},
			{1, 0.45, 0.45, -0.8, -0.15, 0.2, -0.5, -0.3, 0.2, 0.7, 0.3, 0.2, 0.7}}),
		HEAD_SNAP(10, false, new double[][] {
			{0, 0.75, 0, 0, 0, 0, 0, 0, -0.2, 1, 0, -0.2, 1},
			{0.35, 0.72, -0.15, 0.45, 0, 0, 1.0, 0.3, -0.8, 0.3, -0.3, -0.8, 0.3},
			{1, 0.6, -0.35, 0.9, 0.1, 0.1, 1.1, 0.3, -1, 0, -0.3, -1, 0}});

		final int ticks;
		final boolean kneel;
		final double[][] keys;

		DeathAnim(int ticks, boolean kneel, double[][] keys) {
			this.ticks = ticks;
			this.kneel = kneel;
			this.keys = keys;
		}

		/** Which animation a death plays, or null for a plain ragdoll. {@code along} > 0: the hit came from behind it. */
		static DeathAnim pick(BloodPayload hit, double along) {
			double roll = RANDOM.nextDouble();
			if (hit.kind() == BloodPayload.HEADSHOT) {
				return roll < 0.7 ? HEAD_SNAP : null;
			}
			if (roll < 0.3) {
				return null; // sometimes just the ragdoll
			}
			roll = RANDOM.nextDouble();
			if (along > 0.3) {
				// Hit from behind: it goes forward
				return roll < 0.5 ? KNEES_BUCKLE : CLUTCH;
			}
			if (roll < 0.35) {
				return STAGGER_BACK;
			}
			if (roll < 0.6) {
				return KNEES_BUCKLE;
			}
			return roll < 0.8 ? SPIN : CLUTCH;
		}

		/** Where every point of the skeleton should be at time t (0..1) of this animation. */
		double[][] pose(Body b, double t) {
			// Blend the two keys around t, eased
			double[] a = keys[0], c = keys[keys.length - 1];
			for (int i = 0; i < keys.length - 1; i++) {
				if (t >= keys[i][0] && t <= keys[i + 1][0]) {
					a = keys[i];
					c = keys[i + 1];
					break;
				}
			}
			double span = c[0] - a[0];
			double u = span <= 0 ? 1 : Mth.clamp((t - a[0]) / span, 0, 1);
			u = u * u * (3 - 2 * u);
			double[] k = new double[13];
			for (int i = 0; i < 13; i++) {
				k[i] = Mth.lerp(u, a[i], c[i]);
			}
			double hipH = k[1], hipF = k[2], lean = k[3], side = k[4], turn = k[5], headBack = k[6];

			// The torso's directions: turned, leaned back/forward, then to the side
			double cy = Math.cos(turn), sy = Math.sin(turn);
			double[] fwd = {b.fwdX * cy - b.rightX * sy, 0, b.fwdZ * cy - b.rightZ * sy};
			double[] right = {b.rightX * cy + b.fwdX * sy, 0, b.rightZ * cy + b.fwdZ * sy};
			double[] up = {0, 1, 0};
			double[] up1 = lin(up, Math.cos(lean), fwd, -Math.sin(lean));
			double[] fwd1 = lin(fwd, Math.cos(lean), up, Math.sin(lean));
			double[] up2 = lin(up1, Math.cos(side), right, Math.sin(side));
			double[] right2 = lin(right, Math.cos(side), up1, -Math.sin(side));

			double[][] out = new double[POINTS][];
			double[] hips = {b.footX + b.fwdX * hipF, b.footY + hipH, b.footZ + b.fwdZ * hipF};
			double[] neck = add(hips, up2, SPINE);
			out[HIPS] = hips;
			out[NECK] = neck;
			out[SHOULDER] = add(neck, right2, -HALF_SHOULDERS);
			out[HEAD] = add(neck, lin(up2, Math.cos(headBack), fwd1, -Math.sin(headBack)), HEAD_OFFSET);
			double[] shR = joint(neck, right2, up2, HALF_SHOULDERS, -SHOULDER_DROP);
			double[] shL = joint(neck, right2, up2, -HALF_SHOULDERS, -SHOULDER_DROP);
			out[R_HAND] = add(shR, unit(lin3(right2, k[7], up2, k[8], fwd1, k[9])), ARM);
			out[L_HAND] = add(shL, unit(lin3(right2, k[10], up2, k[11], fwd1, k[12])), ARM);
			double[] hipR = joint(hips, right2, up2, HIP_SIDE, 0), hipL = joint(hips, right2, up2, -HIP_SIDE, 0);
			if (kneel) {
				// Legs folded under it: feet behind, as low as the hips allow
				double back = Math.sqrt(Math.max(0, LEG * LEG - hipH * hipH));
				out[R_FOOT] = new double[] {hipR[0] - b.fwdX * back, b.footY + 0.05, hipR[2] - b.fwdZ * back};
				out[L_FOOT] = new double[] {hipL[0] - b.fwdX * back, b.footY + 0.05, hipL[2] - b.fwdZ * back};
			} else {
				out[R_FOOT] = new double[] {hipR[0], b.footY + 0.05, hipR[2]};
				out[L_FOOT] = new double[] {hipL[0], b.footY + 0.05, hipL[2]};
			}
			return out;
		}

		private static double[] lin(double[] a, double ka, double[] c, double kc) {
			return new double[] {a[0] * ka + c[0] * kc, a[1] * ka + c[1] * kc, a[2] * ka + c[2] * kc};
		}

		private static double[] lin3(double[] a, double ka, double[] c, double kc, double[] d, double kd) {
			return new double[] {a[0] * ka + c[0] * kc + d[0] * kd, a[1] * ka + c[1] * kc + d[1] * kd, a[2] * ka + c[2] * kc + d[2] * kd};
		}

		private static double[] unit(double[] v) {
			double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
			return l < 1.0E-6 ? new double[] {0, -1, 0} : new double[] {v[0] / l, v[1] / l, v[2] / l};
		}
	}

	// ------------------------------------------------------------------ skeleton maths

	/** The torso's directions from its three points: {up (hips to neck), its right, behind it}. */
	private static double[][] frame(double[][] pts) {
		double[] up = norm(sub(pts[NECK], pts[HIPS]), new double[] {0, 1, 0});
		double[] toLeft = sub(pts[SHOULDER], pts[NECK]);
		double along = dot(toLeft, up);
		double[] left = norm(new double[] {toLeft[0] - up[0] * along, toLeft[1] - up[1] * along, toLeft[2] - up[2] * along},
			perpendicular(up));
		double[] right = {-left[0], -left[1], -left[2]};
		double[] behind = cross(right, up);
		return new double[][] {up, right, behind};
	}

	private static double[] joint(double[] origin, double[] right, double[] up, double side, double upAmount) {
		return new double[] {origin[0] + right[0] * side + up[0] * upAmount, origin[1] + right[1] * side + up[1] * upAmount,
			origin[2] + right[2] * side + up[2] * upAmount};
	}

	private static double[] add(double[] p, double[] dir, double amount) {
		return new double[] {p[0] + dir[0] * amount, p[1] + dir[1] * amount, p[2] + dir[2] * amount};
	}

	private static double[] sub(double[] a, double[] b) {
		return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	private static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	private static double[] cross(double[] a, double[] b) {
		return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	private static double[] norm(double[] v, double[] fallback) {
		double l = Math.sqrt(dot(v, v));
		return l < 1.0E-6 ? fallback : new double[] {v[0] / l, v[1] / l, v[2] / l};
	}

	private static double[] perpendicular(double[] v) {
		return Math.abs(v[1]) < 0.9 ? norm(cross(v, new double[] {0, 1, 0}), new double[] {1, 0, 0}) : new double[] {1, 0, 0};
	}

	private static void set(double[] p, double x, double y, double z) {
		p[0] = x;
		p[1] = y;
		p[2] = z;
	}

	// ------------------------------------------------------------------ rendering

	/** The pose of corpse {@code id} for this frame, or null when it is not a corpse. */
	public static Pose pose(int id, float partialTicks) {
		Body b = BODIES.get(id);
		if (b == null) {
			return null;
		}
		double[][] pts = new double[POINTS][3];
		for (int i = 0; i < POINTS; i++) {
			for (int k = 0; k < 3; k++) {
				pts[i][k] = Mth.lerp(partialTicks, b.prev[i][k], b.pos[i][k]);
			}
		}
		double[][] f = frame(pts);
		double[] up = f[0], right = f[1], behind = f[2];
		double[] n = pts[NECK], h = pts[HIPS];
		// The model is drawn around the hips point (the entity's position); its neck goes where the neck point is
		Matrix4f torso = new Matrix4f(
			(float) right[0], (float) right[1], (float) right[2], 0,
			(float) up[0], (float) up[1], (float) up[2], 0,
			(float) behind[0], (float) behind[1], (float) behind[2], 0,
			0, 0, 0, 1);

		// Head, arms and legs: aimed from their joints at their points, in the torso's own frame
		float[] head = aim(sub(pts[HEAD], n), right, up, behind, true);
		float[] ra = aim(sub(pts[R_HAND], joint(n, right, up, HALF_SHOULDERS, -SHOULDER_DROP)), right, up, behind, false);
		float[] la = aim(sub(pts[L_HAND], joint(n, right, up, -HALF_SHOULDERS, -SHOULDER_DROP)), right, up, behind, false);
		float[] rl = aim(sub(pts[R_FOOT], joint(h, right, up, HIP_SIDE, 0)), right, up, behind, false);
		float[] ll = aim(sub(pts[L_FOOT], joint(h, right, up, -HIP_SIDE, 0)), right, up, behind, false);
		return new Pose((float) (n[0] - h[0]), (float) (n[1] - h[1]), (float) (n[2] - h[2]), torso, b.headless, b.age < 6,
			head[0], head[1], ra[0], ra[1], la[0], la[1], rl[0], rl[1], ll[0], ll[1]);
	}

	/**
	 * xRot / zRot that turn a model part to point along a direction. Arms and legs hang down from their joints (+y in model
	 * space); the head sits up from the neck (-y). Model space: +x is the zombie's left, +y down, -z its front.
	 */
	private static float[] aim(double[] world, double[] right, double[] up, double[] behind, boolean upward) {
		double fx = dot(world, right), fy = dot(world, up), fz = dot(world, behind);
		double mx = -fx, my = -fy, mz = fz;
		double l = Math.sqrt(mx * mx + my * my + mz * mz);
		if (l < 1.0E-6) {
			return new float[] {0, 0};
		}
		mx /= l;
		my /= l;
		mz /= l;
		if (upward) {
			// rotationZYX(z, 0, x) * (0, -1, 0) = (cos x sin z, -cos x cos z, -sin x)
			return new float[] {(float) Math.asin(Mth.clamp(-mz, -1, 1)), (float) Math.atan2(mx, -my)};
		}
		// rotationZYX(z, 0, x) * (0, 1, 0) = (-cos x sin z, cos x cos z, sin x)
		return new float[] {(float) Math.asin(Mth.clamp(mz, -1, 1)), (float) Math.atan2(-mx, my)};
	}

	/** Places and turns the model to match the torso (called right after the renderer's own turn, which is none here). */
	public static void applyRotation(PoseStack poseStack, Pose p) {
		poseStack.translate(p.offX(), p.offY(), p.offZ());
		poseStack.mulPose(p.torso());
		// The renderer then flips the model and moves it so its neck (model y 0) is 1.501 above this point
		poseStack.translate(0.0F, -1.501F, 0.0F);
	}

	/** Head, arms and legs of a corpse, and no head after a headshot. Every zombie model passes here (models are shared). */
	public static void applyLimbs(HumanoidModel<?> m, LivingEntityRenderState state) {
		Pose p = state.getData(POSE);
		m.head.visible = p == null || !p.headless();
		if (p == null) {
			return;
		}
		m.body.xRot = 0;
		m.body.yRot = 0;
		m.body.zRot = 0;
		m.head.xRot = p.headX();
		m.head.yRot = 0;
		m.head.zRot = p.headZ();
		m.rightArm.xRot = p.rightArmX();
		m.rightArm.yRot = 0;
		m.rightArm.zRot = p.rightArmZ();
		m.leftArm.xRot = p.leftArmX();
		m.leftArm.yRot = 0;
		m.leftArm.zRot = p.leftArmZ();
		m.rightLeg.xRot = p.rightLegX();
		m.rightLeg.yRot = 0;
		m.rightLeg.zRot = p.rightLegZ();
		m.leftLeg.xRot = p.leftLegX();
		m.leftLeg.yRot = 0;
		m.leftLeg.zRot = p.leftLegZ();
	}
}
