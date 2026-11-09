package com.mzcod.tacticalmovement.network;

import com.mzcod.tacticalmovement.TacticalMovement;
import com.mzcod.tacticalmovement.movement.MovementAction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record MovementRequestPayload(MovementAction action) implements CustomPacketPayload {
	public static final Type<MovementRequestPayload> TYPE = new Type<>(TacticalMovement.id("movement_request"));
	public static final StreamCodec<RegistryFriendlyByteBuf, MovementRequestPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT.map(id -> MovementAction.values()[id], MovementAction::ordinal),
		MovementRequestPayload::action,
		MovementRequestPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}