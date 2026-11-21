package com.mzcod.tacticalmovement.client.zombie;

import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

/**
 * Weather on the zombies: snow settles on one standing in the snow, rain soaks one in the rain (or in water), desert dust
 * clings to one in a desert or badlands. It builds up over a few seconds and wears off over several more once they're
 * out of it, and a corpse keeps what its zombie had. The overlay textures are zombie_fx_snow / _wet / _dust.png
 * (tools/models/zombie_fx.py); the server's models for players without the mod do the same (ZombieModelHook.weatherAt).
 */
public final class ZombieFx {
	public static final int SNOW = 0, WET = 1, DUST = 2, KINDS = 3;
	/** Ticks to build up fully / to wear off fully. */
	private static final float[] BUILD = {120, 60, 80}, FADE = {400, 240, 300};
	private static final java.util.Set<net.minecraft.resources.ResourceKey<Biome>> DRY = java.util.Set.of(Biomes.DESERT,
		Biomes.BADLANDS, Biomes.ERODED_BADLANDS, Biomes.WOODED_BADLANDS);

	private static final class State {
		final float[] level = new float[KINDS];
		int wanted = -1;
		int seen;
	}

	private static final Map<Integer, State> STATES = new HashMap<>();
	private static ClientLevel lastLevel;
	private static int ticks;

	private ZombieFx() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(ZombieFx::tick);
	}

	/** How much of this weather the zombie with this (server) id wears: 0..1. */
	public static float strength(int entityId, int kind) {
		State s = STATES.get(entityId);
		return s == null ? 0 : s.level[kind];
	}

	/** What the weather where the zombie stands would put on it: SNOW, WET, DUST, or -1 for nothing. */
	static int wanted(ClientLevel level, Zombie z) {
		if (level.dimension() != Level.OVERWORLD) {
			return -1;
		}
		BlockPos pos = z.blockPosition();
		if (z.isInWater()) {
			return WET;
		}
		if (!level.canSeeSky(pos)) {
			return -1;
		}
		var biome = level.getBiome(pos);
		if (DRY.stream().anyMatch(biome::is)) {
			return DUST;
		}
		if (level.getRainLevel(1.0F) < 0.2F) {
			return -1;
		}
		Biome.Precipitation p = biome.value().getPrecipitationAt(pos, level.getSeaLevel());
		return p == Biome.Precipitation.SNOW ? SNOW : p == Biome.Precipitation.RAIN ? WET : -1;
	}

	private static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level != lastLevel) {
			STATES.clear();
			lastLevel = level;
		}
		if (level == null || mc.isPaused()) {
			return;
		}
		ticks++;
		for (var e : level.entitiesForRendering()) {
			if (!(e instanceof Zombie z) || z.isBaby()) {
				continue;
			}
			State s = STATES.computeIfAbsent(z.getId(), k -> new State());
			s.seen = ticks;
			if ((ticks + z.getId()) % 20 == 0) {
				s.wanted = wanted(level, z);
			}
			for (int k = 0; k < KINDS; k++) {
				s.level[k] = Math.max(0, Math.min(1, s.level[k] + (s.wanted == k ? 1 / BUILD[k] : -1 / FADE[k])));
			}
		}
		// Forget the ones gone for a long while (a corpse keeps what its zombie had for as long as it lies there)
		if (ticks % 600 == 0) {
			STATES.values().removeIf(s -> ticks - s.seen > 6000);
		}
	}
}
