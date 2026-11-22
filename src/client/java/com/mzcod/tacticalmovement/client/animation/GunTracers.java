package com.mzcod.tacticalmovement.client.animation;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mzcod.tacticalmovement.network.TracerPayload;
import com.mzcod.tacticalmovement.network.TriggerPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Bullet tracers: each shot (TracerPayload, from the MinecraftZombies plugin) is drawn as a bright streak flying from the
 * gun's muzzle to where the bullet stopped (a wall, or the end of its range), with a faint glowing line along the path for
 * an instant. Also sends the trigger (attack key) state, so automatic guns keep firing while it is held.
 */
public final class GunTracers {
	/** How fast a tracer flies (blocks a second), how long its streak is (blocks), how long the faint line lasts (s). */
	private static final double SPEED = 220, STREAK = 3.5, GLOW = 0.07;

	/** kind: 0 a bullet; 1 lightning from the gun (the Wunderwaffe); 2 lightning jumping between zombies; 3 a Ray Gun bolt. */
	private record Tracer(Vec3 from, Vec3 to, float r, float g, float b, long born, int kind) {
	}

	/** How long a bolt of lightning is seen (s), and how often it flickers into a new shape (ms). */
	private static final double BOLT = 0.28;
	private static final long FLICKER_MS = 45;
	/** A Ray Gun bolt: how fast it flies (blocks a second), and how long its impact ring lasts (s). */
	private static final double RAY_SPEED = 70, IMPACT = 0.3;

	private static final List<Tracer> TRACERS = new ArrayList<>();
	private static boolean triggerDown;
	private static int triggerTicks;

