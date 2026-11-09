package com.mzcod.tacticalmovement.network;

import com.mzcod.tacticalmovement.TacticalMovement;
import com.mzcod.tacticalmovement.movement.MovementStance;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record MovementStatePayload(int entityId, MovementStance stance, int diveTicks) implements CustomPacketPayload {
	public static final Type<MovementStatePayload> TYPE = new Type<>(TacticalMovement.id("movement_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, MovementStatePayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, MovementStatePayload::entityId,
		ByteBufCodecs.VAR_INT.map(id -> MovementStance.values()[id], MovementStance::ordinal), MovementStatePayload::stance,
		ByteBufCodecs.VAR_INT, MovementStatePayload::diveTicks,
		MovementStatePayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}