package com.mzcod.tacticalmovement.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// Server -> client: noclip is on or off for this player (NoClip Paper plugin, channel noclip:state). While it is on and the
// player is flying, the client moves through blocks in creative; the plugin lets the server accept that movement.
public record NoclipStatePayload(boolean on) implements CustomPacketPayload {
	public static final Type<NoclipStatePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("noclip", "state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, NoclipStatePayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.BOOL, NoclipStatePayload::on,
		NoclipStatePayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
