package com.mzcod.tacticalmovement.network;

import com.mzcod.tacticalmovement.TacticalMovement;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// Server -> client: the round the player's zombies game is on (0 = not in a game). Sent by the MinecraftZombies plugin;
// the mod draws it as tally marks in the bottom-left corner instead of the plugin's title in the middle of the screen.
public record RoundPayload(int round) implements CustomPacketPayload {
	public static final Type<RoundPayload> TYPE = new Type<>(TacticalMovement.id("round"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RoundPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, RoundPayload::round,
		RoundPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
