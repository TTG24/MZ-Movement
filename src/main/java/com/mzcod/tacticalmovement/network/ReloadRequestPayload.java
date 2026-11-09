package com.mzcod.tacticalmovement.network;

import com.mzcod.tacticalmovement.TacticalMovement;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// Client -> server: the reload key (R) was pressed. Empty payload; the MinecraftZombies plugin handles it.
public record ReloadRequestPayload() implements CustomPacketPayload {
	public static final Type<ReloadRequestPayload> TYPE = new Type<>(TacticalMovement.id("reload"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ReloadRequestPayload> STREAM_CODEC = StreamCodec.unit(new ReloadRequestPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