	private GunTracers() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(TracerPayload.TYPE, (payload, context) -> add(context.client(), payload));
		ClientTickEvents.END_CLIENT_TICK.register(GunTracers::tickTrigger);
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
			java.util.Set<Integer> shocked = com.mzcod.tacticalmovement.client.zombie.ZombieShock.shocked();
			if (TRACERS.isEmpty() && shocked.isEmpty()) {
				return;
			}
			long now = System.nanoTime();
			TRACERS.removeIf(t -> (now - t.born) / 1.0E9 > life(t));
			if (TRACERS.isEmpty() && shocked.isEmpty()) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			Vec3 cam = mc.gameRenderer.mainCamera().position();
			PoseStack ps = ctx.poseStack();
			List<Tracer> snapshot = new ArrayList<>(TRACERS);
				List<net.minecraft.world.phys.AABB> bodies = new ArrayList<>();
			List<Integer> ids = new ArrayList<>();
			if (mc.level != null) {
				for (int id : shocked) {
					Entity e = mc.level.getEntity(id);
					if (e != null) {
						bodies.add(e.getBoundingBox());
						ids.add(id);
					}
				}
			}
			ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.lightning(), (pose, vc) -> {
				for (Tracer t : snapshot) {
					draw(t, now, cam, pose, vc);
				}
				for (int i = 0; i < bodies.size(); i++) {
					crackle(ids.get(i), bodies.get(i), now, cam, pose, vc);
				}
			});
		});
	}

	private static void add(Minecraft mc, TracerPayload p) {
		if (mc.level == null) {
			return;
		}
		int kind = (p.color() >>> 24) & 255;
		if (kind > 3) {
			kind = 0;
		}
		Vec3 from = new Vec3(p.fx(), p.fy(), p.fz());
		Vec3 to = new Vec3(p.tx(), p.ty(), p.tz());
		Entity shooter = mc.level.getEntity(p.shooter());
		// Stops at the first wall
		if (kind == 2) {
			// a jump between zombies: just as it is
			if (TRACERS.size() > 200) {
				TRACERS.remove(0);
			}
			int c = p.color();
			TRACERS.add(new Tracer(from, to, ((c >> 16) & 255) / 255F, ((c >> 8) & 255) / 255F, (c & 255) / 255F, System.nanoTime(), 2));
			return;
		}
		HitResult wall = mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
			shooter != null ? shooter : mc.player));
		if (wall.getType() != HitResult.Type.MISS) {
			to = wall.getLocation();
		}
		// Seen coming out of the gun's muzzle: in first person just below and right of the view (the middle when aiming),
		// otherwise out in front of the shooter's chest at the right
		Vec3 look = to.subtract(from);
		if (look.lengthSqr() < 1.0E-6) {
			return;
		}
		look = look.normalize();
		Vec3 right = look.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : right.normalize();
		Vec3 up = right.cross(look).normalize();
		boolean mine = shooter == mc.player && mc.options.getCameraType() == CameraType.FIRST_PERSON;
		int side = shooter instanceof net.minecraft.world.entity.LivingEntity le && le.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT
			? -1 : 1;
		Vec3 muzzle;
		if (mine) {
			float aim = GunFirstPerson.aim();
			muzzle = from.add(look.scale(1.0)).add(right.scale(side * 0.22 * (1 - aim))).add(up.scale(-0.16 * (1 - aim) - 0.05 * aim));
		} else {
			muzzle = from.add(look.scale(0.9)).add(right.scale(side * 0.3)).add(up.scale(-0.2));
		}
		if (muzzle.distanceTo(to) < 0.5) {
			return;
		}
		// Brighter than the gun's particle colour, towards white-hot
		int c = p.color();
		float r = ((c >> 16) & 255) / 255F, g = ((c >> 8) & 255) / 255F, b = (c & 255) / 255F;
		if (kind == 0 && r + g + b < 1.2F) {
			// a grey or dark colour: an ordinary bullet's warm yellow
			r = 1.0F;
			g = 0.82F;
			b = 0.45F;
		}
		if (TRACERS.size() > 200) {
			TRACERS.remove(0);
		}
		TRACERS.add(new Tracer(muzzle, to, r, g, b, System.nanoTime(), kind));
	}

	/** How long a tracer is drawn (s). */
	private static double life(Tracer t) {
		return switch (t.kind) {
			case 1, 2 -> BOLT;
			case 3 -> t.from.distanceTo(t.to) / RAY_SPEED + IMPACT;
			default -> t.from.distanceTo(t.to) / SPEED + GLOW + 0.05;
		};
	}

	private static void draw(Tracer t, long now, Vec3 cam, PoseStack.Pose pose, VertexConsumer vc) {
		if (t.kind == 3) {
			ray(t, now, cam, pose, vc);
			return;
		}
		if (t.kind > 0) {
			bolt(t, now, cam, pose, vc);
			return;
		}
		double age = (now - t.born) / 1.0E9;
		double len = t.from.distanceTo(t.to);
		Vec3 dir = t.to.subtract(t.from).normalize();
		// The streak: its head flying along, its tail STREAK behind
		double head = Math.min(len, age * SPEED), tail = Math.max(0, head - STREAK);
		if (tail < len) {
			Vec3 a = t.from.add(dir.scale(tail)), b = t.from.add(dir.scale(head));
			ribbon(vc, pose, cam, a, b, 0.045F, t.r, t.g, t.b, 0.55F);
			ribbon(vc, pose, cam, a, b, 0.015F, 1.0F, 0.97F, 0.9F, 0.95F);
		}
		// The faint line along the whole path, for an instant
		if (age < GLOW) {
			float fade = (float) (1 - age / GLOW);
			ribbon(vc, pose, cam, t.from, t.to, 0.02F, t.r, t.g, t.b, 0.25F * fade);
		}
	}

	/**
	 * Lightning: a jagged line from end to end (most crooked in the middle), a forked branch or two, a wide glow in the
	 * bolt's colour around a white-hot core; it flickers into a new shape every FLICKER_MS and fades out.
	 */
	private static void bolt(Tracer t, long now, Vec3 cam, PoseStack.Pose pose, VertexConsumer vc) {
		double age = (now - t.born) / 1.0E9;
		float fade = (float) Math.max(0, 1 - age / BOLT);
		// flickering: now and then a frame dimmer
		long frame = (now - t.born) / (FLICKER_MS * 1_000_000L);
		java.util.Random rnd = new java.util.Random(t.born * 31 + frame);
		float alpha = fade * (rnd.nextFloat() < 0.2F ? 0.45F : 1.0F);
		double len = t.from.distanceTo(t.to);
		if (len < 0.05) {
			return;
		}
		Vec3 dir = t.to.subtract(t.from).normalize();
		Vec3 a = dir.cross(new Vec3(0, 1, 0));
		a = a.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : a.normalize();
		Vec3 b = a.cross(dir).normalize();
		int n = Math.max(4, (int) (len / 0.6));
		Vec3[] pts = new Vec3[n + 1];
		double jag = Math.min(0.6, 0.12 + len * 0.05);
		for (int i = 0; i <= n; i++) {
			double f = i / (double) n;
			double off = (i == 0 || i == n) ? 0 : Math.sin(f * Math.PI) * jag;
			pts[i] = t.from.add(dir.scale(len * f)).add(a.scale((rnd.nextDouble() * 2 - 1) * off)).add(b.scale((rnd.nextDouble() * 2 - 1) * off));
		}
		for (int i = 0; i < n; i++) {
			ribbon(vc, pose, cam, pts[i], pts[i + 1], 0.11F, t.r, t.g, t.b, 0.35F * alpha);
			ribbon(vc, pose, cam, pts[i], pts[i + 1], 0.035F, 0.95F, 0.97F, 1.0F, 0.95F * alpha);
		}
		// a fork or two off the side
		int forks = 1 + rnd.nextInt(2);
		for (int k = 0; k < forks && n > 2; k++) {
			Vec3 s = pts[1 + rnd.nextInt(n - 1)];
			Vec3 prev = s;
			Vec3 way = dir.scale(0.5).add(a.scale(rnd.nextDouble() * 2 - 1)).add(b.scale(rnd.nextDouble() * 2 - 1)).normalize();
			for (int j = 0; j < 3; j++) {
				Vec3 next = prev.add(way.scale(0.3 + rnd.nextDouble() * 0.3)).add(a.scale((rnd.nextDouble() - 0.5) * 0.2));
				ribbon(vc, pose, cam, prev, next, 0.06F, t.r, t.g, t.b, 0.3F * alpha);
				ribbon(vc, pose, cam, prev, next, 0.018F, 0.95F, 0.97F, 1.0F, 0.8F * alpha);
				prev = next;
			}
		}
	}

	/**
	 * A Ray Gun bolt: a glowing ball in the gun's colour (green; red Pack-a-Punched) with a spinning ring round it and two
	 * smaller rings trailing, flying to where it hit; there, a ring bursting outwards and a flash.
	 */
	private static void ray(Tracer t, long now, Vec3 cam, PoseStack.Pose pose, VertexConsumer vc) {
		double age = (now - t.born) / 1.0E9;
		double len = t.from.distanceTo(t.to);
		Vec3 dir = t.to.subtract(t.from).normalize();
		double travel = age * RAY_SPEED;
		float spin = (float) (age * 25);
		if (travel < len) {
			Vec3 head = t.from.add(dir.scale(travel));
			Vec3 tail = t.from.add(dir.scale(Math.max(0, travel - 1.6)));
			ribbon(vc, pose, cam, tail, head, 0.07F, t.r, t.g, t.b, 0.3F);                       // trail
			Vec3 a = head.subtract(dir.scale(0.18)), b = head.add(dir.scale(0.18));
			ribbon(vc, pose, cam, a, b, 0.17F, t.r, t.g, t.b, 0.55F);                            // the ball's glow
			ribbon(vc, pose, cam, a, b, 0.07F, 0.9F + 0.1F * t.r, 0.9F + 0.1F * t.g, 0.9F + 0.1F * t.b, 0.95F); // its hot core
			ring(vc, pose, cam, head, dir, 0.24F, spin, 0.035F, t.r, t.g, t.b, 0.9F);
			for (int i = 1; i <= 2; i++) {
				double back = travel - i * 0.45;
				if (back > 0) {
					ring(vc, pose, cam, t.from.add(dir.scale(back)), dir, 0.24F - 0.05F * i, spin + i, 0.025F, t.r, t.g, t.b, 0.6F / i);
				}
			}
			return;
		}
		// the impact: a ring bursting outwards, a flash fading
		float k = (float) Math.min(1, (age - len / RAY_SPEED) / IMPACT);
		ring(vc, pose, cam, t.to, dir, 0.25F + 1.1F * k, spin, 0.06F * (1 - k) + 0.01F, t.r, t.g, t.b, 0.8F * (1 - k));
		Vec3 a = t.to.subtract(0, 0.2 * (1 - k), 0), b = t.to.add(0, 0.2 * (1 - k), 0);
		ribbon(vc, pose, cam, a, b, 0.35F * (1 - k) + 0.02F, t.r, t.g, t.b, 0.5F * (1 - k));
	}

	/** A ring round `centre`, square to `axis`: 16 short strips. */
	private static void ring(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, Vec3 centre, Vec3 axis, float radius, float turn, float width,
		float r, float g, float b, float alpha) {
		Vec3 u = axis.cross(new Vec3(0, 1, 0));
		u = u.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : u.normalize();
		Vec3 v = u.cross(axis).normalize();
		Vec3 prev = null;
		for (int i = 0; i <= 16; i++) {
			double ang = turn + i * Math.PI * 2 / 16;
			Vec3 p = centre.add(u.scale(Math.cos(ang) * radius)).add(v.scale(Math.sin(ang) * radius));
			if (prev != null) {
				ribbon(vc, pose, cam, prev, p, width, r, g, b, alpha);
			}
			prev = p;
		}
	}

	/** A shocked zombie (ZombieShock): little arcs of lightning crawling over its body, flickering into new places. */
	private static void crackle(int id, net.minecraft.world.phys.AABB box, long now, Vec3 cam, PoseStack.Pose pose, VertexConsumer vc) {
		long frame = now / (FLICKER_MS * 1_000_000L);
		java.util.Random rnd = new java.util.Random(id * 7919L + frame);
		for (int k = 0; k < 3; k++) {
			Vec3 a = new Vec3(Mth.lerp(rnd.nextDouble(), box.minX, box.maxX), Mth.lerp(0.15 + rnd.nextDouble() * 0.85, box.minY, box.maxY),
				Mth.lerp(rnd.nextDouble(), box.minZ, box.maxZ));
			Vec3 b = a.add((rnd.nextDouble() - 0.5) * 0.9, (rnd.nextDouble() - 0.5) * 1.0, (rnd.nextDouble() - 0.5) * 0.9);
			int n = 5;
			Vec3 prev = a;
			for (int i = 1; i <= n; i++) {
				double f = i / (double) n;
				Vec3 p = a.add(b.subtract(a).scale(f));
				if (i < n) {
					p = p.add((rnd.nextDouble() - 0.5) * 0.18, (rnd.nextDouble() - 0.5) * 0.18, (rnd.nextDouble() - 0.5) * 0.18);
				}
				ribbon(vc, pose, cam, prev, p, 0.05F, 0.45F, 0.75F, 1.0F, 0.4F);
				ribbon(vc, pose, cam, prev, p, 0.014F, 0.92F, 0.96F, 1.0F, 0.95F);
				prev = p;
			}
		}
	}

	/** A flat strip from a to b, turned to face the camera (drawn both ways round). */
	private static void ribbon(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, Vec3 a, Vec3 b, float width, float r, float g, float bl, float alpha) {
		Vector3f pa = new Vector3f((float) (a.x - cam.x), (float) (a.y - cam.y), (float) (a.z - cam.z));
		Vector3f pb = new Vector3f((float) (b.x - cam.x), (float) (b.y - cam.y), (float) (b.z - cam.z));
		Vector3f along = new Vector3f(pb).sub(pa);
		Vector3f mid = new Vector3f(pa).add(pb).mul(0.5F);
		Vector3f side = along.cross(mid, new Vector3f());
		if (side.lengthSquared() < 1.0E-10F) {
			return;
		}
		side.normalize().mul(width);
		quad(vc, pose, pa, pb, side, r, g, bl, alpha);
	}

	private static void quad(VertexConsumer vc, PoseStack.Pose pose, Vector3f a, Vector3f b, Vector3f s, float r, float g, float bl, float alpha) {
		Vector3f a1 = new Vector3f(a).add(s), a2 = new Vector3f(a).sub(s), b1 = new Vector3f(b).add(s), b2 = new Vector3f(b).sub(s);
		Vector3f[] order = {a1, a2, b2, b1, b1, b2, a2, a1};
		for (Vector3f v : order) {
			vc.addVertex(pose, v.x, v.y, v.z).setColor(r, g, bl, alpha);
		}
	}

	// ---- the trigger, for automatic guns
	private static void tickTrigger(Minecraft mc) {
		boolean down = mc.player != null && mc.gui.screen() == null && mc.options.keyAttack.isDown()
			&& com.mzcod.tacticalmovement.client.GunItems.isGun(mc.player.getMainHandItem());
		if (mc.player == null) {
			triggerDown = false;
			return;
		}
		if (down != triggerDown || (down && ++triggerTicks >= 5)) {
			triggerTicks = 0;
			triggerDown = down;
			// (not gated on canSend: a Paper server doesn't always advertise its plugin channels to Fabric clients)
			try {
				ClientPlayNetworking.send(new TriggerPayload(down));
			} catch (RuntimeException ignored) {
			}
		}
	}
}
