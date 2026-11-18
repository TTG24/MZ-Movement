package com.mzcod.tacticalmovement.network;

import com.mzcod.tacticalmovement.TacticalMovement;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// Client -> server: the grenade key (G) was pressed. Empty payload; the MinecraftZombies plugin handles it.
public record GrenadeRequestPayload() implements CustomPacketPayload {
	public static final Type<GrenadeRequestPayload> TYPE = new Type<>(TacticalMovement.id("grenade"));
	public static final StreamCodec<RegistryFriendlyByteBuf, GrenadeRequestPayload> STREAM_CODEC = StreamCodec.unit(new GrenadeRequestPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
