package com.mzcod.tacticalmovement.network;

import com.mzcod.tacticalmovement.TacticalMovement;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// Server -> client: a zombie was hit. The MinecraftZombies plugin sends it, because it changes zombie health directly and
// Minecraft then sends no hurt flash the client could see.
// Wire format: x, y, z (big-endian doubles, where it was hit), entity id (VarInt), killed (boolean), kind (byte: 0 = shot,
// 1 = knife slash, 2 = other, 3 = headshot), and the direction of the shot or swing dx, dy, dz (big-endian floats).
public record BloodPayload(double x, double y, double z, int entityId, boolean killed, int kind, float dx, float dy, float dz)
	implements CustomPacketPayload {
	public static final int SHOT = 0, SLASH = 1, OTHER = 2, HEADSHOT = 3;
	public static final Type<BloodPayload> TYPE = new Type<>(TacticalMovement.id("blood"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BloodPayload> STREAM_CODEC = StreamCodec.of(
		(buf, p) -> {
			buf.writeDouble(p.x);
			buf.writeDouble(p.y);
			buf.writeDouble(p.z);
			buf.writeVarInt(p.entityId);
			buf.writeBoolean(p.killed);
			buf.writeByte(p.kind);
			buf.writeFloat(p.dx);
			buf.writeFloat(p.dy);
			buf.writeFloat(p.dz);
		},
		buf -> {
			double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
			int id = buf.readVarInt();
			boolean killed = buf.readBoolean();
			// Older senders stop here
			if (buf.readableBytes() < 13) {
				return new BloodPayload(x, y, z, id, killed, OTHER, 0, 0, 0);
			}
			return new BloodPayload(x, y, z, id, killed, buf.readByte(), buf.readFloat(), buf.readFloat(), buf.readFloat());
		}
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
