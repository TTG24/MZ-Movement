package com.mzcod.tacticalmovement.client;

import com.mzcod.tacticalmovement.network.NoclipStatePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

// Creative noclip, switched on by the server (NoClip plugin, operators only). While on and flying, the player passes through
// blocks without leaving creative (see LocalPlayerNoclipMixin), so building still works.
public final class NoclipClient {
	public static volatile boolean active;

	private NoclipClient() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(NoclipStatePayload.TYPE, (payload, context) -> active = payload.on());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> active = false);
	}
}
