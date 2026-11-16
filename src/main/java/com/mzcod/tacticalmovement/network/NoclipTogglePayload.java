package com.mzcod.tacticalmovement.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// Client -> server: the noclip key was pressed. Handled by the NoClip Paper plugin (channel noclip:toggle), which checks
// that the player is an operator.
public record NoclipTogglePayload() implements CustomPacketPayload {
	public static final Type<NoclipTogglePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("noclip", "toggle"));
	public static final StreamCodec<RegistryFriendlyByteBuf, NoclipTogglePayload> STREAM_CODEC = StreamCodec.unit(new NoclipTogglePayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
