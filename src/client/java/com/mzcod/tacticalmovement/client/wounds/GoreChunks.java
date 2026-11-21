package com.mzcod.tacticalmovement.client.wounds;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mzcod.tacticalmovement.TacticalMovement;
import com.mzcod.tacticalmovement.network.BloodPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Chunks of zombie torn off by hits: small cubes, skin on the outside and raw meat where they were torn off, that fly out
 * of the wound, tumble, bounce off blocks and lie on the ground for a while before shrinking away. Only this client sees
 * them (players without the mod get the server's particles instead).
 *
 * How many and how hard: a bullet knocks out one or two, a headshot more, a knife slash a couple, a grenade blast a lot,
 * flung away from it; killing hits tear off twice as many.
 *
 * Each chunk is cut from the zombie's own look (ZombieModel.skin), from the part that was hit: its head, its uniform's
 * tunic or sleeves, or its trousers and boots.
 */
public final class GoreChunks {
	/** Patches of the skin to cut chunks from, per part: {u0, v0, width, height} (the sides, not the face). */
	private static final int[][] PATCHES = {
		{0, 8, 32, 5}, // head (the sides and back above the mouth)
		{16, 20, 24, 12}, // body
		{40, 20, 16, 12}, // arm
		{0, 20, 16, 12}, // leg
	};
	public static final int HEAD = 0, BODY = 1, ARM = 2, LEG = 3;
	private static final Identifier MEAT = TacticalMovement.id("textures/entity/zombie_gore.png");
	private static final int MAX = 220;
	private static final double GRAVITY = 0.04, DRAG = 0.98;
	private static final Random RANDOM = new Random();

	private static final class Chunk {
		double x, y, z, ox, oy, oz;
		double vx, vy, vz;
		final Quaternionf rot = new Quaternionf(), oldRot = new Quaternionf();
		final Vector3f spinAxis = new Vector3f();
		float spin;
		final float size; // edge, blocks
		final float u, v; // corner of its patch of zombie skin (pixels)
		final Identifier skin;
		int age;
		final int life;
		boolean resting;

		Chunk(double x, double y, double z, float size, Identifier skin, int part) {
			this.x = ox = x;
			this.y = oy = y;
			this.z = oz = z;
			this.size = size;
			this.skin = skin;
			int[] p = PATCHES[part];
			u = p[0] + RANDOM.nextInt(Math.max(1, p[2] - 3));
			v = p[1] + RANDOM.nextInt(Math.max(1, p[3] - 3));
			life = 160 + RANDOM.nextInt(100);
		}
	}

	private static final List<Chunk> CHUNKS = new ArrayList<>();

	private GoreChunks() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.level == null) {
				CHUNKS.clear();
			} else if (!mc.isPaused()) {
				tick(mc.level);
			}
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
			if (CHUNKS.isEmpty()) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			Vec3 cam = mc.gameRenderer.mainCamera().position();
			PoseStack ps = ctx.poseStack();
			ps.pushPose();
			ps.translate(-cam.x, -cam.y, -cam.z);
			List<Chunk> snapshot = new ArrayList<>(CHUNKS);
			ClientLevel level = mc.level;
			// One batch per look in use
			java.util.Set<Identifier> skins = new java.util.HashSet<>();
			for (Chunk c : snapshot) {
				skins.add(c.skin);
			}
			for (Identifier skin : skins) {
				ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.entityCutout(skin),
					(pose, vc) -> draw(level, snapshot, pt, pose, vc, skin));
			}
			ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.entityCutout(MEAT),
				(pose, vc) -> draw(level, snapshot, pt, pose, vc, null));
			ps.popPose();
		});
	}

	/** A hit landed on a zombie (the server's BloodPayload): tear some chunks off it. */
	public static void onHit(Minecraft mc, BloodPayload hit) {
		if (mc.level == null) {
			return;
		}
		Vec3 dir = new Vec3(hit.dx(), hit.dy(), hit.dz());
		boolean aimed = dir.lengthSqr() > 1.0E-6;
		dir = aimed ? dir.normalize() : Vec3.ZERO;
		int count;
		double speed;
		switch (hit.kind()) {
			case BloodPayload.SHOT -> {
				count = 1 + (RANDOM.nextFloat() < 0.4F ? 1 : 0);
				speed = 0.22;
			}
			case BloodPayload.HEADSHOT -> {
				count = 3;
				speed = 0.3;
			}
			case BloodPayload.SLASH -> {
				count = 2;
				speed = 0.16;
			}
			default -> { // a grenade blast (its direction is away from the blast) or anything else
				count = aimed ? 4 + RANDOM.nextInt(3) : 1;
				speed = 0.35;
			}
		}
		if (hit.killed()) {
			count *= 2;
		}
		// Which part was hit: by height on the zombie (a crawler's lying body is all torso and arms)
		int part = BODY;
		var zombie = mc.level.getEntity(hit.entityId());
		if (hit.kind() == BloodPayload.HEADSHOT) {
			part = HEAD;
		} else if (zombie != null) {
			double h = hit.y() - zombie.getY();
			boolean crawler = com.mzcod.tacticalmovement.client.zombie.ZombieCrawlers.isCrawler(hit.entityId());
			part = crawler ? (RANDOM.nextBoolean() ? BODY : ARM) : h > 1.45 ? HEAD : h < 0.75 ? LEG : RANDOM.nextFloat() < 0.3F ? ARM : BODY;
		}
		Identifier skin = com.mzcod.tacticalmovement.client.zombie.ZombieModel.skin(hit.entityId());
		if (hit.kind() != BloodPayload.HEADSHOT && hit.kind() != BloodPayload.SHOT && hit.kind() != BloodPayload.SLASH) {
			// A blast tears from all over
			for (int i = 0; i < count; i++) {
				int[] parts = {BODY, BODY, ARM, LEG, LEG, HEAD};
				spawn(new Vec3(hit.x(), hit.y(), hit.z()), dir, 1, speed, 0.3, skin, parts[RANDOM.nextInt(parts.length)]);
			}
			return;
		}
		spawn(new Vec3(hit.x(), hit.y(), hit.z()), dir, count, speed, 0.2, skin, part);
	}

	/** A zombie's legs blown off (it became a crawler): big pieces burst out all round its knees and thighs. */
	public static void legsOff(int entityId, Vec3 feet) {
		spawn(feet.add(0, 0.45, 0), Vec3.ZERO, 12, 0.3, 0.5, com.mzcod.tacticalmovement.client.zombie.ZombieModel.skin(entityId), LEG);
	}

	private static void spawn(Vec3 at, Vec3 dir, int count, double speed, double spread, Identifier skin, int part) {
		boolean outward = dir.lengthSqr() < 1.0E-6;
		for (int i = 0; i < count; i++) {
			Chunk c = new Chunk(at.x + (RANDOM.nextDouble() - 0.5) * spread, at.y + (RANDOM.nextDouble() - 0.5) * spread,
				at.z + (RANDOM.nextDouble() - 0.5) * spread, (outward ? 0.1F : 0.07F) + RANDOM.nextFloat() * 0.09F, skin, part);
			if (outward) {
				double a = RANDOM.nextDouble() * Math.PI * 2;
				dir = new Vec3(Math.cos(a), 0, Math.sin(a));
			}
			// Out along the hit, spread, and up a little
			double s = speed * (0.6 + RANDOM.nextDouble() * 0.7);
			c.vx = dir.x * s + (RANDOM.nextDouble() - 0.5) * 0.18;
			c.vy = Math.max(dir.y, 0) * s + 0.08 + RANDOM.nextDouble() * 0.16;
			c.vz = dir.z * s + (RANDOM.nextDouble() - 0.5) * 0.18;
			c.rot.rotationXYZ(RANDOM.nextFloat() * 6.3F, RANDOM.nextFloat() * 6.3F, RANDOM.nextFloat() * 6.3F);
			c.oldRot.set(c.rot);
			c.spinAxis.set(RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F).normalize();
			c.spin = 0.25F + RANDOM.nextFloat() * 0.45F;
			CHUNKS.add(c);
		}
		while (CHUNKS.size() > MAX) {
			CHUNKS.remove(0);
		}
	}

	private static void tick(ClientLevel level) {
		CHUNKS.removeIf(c -> ++c.age > c.life);
		for (Chunk c : CHUNKS) {
			c.ox = c.x;
			c.oy = c.y;
			c.oz = c.z;
			c.oldRot.set(c.rot);
			if (c.resting) {
				continue;
			}
			c.vy -= GRAVITY;
			c.vx *= DRAG;
			c.vy *= DRAG;
			c.vz *= DRAG;
			double r = c.size / 2;
			// One axis at a time, so it slides along walls and floors
			if (solid(level, c.x + c.vx, c.y, c.z, r)) {
				c.vx *= -0.3;
				c.spin *= 0.6F;
			} else {
				c.x += c.vx;
			}
			if (solid(level, c.x, c.y, c.z + c.vz, r)) {
				c.vz *= -0.3;
				c.spin *= 0.6F;
			} else {
				c.z += c.vz;
			}
			if (solid(level, c.x, c.y + c.vy, c.z, r)) {
				if (c.vy < 0) {
					// Hit the ground: bounce a little, scrape to a stop
					c.vx *= 0.55;
					c.vz *= 0.55;
					c.spin *= 0.5F;
					if (Math.abs(c.vy) < 0.08 && c.vx * c.vx + c.vz * c.vz < 0.0004) {
						c.resting = true;
					}
				}
				c.vy *= -0.25;
			} else {
				c.y += c.vy;
			}
			c.rot.rotateAxis(c.spin, c.spinAxis);
		}
	}

	private static boolean solid(ClientLevel level, double x, double y, double z, double r) {
		BlockPos pos = BlockPos.containing(x, y - r, z);
		var shape = level.getBlockState(pos).getCollisionShape(level, pos);
		if (shape.isEmpty()) {
			return false;
		}
		double ly = y - r - pos.getY();
		return ly <= shape.max(net.minecraft.core.Direction.Axis.Y) && ly >= shape.min(net.minecraft.core.Direction.Axis.Y);
	}

	// Cube corners (-1/+1) of each face as seen from outside, counter-clockwise; and the normals
	private static final int[][][] FACES = {
		{{1, 1, -1}, {1, -1, -1}, {-1, -1, -1}, {-1, 1, -1}},
		{{-1, 1, 1}, {-1, -1, 1}, {1, -1, 1}, {1, 1, 1}},
		{{1, 1, 1}, {1, -1, 1}, {1, -1, -1}, {1, 1, -1}},
		{{-1, 1, -1}, {-1, -1, -1}, {-1, -1, 1}, {-1, 1, 1}},
		{{-1, 1, -1}, {-1, 1, 1}, {1, 1, 1}, {1, 1, -1}},
		{{-1, -1, 1}, {-1, -1, -1}, {1, -1, -1}, {1, -1, 1}},
	};
	private static final float[][] NORMALS = {{0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
	private static final float[][] CORNER_UV = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};

	/**
	 * Three faces of each chunk show skin, the other three the meat it was torn from. {@code skinOf}: draw the skin faces of
	 * the chunks with this look, or (null) the meat faces of all of them.
	 */
	private static void draw(ClientLevel level, List<Chunk> chunks, float pt, PoseStack.Pose pose, VertexConsumer vc, Identifier skinOf) {
		boolean skin = skinOf != null;
		if (level == null) {
			return;
		}
		Matrix4f m = new Matrix4f();
		Quaternionf q = new Quaternionf();
		Vector3f p = new Vector3f(), n = new Vector3f();
		for (Chunk c : chunks) {
			if (skin && !skinOf.equals(c.skin)) {
				continue;
			}
			float shrink = Math.min(1.0F, (c.life - c.age - pt) / 30.0F);
			if (shrink <= 0) {
				continue;
			}
			double x = c.ox + (c.x - c.ox) * pt, y = c.oy + (c.y - c.oy) * pt, z = c.oz + (c.z - c.oz) * pt;
			c.oldRot.slerp(c.rot, pt, q);
			float h = c.size / 2 * shrink;
			m.translation((float) x, (float) y, (float) z).rotate(q).scale(h);
			BlockPos at = BlockPos.containing(x, y, z);
			int light = level.getBrightness(LightLayer.BLOCK, at) << 4 | level.getBrightness(LightLayer.SKY, at) << 20;
			for (int f = skin ? 0 : 3; f < (skin ? 3 : 6); f++) {
				m.transformDirection(NORMALS[f][0], NORMALS[f][1], NORMALS[f][2], n).normalize();
				pose.transformNormal(n.x, n.y, n.z, n);
				for (int k = 0; k < 4; k++) {
					int[] cr = FACES[f][k];
					m.transformPosition(cr[0], cr[1], cr[2], p);
					pose.pose().transformPosition(p);
					float u, v;
					if (skin) {
						u = (c.u + CORNER_UV[k][0] * 3) / 64.0F;
						v = (c.v + CORNER_UV[k][1] * 3) / 64.0F;
					} else {
						u = (4 + CORNER_UV[k][0] * 8) / 16.0F;
						v = (4 + CORNER_UV[k][1] * 8) / 16.0F;
					}
					vc.addVertex(p.x, p.y, p.z, 0xFFFFFFFF, u, v, OverlayTexture.NO_OVERLAY, light, n.x, n.y, n.z);
				}
			}
		}
	}
}
