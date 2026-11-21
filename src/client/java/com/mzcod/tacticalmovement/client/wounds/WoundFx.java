package com.mzcod.tacticalmovement.client.wounds;

import com.mojang.blaze3d.platform.NativeImage;
import com.mzcod.tacticalmovement.TacticalMovement;
import com.mzcod.tacticalmovement.network.BloodPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

// Bullet holes and knife slashes on zombies, where they were really hit. Each wounded zombie gets its own see-through
// 64x64 texture laid out like the zombie skin; a wound is painted onto the skin pixels of the body part and side that was
// hit, and the texture is drawn over the zombie (WoundLayer), so the wounds move with its head, arms and legs.
public final class WoundFx {
	/** The entity id of the zombie a render state belongs to (set by EntityRendererIdMixin). */
	public static final RenderStateDataKey<Integer> ENTITY_ID = RenderStateDataKey.create(() -> "tacticalmovement:entity_id");

	private static final Random RANDOM = new Random();
	private static final Map<Integer, Wounds> WOUNDS = new HashMap<>();

	/** A trickle of blood running down from a wound, a pixel at a time, until it has run its length or reaches the edge. */
	private static final class Trickle {
		final Wounds wounds;
		final int x;
		final int[] rect;
		int y;
		int left;
		int wait;
		final int every;

		Trickle(Wounds wounds, int x, int y, int length, int[] rect) {
			this.wounds = wounds;
			this.x = x;
			this.y = y;
			this.left = length;
			this.rect = rect;
			this.every = 3 + RANDOM.nextInt(6); // ticks per pixel: some run, some ooze
			this.wait = every;
		}
	}

	private static final java.util.List<Trickle> TRICKLES = new java.util.ArrayList<>();
	private static int counter;

	private WoundFx() {
	}

