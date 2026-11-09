package com.mzcod.tacticalmovement.network;

import com.mzcod.tacticalmovement.TacticalMovement;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// Server -> owning client: 0 = no effect, 1..9 = strength of the last-stand black-and-white shader (lowered while being revived).
public record DownedPayload(int level) implements CustomPacketPayload {
	public static final Type<DownedPayload> TYPE = new Type<>(TacticalMovement.id("downed"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DownedPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, DownedPayload::level,
		DownedPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
