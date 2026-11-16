package com.mzcod.tacticalmovement.network;

import com.mzcod.tacticalmovement.TacticalMovement;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// Server -> client: a zombie just spawned and is digging itself up out of the ground (the MinecraftZombies plugin sends
// it). The client plays the rise animation on that zombie for the given time; the server keeps it frozen meanwhile.
// Wire format: entity id (VarInt), length in ticks (VarInt).
public record RisePayload(int entityId, int ticks) implements CustomPacketPayload {
	public static final Type<RisePayload> TYPE = new Type<>(TacticalMovement.id("rise"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RisePayload> STREAM_CODEC = StreamCodec.of(
		(buf, p) -> {
			buf.writeVarInt(p.entityId);
			buf.writeVarInt(p.ticks);
		},
		buf -> new RisePayload(buf.readVarInt(), buf.readVarInt())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
