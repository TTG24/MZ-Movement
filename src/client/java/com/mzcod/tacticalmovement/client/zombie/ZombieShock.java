package com.mzcod.tacticalmovement.client.zombie;

import com.mzcod.tacticalmovement.network.ShockPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zombies shocked by the Wunderwaffe (ShockPayload): ZombieModel plays their shock_N animation and GunTracers draws arcs
 * crawling over them, until the server kills them (a little after the payload's ticks, in case the death is late).
 */
public final class ZombieShock {
	private record Shock(int variant, long until) {
	}

	private static final Map<Integer, Shock> SHOCKED = new ConcurrentHashMap<>();

	private ZombieShock() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(ShockPayload.TYPE, (p, context) ->
			SHOCKED.put(p.entityId(), new Shock(Math.max(1, Math.min(3, p.variant())), System.nanoTime() + (p.ticks() + 6) * 50_000_000L)));
	}

	/** The shock animation (1-3) the zombie is playing, or 0. */
	public static int variant(int entityId) {
		Shock s = SHOCKED.get(entityId);
		if (s == null) {
			return 0;
		}
		if (System.nanoTime() > s.until) {
			SHOCKED.remove(entityId);
			return 0;
		}
		return s.variant;
	}

	/** The entity ids being shocked right now. */
	public static Set<Integer> shocked() {
		long now = System.nanoTime();
		SHOCKED.values().removeIf(s -> now > s.until);
		return SHOCKED.keySet();
	}
}