	private record Wounds(Identifier id, DynamicTexture texture) {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.level == null) {
				clear(mc);
				return;
			}
			runTrickles();
			if (mc.level.getGameTime() % 20 != 0) {
				return;
			}
			// Zombies that are gone (killed, out of range) give their texture back
			for (Iterator<Map.Entry<Integer, Wounds>> it = WOUNDS.entrySet().iterator(); it.hasNext();) {
				var e = it.next();
				var entity = mc.level.getEntity(e.getKey());
				if (entity == null || entity.isRemoved()) {
					mc.getTextureManager().release(e.getValue().id());
					Wounds gone = e.getValue();
					TRICKLES.removeIf(t -> t.wounds == gone);
					it.remove();
				}
			}
		});
	}

	/** Moves every trickle on: its head (a bright bead) a pixel down when it's due, the pixel behind turning to a run. */
	private static void runTrickles() {
		if (TRICKLES.isEmpty()) {
			return;
		}
		java.util.Set<Wounds> changed = new java.util.HashSet<>();
		for (Iterator<Trickle> it = TRICKLES.iterator(); it.hasNext();) {
			Trickle t = it.next();
			if (--t.wait > 0) {
				continue;
			}
			t.wait = t.every;
			NativeImage img = t.wounds.texture().getPixels();
			if (img == null) {
				it.remove();
				continue;
			}
			put(img, t.x, t.y, DRIP, t.rect, false);
			t.y++;
			t.left--;
			if (t.left <= 0 || t.y >= t.rect[1] + t.rect[3]) {
				it.remove();
			} else {
				put(img, t.x, t.y, BLOOD, t.rect, false);
				// Now and then it wanders a pixel sideways, like blood finding its way down
				if (RANDOM.nextInt(6) == 0) {
					int nx = t.x + (RANDOM.nextBoolean() ? 1 : -1);
					if (nx >= t.rect[0] && nx < t.rect[0] + t.rect[2]) {
						it.remove();
						TRICKLES.add(new Trickle(t.wounds, nx, t.y, t.left, t.rect));
						changed.add(t.wounds);
						break; // (the list changed: the rest move next tick)
					}
				}
			}
			changed.add(t.wounds);
		}
		for (Wounds w : changed) {
			w.texture().upload();
		}
	}

	private static void trickle(Wounds w, int x, int y, int length, int[] rect) {
		if (TRICKLES.size() < 400) {
			TRICKLES.add(new Trickle(w, x, y, length, rect));
		}
	}

	private static void clear(Minecraft mc) {
		TRICKLES.clear();
		for (Wounds w : WOUNDS.values()) {
			mc.getTextureManager().release(w.id());
		}
		WOUNDS.clear();
	}

	/** A head shot off: the neck stump (top of the body) bloody and torn. */
	public static void paintNeck(Minecraft mc, int entityId) {
		Wounds w = WOUNDS.computeIfAbsent(entityId, id -> {
			DynamicTexture texture = new DynamicTexture(() -> "tacticalmovement wounds", 64, 64, true);
			Identifier location = TacticalMovement.id("wounds/" + (counter++));
			mc.getTextureManager().register(location, texture);
			return new Wounds(location, texture);
		});
		NativeImage img = w.texture().getPixels();
		if (img == null) {
			return;
		}
		// Top of the body on the skin: 8 x 4 at (20, 16); the outer ring torn red, the middle dark
		for (int x = 20; x < 28; x++) {
			for (int y = 16; y < 20; y++) {
				boolean edge = x == 20 || x == 27 || y == 16 || y == 19;
				img.setPixel(x, y, edge ? (RANDOM.nextBoolean() ? BLOOD : WOUND) : (RANDOM.nextInt(3) == 0 ? WOUND : HOLE));
			}
		}
		// Blood run down the top of the chest and back
		for (int i = 0; i < 4; i++) {
			int x = 21 + RANDOM.nextInt(6);
			int len = 1 + RANDOM.nextInt(4);
			for (int y = 20; y < 20 + len; y++) {
				img.setPixel(x, y, DRIP);
				img.setPixel(x + 12, y, DRIP);
			}
		}
		w.texture().upload();
	}

	/** A killed zombie's wounds go to its corpse. */
	public static void transfer(int fromId, int toId) {
		Wounds w = WOUNDS.remove(fromId);
		if (w != null) {
			WOUNDS.put(toId, w);
		}
	}

	/** The wound texture to draw over this zombie, or null when it has none. */
	public static Identifier textureFor(int entityId) {
		Wounds w = WOUNDS.get(entityId);
		return w == null ? null : w.id();
	}

	/** A hit the server told us about: paints a bullet hole or slash where it landed. */
	public static void onHit(Minecraft mc, BloodPayload hit) {
		if (hit.kind() == BloodPayload.OTHER || mc.level == null) {
			return;
		}
		if (!(mc.level.getEntity(hit.entityId()) instanceof LivingEntity zombie) || zombie.isBaby()) {
			return;
		}
		// Into the zombie's own frame: forward, to its right, and up from its feet
		double yaw = Math.toRadians(zombie.yBodyRot);
		Vec3 fwd = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		Vec3 d = new Vec3(hit.x(), hit.y(), hit.z()).subtract(zombie.position());
		Vec3 dir = new Vec3(hit.dx(), hit.dy(), hit.dz());
		if (dir.lengthSqr() < 1.0E-6) {
			dir = d.scale(-1); // no direction given: as if it came from outside towards where it hit
		}
		double scale = zombie.getBbHeight() / 1.95; // for bigger or smaller zombies
		double mx = -d.dot(right) * 16 / scale; // model x: + is the zombie's left
		double my = 24 - d.y * 16 / scale; // model y: down from the neck
		double mz = -d.dot(fwd) * 16 / scale; // model z: - is its front
		double df = dir.dot(fwd), dr = dir.dot(right);

		Part part = Part.at(mx, my);
		// The side the shot came in from: front/back, or its left/right side
		int face;
		if (Math.abs(df) >= Math.abs(dr)) {
			face = df < 0 ? FRONT : BACK;
		} else {
			face = dr > 0 ? PLUS_X : MINUS_X;
		}
		int[] uv = part.uv(face, mx, my, mz);
		int[] rect = part.faceRect(face);

		Wounds w = WOUNDS.computeIfAbsent(hit.entityId(), id -> {
			DynamicTexture texture = new DynamicTexture(() -> "tacticalmovement wounds", 64, 64, true);
			Identifier location = TacticalMovement.id("wounds/" + (counter++));
			mc.getTextureManager().register(location, texture);
			return new Wounds(location, texture);
		});
		NativeImage img = w.texture().getPixels();
		if (img == null) {
			return;
		}
		if (hit.kind() == BloodPayload.SLASH) {
			paintSlash(img, uv[0], uv[1], rect);
			// A cut bleeds along its length
			for (int i = 0; i < 2 + RANDOM.nextInt(2); i++) {
				trickle(w, uv[0] + RANDOM.nextInt(5) - 2, uv[1] + 1, 3 + RANDOM.nextInt(6), rect);
			}
		} else {
			paintBullet(img, uv[0], uv[1], rect);
			trickle(w, uv[0], uv[1] + 2, 3 + RANDOM.nextInt(7), rect);
			if (RANDOM.nextBoolean()) {
				trickle(w, uv[0] + (RANDOM.nextBoolean() ? 1 : -1), uv[1] + 1, 2 + RANDOM.nextInt(4), rect);
			}
		}
		w.texture().upload();
	}

	// ------------------------------------------------------------------ painting

	private static final int HOLE = 0xFF1A0000, WOUND = 0xFF6E0505, BLOOD = 0xFF8C0A0A, DRIP = 0xE0700000;

	private static void paintBullet(NativeImage img, int u, int v, int[] rect) {
		// A dark hole ringed with torn red, and a little blood running down from it
		put(img, u - 1, v, WOUND, rect, false);
		put(img, u + 1, v, WOUND, rect, false);
		put(img, u, v - 1, WOUND, rect, false);
		put(img, u, v + 1, WOUND, rect, false);
		if (RANDOM.nextBoolean()) {
			put(img, u + (RANDOM.nextBoolean() ? 1 : -1), v + (RANDOM.nextBoolean() ? 1 : -1), BLOOD, rect, false);
		}
		put(img, u, v, HOLE, rect, true);
		// (the blood running from it: a trickle, WoundFx.runTrickles)
	}

	private static void paintSlash(NativeImage img, int u, int v, int[] rect) {
		// A diagonal cut: a dark line with red edges above it
		int len = 4 + RANDOM.nextInt(3);
		int sx = RANDOM.nextBoolean() ? 1 : -1;
		double slope = 0.4 + RANDOM.nextDouble() * 0.8;
		for (int i = -len / 2; i <= len / 2; i++) {
			int x = u + i * sx;
			int y = v + (int) Math.round(i * slope);
			put(img, x, y - 1, WOUND, rect, false);
			put(img, x, y, HOLE, rect, true);
		}
	}

	/** Paints a pixel if it is on the wounded side. Lighter wound colours never paint over a hole already there. */
	private static void put(NativeImage img, int x, int y, int argb, int[] rect, boolean force) {
		if (x < rect[0] || x >= rect[0] + rect[2] || y < rect[1] || y >= rect[1] + rect[3]) {
			return;
		}
		if (!force && img.getPixel(x, y) == HOLE) {
			return;
		}
		img.setPixel(x, y, argb);
	}

	// ------------------------------------------------------------------ zombie skin layout (Minecraft's humanoid model)

	private static final int FRONT = 0, BACK = 1, MINUS_X = 2, PLUS_X = 3;

	private enum Part {
		HEAD(-4, 4, -8, 0, -4, 4, 0, 0, false),
		BODY(-4, 4, 0, 12, -2, 2, 16, 16, false),
		RIGHT_ARM(-8, -4, 0, 12, -2, 2, 40, 16, false),
		LEFT_ARM(4, 8, 0, 12, -2, 2, 40, 16, true),
		RIGHT_LEG(-3.9, 0.1, 12, 24, -2, 2, 0, 16, false),
		LEFT_LEG(-0.1, 3.9, 12, 24, -2, 2, 0, 16, true);

		final double x0, x1, y0, y1, z0, z1;
		final int u0, v0, w, h, d;
		final boolean mirror;

		Part(double x0, double x1, double y0, double y1, double z0, double z1, int u0, int v0, boolean mirror) {
			this.x0 = x0;
			this.x1 = x1;
			this.y0 = y0;
			this.y1 = y1;
			this.z0 = z0;
			this.z1 = z1;
			this.u0 = u0;
			this.v0 = v0;
			this.w = (int) Math.round(x1 - x0);
			this.h = (int) Math.round(y1 - y0);
			this.d = (int) Math.round(z1 - z0);
			this.mirror = mirror;
		}

		static Part at(double mx, double my) {
			if (my <= 0) {
				return HEAD;
			}
			if (my <= 12) {
				return mx < -4 ? RIGHT_ARM : mx > 4 ? LEFT_ARM : BODY;
			}
			return mx < 0 ? RIGHT_LEG : LEFT_LEG;
		}

		/** {u, v, width, height} of a side of this part on the 64x64 skin. */
		int[] faceRect(int face) {
			int top = v0 + d;
			// A mirrored part (left arm/leg) has its two sides swapped on the skin
			int f = face;
			if (mirror && (face == MINUS_X || face == PLUS_X)) {
				f = face == MINUS_X ? PLUS_X : MINUS_X;
			}
			return switch (f) {
				case FRONT -> new int[] {u0 + d, top, w, h};
				case BACK -> new int[] {u0 + 2 * d + w, top, w, h};
				case MINUS_X -> new int[] {u0, top, d, h};
				default -> new int[] {u0 + d + w, top, d, h};
			};
		}

		/** The skin pixel for a point on the given side of this part. */
		int[] uv(int face, double mx, double my, double mz) {
			int[] r = faceRect(face);
			double x = clamp(mx, x0, x1 - 0.01), y = clamp(my, y0, y1 - 0.01), z = clamp(mz, z0, z1 - 0.01);
			double across = switch (face) {
				case FRONT -> mirror ? x1 - x : x - x0;
				case BACK -> mirror ? x - x0 : x1 - x;
				case MINUS_X -> z - z0;
				default -> z1 - z;
			};
			int u = r[0] + (int) Math.floor(Math.min(r[2] - 1, Math.max(0, across)));
			int v = r[1] + (int) Math.floor(Math.min(r[3] - 1, Math.max(0, y - y0)));
			return new int[] {u, v};
		}

		private static double clamp(double v, double lo, double hi) {
			return Math.max(lo, Math.min(hi, v));
		}
	}
}
