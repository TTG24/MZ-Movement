package com.mzcod.tacticalmovement.client.zombie;

import com.mzcod.tacticalmovement.network.CrawlerPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.HashMap;
import java.util.Map;

/** Which zombies are crawlers (their legs blown off; the server says so, CrawlerPayload) and since when. */
public final class ZombieCrawlers {
	/** entity id -> System.nanoTime() the legs came off (long ago for one first seen already crawling) */
	private static final Map<Integer, Long> SINCE = new HashMap<>();
	private static final long LONG_AGO = 60_000_000_000L;

	private ZombieCrawlers() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(CrawlerPayload.TYPE, (payload, context) -> {
			if (SINCE.putIfAbsent(payload.entityId(), System.nanoTime() - (payload.fresh() ? 0 : LONG_AGO)) != null || !payload.fresh()) {
				return;
			}
			var level = context.client().level;
			var zombie = level == null ? null : level.getEntity(payload.entityId());
			if (zombie != null) {
				com.mzcod.tacticalmovement.client.wounds.GoreChunks.legsOff(payload.entityId(), zombie.position());
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.level == null) {
				SINCE.clear();
			} else if (mc.level.getGameTime() % 100 == 0) {
				SINCE.keySet().removeIf(id -> mc.level.getEntity(id) == null);
			}
		});
	}

	/** Seconds since this zombie lost its legs, or -1 when it still has them. */
	public static float secondsSince(int entityId) {
		Long t = SINCE.get(entityId);
		return t == null ? -1 : (System.nanoTime() - t) / 1.0E9F;
	}

	public static boolean isCrawler(int entityId) {
		return SINCE.containsKey(entityId);
	}
}
