package com.mzcod.tacticalmovement.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Zombie groans (the resource pack makes them long) are sent by the server as a sound at a fixed spot, so they kept playing
// after the zombie died. Those sounds are attached to the zombie that made them instead: they follow it, and stop the moment
// it dies or is removed.
public final class ZombieSounds {
	private static final Set<String> ATTACHED = Set.of(
		"minecraft:entity.zombie.ambient", "minecraft:entity.husk.ambient", "minecraft:entity.drowned.ambient",
		"minecraft:entity.zombie_villager.ambient", "mz:zombie_sprint", "mz:zombie_step");
	/** Entity ids the server said were killed, with the game time, so their sounds stop before the entity is gone. */
	private static final Map<Integer, Long> KILLED = new ConcurrentHashMap<>();

	private ZombieSounds() {
	}

	public static void killed(int entityId) {
		Minecraft mc = Minecraft.getInstance();
		long now = mc.level == null ? 0 : mc.level.getGameTime();
		KILLED.entrySet().removeIf(e -> now - e.getValue() > 200);
		KILLED.put(entityId, now);
	}

	/** True when the sound was played attached to a zombie (the positional one is then not played). */
	public static boolean play(Minecraft mc, Holder<SoundEvent> sound, SoundSource source, double x, double y, double z, float volume,
		float pitch, long seed) {
		if (mc.level == null || !ATTACHED.contains(sound.value().location().toString())) {
			return false;
		}
		LivingEntity best = null;
		double bestD = 1.5 * 1.5;
		for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, new AABB(x - 1.5, y - 1.5, z - 1.5, x + 1.5, y + 1.5, z + 1.5),
			e -> !(e instanceof Player))) {
			double d = e.distanceToSqr(x, y, z);
			if (d < bestD) {
				bestD = d;
				best = e;
			}
		}
		if (best == null) {
			return false;
		}
		if (!best.isAlive() || KILLED.containsKey(best.getId())) {
			return true; // a dead zombie makes no sound
		}
		mc.getSoundManager().play(new Attached(sound.value(), source, volume, pitch, best, seed));
		return true;
	}

	private static final class Attached extends EntityBoundSoundInstance {
		private final LivingEntity mob;

		Attached(SoundEvent event, SoundSource source, float volume, float pitch, LivingEntity mob, long seed) {
			super(event, source, volume, pitch, mob, seed);
			this.mob = mob;
		}

		@Override
		public void tick() {
			if (!mob.isAlive() || KILLED.containsKey(mob.getId())) {
				stop();
				return;
			}
			super.tick();
		}
	}
}
