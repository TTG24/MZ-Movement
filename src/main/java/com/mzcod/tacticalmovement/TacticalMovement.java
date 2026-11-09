package com.mzcod.tacticalmovement;

import com.mzcod.tacticalmovement.network.MovementRequestPayload;
import com.mzcod.tacticalmovement.network.MovementStatePayload;
import com.mzcod.tacticalmovement.server.ServerMovementController;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TacticalMovement implements ModInitializer {
	public static final String MOD_ID = "tacticalmovement";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(MovementRequestPayload.TYPE, MovementRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(com.mzcod.tacticalmovement.network.ReloadRequestPayload.TYPE,
			com.mzcod.tacticalmovement.network.ReloadRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MovementStatePayload.TYPE, MovementStatePayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.mzcod.tacticalmovement.network.StaminaPayload.TYPE,
			com.mzcod.tacticalmovement.network.StaminaPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.mzcod.tacticalmovement.network.DownedPayload.TYPE,
			com.mzcod.tacticalmovement.network.DownedPayload.STREAM_CODEC);

		ServerPlayNetworking.registerGlobalReceiver(MovementRequestPayload.TYPE,
			(payload, context) -> ServerMovementController.handleRequest(context.player(), payload.action()));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			ServerMovementController.synchronizeWith(handler.player));
		ServerTickEvents.END_SERVER_TICK.register(ServerMovementController::tick);

		LOGGER.info("Tactical Movement initialized");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}