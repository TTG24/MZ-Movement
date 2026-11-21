package com.mzcod.tacticalmovement.network;

import com.mzcod.tacticalmovement.TacticalMovement;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// Server -> client: one bullet's path (the MinecraftZombies plugin sends one per shot, one per pellet for a shotgun), drawn
// as a tracer streaking from the gun.
// Wire format: shooter entity id (int), from x, y, z and to x, y, z (doubles), colour (int rgb).
public record TracerPayload(int shooter, double fx, double fy, double fz, double tx, double ty, double tz, int color)
	implements CustomPacketPayload {
	public static final Type<TracerPayload> TYPE = new Type<>(TacticalMovement.id("tracer"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TracerPayload> STREAM_CODEC = StreamCodec.of(
		(buf, p) -> {
			buf.writeInt(p.shooter);
			buf.writeDouble(p.fx);
			buf.writeDouble(p.fy);
			buf.writeDouble(p.fz);
			buf.writeDouble(p.tx);
			buf.writeDouble(p.ty);
			buf.writeDouble(p.tz);
			buf.writeInt(p.color);
		},
		buf -> new TracerPayload(buf.readInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
			buf.readDouble(), buf.readInt())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
