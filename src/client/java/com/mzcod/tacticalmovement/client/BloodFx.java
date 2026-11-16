package com.mzcod.tacticalmovement.client;

import com.mzcod.tacticalmovement.TacticalMovement;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

// Blood when a zombie is hit (shot or knifed): a spray of blood at the zombie, and when it was close to the player, blood
// splattered on the screen that stays for a random 3 to 8 seconds, runs down a little, and fades out.
// A hit is seen on the client from the zombie's hurt flash (hurtTime jumps up), so it works with any server.
public final class BloodFx {
	/** Zombies closer than this (blocks) can splatter the screen. */
	private static final double SCREEN_RANGE = 3.5;
	private static final int MAX_SPLATS = 8;
	private static final Random RANDOM = new Random();

	private static final Map<Integer, Integer> lastHurt = new HashMap<>();
	/** Zombies the server just told us about (entity id -> game time), so their hurt flash is not counted again. */
	private static final Map<Integer, Long> recent = new HashMap<>();
	private static final List<Splat> splats = new ArrayList<>();

	private BloodFx() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(BloodFx::tick);
		// Hits the server tells us about (MinecraftZombies sets zombie health directly, so there is no hurt flash to see)
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
			com.mzcod.tacticalmovement.network.BloodPayload.TYPE, (payload, context) -> {
				Minecraft mc = context.client();
				if (mc.player == null || mc.level == null) {
					return;
				}
				recent.put(payload.entityId(), mc.level.getGameTime());
				onHit(mc, new Vec3(payload.x(), payload.y(), payload.z()));
				com.mzcod.tacticalmovement.client.wounds.WoundFx.onHit(mc, payload);
				if (payload.killed()) {
					if (payload.kind() == com.mzcod.tacticalmovement.network.BloodPayload.HEADSHOT) {
						headBurst(mc, payload);
					}
					// Its groans stop with it
					ZombieSounds.killed(payload.entityId());
					// The server removes it right after this: leave a body (with its wounds) behind
					com.mzcod.tacticalmovement.client.wounds.Corpses.spawn(mc, payload);
				}
			});
		// Under the crosshair and hotbar, over the world
		HudElementRegistry.attachElementBefore(VanillaHudElements.CROSSHAIR, TacticalMovement.id("blood"), BloodFx::render);
	}

	// ------------------------------------------------------------------ hits

	private static void tick(Minecraft mc) {
		var player = mc.player;
		var level = mc.level;
		if (player == null || level == null) {
			lastHurt.clear();
			splats.clear();
			return;
		}
		Map<Integer, Integer> seen = new HashMap<>();
		for (Zombie zombie : level.getEntitiesOfClass(Zombie.class, new AABB(player.blockPosition()).inflate(32))) {
			int id = zombie.getId();
			int hurt = zombie.hurtTime;
			Integer before = lastHurt.get(id);
			Long told = recent.get(id);
			boolean alreadyShown = told != null && level.getGameTime() - told < 5;
			if (before != null && hurt > before && !alreadyShown) {
				onHit(mc, zombie.position().add(0, zombie.getBbHeight() * 0.65, 0));
			}
			seen.put(id, hurt);
		}
		lastHurt.clear();
		lastHurt.putAll(seen);
		long now = level.getGameTime();
		recent.values().removeIf(t -> now - t > 40);
	}

	/** A head blown off: a big burst of blood and bits where the head was, thrown along the shot. */
	private static void headBurst(Minecraft mc, com.mzcod.tacticalmovement.network.BloodPayload hit) {
		var level = mc.level;
		if (!(level.getEntity(hit.entityId()) instanceof net.minecraft.world.entity.LivingEntity dead)) {
			return;
		}
		Vec3 head = dead.position().add(0, dead.getBbHeight() * 0.88, 0);
		Vec3 push = new Vec3(hit.dx(), hit.dy(), hit.dz());
		push = push.lengthSqr() > 1.0E-6 ? push.normalize().scale(0.25) : Vec3.ZERO;
		BlockParticleOption chunk = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState());
		BlockParticleOption flesh = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.NETHER_WART_BLOCK.defaultBlockState());
		for (int i = 0; i < 40; i++) {
			level.addParticle(i % 3 == 0 ? flesh : chunk, head.x + (RANDOM.nextDouble() - 0.5) * 0.4, head.y + (RANDOM.nextDouble() - 0.5) * 0.4,
				head.z + (RANDOM.nextDouble() - 0.5) * 0.4, push.x + (RANDOM.nextDouble() - 0.5) * 0.35, 0.05 + RANDOM.nextDouble() * 0.3,
				push.z + (RANDOM.nextDouble() - 0.5) * 0.35);
		}
		for (int i = 0; i < 20; i++) {
			level.addParticle(new DustParticleOptions(RANDOM.nextBoolean() ? 0x8A0303 : 0x5E0000, 1.5F + RANDOM.nextFloat()),
				head.x + (RANDOM.nextDouble() - 0.5) * 0.7, head.y + (RANDOM.nextDouble() - 0.5) * 0.6, head.z + (RANDOM.nextDouble() - 0.5) * 0.7,
				0, 0, 0);
		}
	}

	private static void onHit(Minecraft mc, Vec3 at) {
		var level = mc.level;
		var player = mc.player;
		// Spray: blood chunks and drops flying out of the hit
		BlockParticleOption chunk = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState());
		for (int i = 0; i < 14; i++) {
			level.addParticle(chunk, at.x + (RANDOM.nextDouble() - 0.5) * 0.4, at.y + (RANDOM.nextDouble() - 0.5) * 0.5,
				at.z + (RANDOM.nextDouble() - 0.5) * 0.4, (RANDOM.nextDouble() - 0.5) * 0.3, RANDOM.nextDouble() * 0.25,
				(RANDOM.nextDouble() - 0.5) * 0.3);
		}
		for (int i = 0; i < 10; i++) {
			int color = RANDOM.nextBoolean() ? 0x8A0303 : 0x5E0000;
			level.addParticle(new DustParticleOptions(color, 1.0F + RANDOM.nextFloat()), at.x + (RANDOM.nextDouble() - 0.5) * 0.6,
				at.y + (RANDOM.nextDouble() - 0.5) * 0.7, at.z + (RANDOM.nextDouble() - 0.5) * 0.6, 0, 0, 0);
		}

		// Screen: only when it was right next to the player and in front of them
		double dist = player.position().add(0, 1.0, 0).distanceTo(at);
		if (dist > SCREEN_RANGE) {
			return;
		}
		Vec3 look = player.getViewVector(1.0F);
		Vec3 to = at.subtract(player.getEyePosition()).normalize();
		if (look.dot(to) < 0.3) {
			return;
		}
		double chance = dist <= 2.5 ? 0.85 : 0.4;
		if (RANDOM.nextDouble() > chance) {
			return;
		}
		int count = RANDOM.nextDouble() < 0.3 ? 2 : 1;
		for (int i = 0; i < count; i++) {
			if (splats.size() >= MAX_SPLATS) {
				splats.remove(0);
			}
			splats.add(Splat.random());
		}
	}

	// ------------------------------------------------------------------ screen

	/** Blood spraying out at a point (the game-over cutscene's bites). */
	public static void spray(Vec3 at) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null && mc.player != null) {
			onHit(mc, at);
		}
	}

	/** Adds a splat of blood to the screen (the game-over cutscene, when a zombie claws at the camera). */
	public static void screenSplat() {
		if (splats.size() >= MAX_SPLATS) {
			splats.remove(0);
		}
		splats.add(Splat.random());
	}

	/** Draws the screen blood (used by the cutscene, which hides the normal HUD). */
	public static void drawSplats(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		render(graphics, deltaTracker);
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (splats.isEmpty()) {
			return;
		}
		long now = System.nanoTime();
		int w = graphics.guiWidth(), h = graphics.guiHeight();
		for (Iterator<Splat> it = splats.iterator(); it.hasNext();) {
			Splat s = it.next();
			float age = (now - s.born) / 1.0E9F;
			if (age >= s.life) {
				it.remove();
				continue;
			}
			// Fades in very fast, holds, then fades out over the last 1.5 seconds
			float alpha = Mth.clamp(age / 0.08F, 0.0F, 1.0F) * Mth.clamp((s.life - age) / 1.5F, 0.0F, 1.0F) * s.opacity;
			int a = (int) (alpha * 255.0F) & 0xFF;
			if (a <= 2) {
				continue;
			}
			int cx = Math.round(s.fx * w), cy = Math.round(s.fy * h);
			int dark = (a << 24) | s.dark, light = (Math.min(255, a + 10) << 24) | s.light;
			for (int[] r : s.runs) {
				graphics.fill(cx + r[1], cy + r[0], cx + r[2], cy + r[0] + 1, r[3] == 1 ? light : dark);
			}
			// Drips run down from the bottom of the splat over the first seconds
			float run = Mth.clamp(age / 2.5F, 0.0F, 1.0F);
			run = 1.0F - (1.0F - run) * (1.0F - run);
			for (int[] d : s.drips) {
				int len = Math.round(d[3] * run);
				if (len > 0) {
					graphics.fill(cx + d[0], cy + d[1], cx + d[0] + d[2], cy + d[1] + len, dark);
					// a rounder drop at the end of the drip
					graphics.fill(cx + d[0] - 1, cy + d[1] + len - 2, cx + d[0] + d[2] + 1, cy + d[1] + len + 1, dark);
				}
			}
		}
	}

	/**
	 * One splat of blood on the screen: its shape is drawn once when it appears (a blob made of overlapping circles, with
	 * droplets around it) and kept as rows of pixels, so it costs little to draw each frame.
	 */
	private static final class Splat {
		float fx, fy;
		long born;
		float life;
		float opacity;
		int dark, light;
		/** {row, x from, x to, 1 = lighter centre} relative to the splat's middle. */
		final List<int[]> runs = new ArrayList<>();
		/** {x, y start, width, full length} relative to the splat's middle. */
		final List<int[]> drips = new ArrayList<>();

		static Splat random() {
			Splat s = new Splat();
			s.born = System.nanoTime();
			s.life = 3.0F + RANDOM.nextFloat() * 5.0F; // 3 to 8 seconds
			s.opacity = 0.75F + RANDOM.nextFloat() * 0.2F;
			// Around the outside of the screen, keeping the middle clear: nearly always in the outer ring, now and then a
			// little further in, never near the crosshair
			float inner = RANDOM.nextFloat() < 0.85F ? 0.72F : 0.5F;
			float ring;
			do {
				s.fx = 0.03F + RANDOM.nextFloat() * 0.94F;
				s.fy = 0.03F + RANDOM.nextFloat() * 0.94F;
				float ex = (s.fx - 0.5F) / 0.5F, ey = (s.fy - 0.5F) / 0.5F;
				ring = (float) Math.sqrt(ex * ex + ey * ey);
			} while (ring < inner);
			s.dark = RANDOM.nextBoolean() ? 0x6B0000 : 0x7A0505;
			s.light = 0x9C1010;

			int r = 14 + RANDOM.nextInt(26); // main blob size in screen pixels
			int size = r * 3;
			boolean[][] cover = new boolean[size * 2 + 1][size * 2 + 1];
			boolean[][] centre = new boolean[size * 2 + 1][size * 2 + 1];
			// The blob: overlapping circles around the middle
			int blobs = 7 + RANDOM.nextInt(6);
			for (int i = 0; i < blobs; i++) {
				double ang = RANDOM.nextDouble() * Math.PI * 2;
				double off = RANDOM.nextDouble() * r * 0.55;
				int rr = (int) (r * (0.35 + RANDOM.nextDouble() * 0.5));
				disc(cover, size + (int) (Math.cos(ang) * off), size + (int) (Math.sin(ang) * off), rr);
			}
			disc(centre, size, size, (int) (r * 0.35));
			// Spikes: lines of shrinking drops thrown outward
			int spikes = 4 + RANDOM.nextInt(5);
			for (int i = 0; i < spikes; i++) {
				double ang = RANDOM.nextDouble() * Math.PI * 2;
				double len = r * (0.9 + RANDOM.nextDouble() * 1.1);
				for (double t = r * 0.5; t < len; t += 2) {
					int rr = (int) Math.max(1, (r * 0.18) * (1 - (t / len)));
					disc(cover, size + (int) (Math.cos(ang) * t), size + (int) (Math.sin(ang) * t), rr);
				}
			}
			// Loose droplets around it
			int drops = 6 + RANDOM.nextInt(10);
			for (int i = 0; i < drops; i++) {
				double ang = RANDOM.nextDouble() * Math.PI * 2;
				double d = r * (1.1 + RANDOM.nextDouble() * 1.6);
				disc(cover, size + (int) (Math.cos(ang) * d), size + (int) (Math.sin(ang) * d), 1 + RANDOM.nextInt(Math.max(1, r / 8)));
			}
			// Rows of covered pixels (no overlaps, so the colour stays even)
			for (int y = 0; y < cover.length; y++) {
				int x = 0;
				while (x < cover.length) {
					if (!cover[y][x]) {
						x++;
						continue;
					}
					boolean light = centre[y][x];
					int start = x;
					while (x < cover.length && cover[y][x] && centre[y][x] == light) {
						x++;
					}
					s.runs.add(new int[] {y - size, start - size, x - size, light ? 1 : 0});
				}
			}
			// Drips from the lower edge of the blob
			int drips = 1 + RANDOM.nextInt(3);
			for (int i = 0; i < drips; i++) {
				int dx = (int) ((RANDOM.nextDouble() - 0.5) * r * 1.0);
				int startY = lowestCovered(cover, size + dx) - size - 2;
				if (startY < -size) {
					continue;
				}
				s.drips.add(new int[] {dx, startY, 2 + RANDOM.nextInt(2), (int) (r * (0.6 + RANDOM.nextDouble() * 1.4))});
			}
			return s;
		}

		private static void disc(boolean[][] grid, int cx, int cy, int r) {
			for (int y = -r; y <= r; y++) {
				int half = (int) Math.sqrt(r * r - y * y);
				int row = cy + y;
				if (row < 0 || row >= grid.length) {
					continue;
				}
				for (int x = cx - half; x <= cx + half; x++) {
					if (x >= 0 && x < grid.length) {
						grid[row][x] = true;
					}
				}
			}
		}

		private static int lowestCovered(boolean[][] grid, int x) {
			if (x < 0 || x >= grid.length) {
				return -1;
			}
			for (int y = grid.length - 1; y >= 0; y--) {
				if (grid[y][x]) {
					return y;
				}
			}
			return -1;
		}
	}
}
