package com.mzcod.tacticalmovement.network;

import com.mzcod.tacticalmovement.TacticalMovement;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// Server -> client: the zombies game is over (everyone is down). Sent by the MinecraftZombies plugin with where this
// player went down and which way they faced, and the round reached; the mod plays a game-over cutscene and screen.
public record GameOverPayload(double x, double y, double z, float yaw, int rounds) implements CustomPacketPayload {
	public static final Type<GameOverPayload> TYPE = new Type<>(TacticalMovement.id("gameover"));
	public static final StreamCodec<RegistryFriendlyByteBuf, GameOverPayload> STREAM_CODEC = StreamCodec.of(
		(buf, p) -> {
			buf.writeDouble(p.x());
			buf.writeDouble(p.y());
			buf.writeDouble(p.z());
			buf.writeFloat(p.yaw());
			buf.writeVarInt(p.rounds());
		},
		buf -> new GameOverPayload(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readVarInt()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
