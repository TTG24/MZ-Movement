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
		PayloadTypeRegistry.serverboundPlay().register(com.mzcod.tacticalmovement.network.GrenadeRequestPayload.TYPE,
			com.mzcod.tacticalmovement.network.GrenadeRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(com.mzcod.tacticalmovement.network.NoclipTogglePayload.TYPE,
			com.mzcod.tacticalmovement.network.NoclipTogglePayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MovementStatePayload.TYPE, MovementStatePayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.mzcod.tacticalmovement.network.GameOverPayload.TYPE,
			com.mzcod.tacticalmovement.network.GameOverPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.mzcod.tacticalmovement.network.NoclipStatePayload.TYPE,
			com.mzcod.tacticalmovement.network.NoclipStatePayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.mzcod.tacticalmovement.network.StaminaPayload.TYPE,
			com.mzcod.tacticalmovement.network.StaminaPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.mzcod.tacticalmovement.network.DownedPayload.TYPE,
			com.mzcod.tacticalmovement.network.DownedPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.mzcod.tacticalmovement.network.BloodPayload.TYPE,
			com.mzcod.tacticalmovement.network.BloodPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.mzcod.tacticalmovement.network.RoundPayload.TYPE,
			com.mzcod.tacticalmovement.network.RoundPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.mzcod.tacticalmovement.network.RisePayload.TYPE,
			com.mzcod.tacticalmovement.network.RisePayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.mzcod.tacticalmovement.network.CrawlerPayload.TYPE,
			com.mzcod.tacticalmovement.network.CrawlerPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.mzcod.tacticalmovement.network.GunAnimPayload.TYPE,
			com.mzcod.tacticalmovement.network.GunAnimPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.mzcod.tacticalmovement.network.TracerPayload.TYPE,
			com.mzcod.tacticalmovement.network.TracerPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.mzcod.tacticalmovement.network.ShockPayload.TYPE,
			com.mzcod.tacticalmovement.network.ShockPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(com.mzcod.tacticalmovement.network.TriggerPayload.TYPE,
			com.mzcod.tacticalmovement.network.TriggerPayload.STREAM_CODEC);

		ServerPlayNetworking.registerGlobalReceiver(MovementRequestPayload.TYPE,
			(payload, context) -> ServerMovementController.handleRequest(context.player(), payload.action()));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			ServerMovementController.synchronizeWith(handler.player));
		ServerTickEvents.END_SERVER_TICK.register(ServerMovementController::tick);

		LOGGER.info("MZ-Movement initialized");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}