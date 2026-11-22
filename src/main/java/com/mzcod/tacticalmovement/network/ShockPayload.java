package com.mzcod.tacticalmovement.network;

import com.mzcod.tacticalmovement.TacticalMovement;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// Server -> client: a zombie shocked by the Wunderwaffe (the MinecraftZombies plugin's ZombieShock): it plays shock_<variant>
// and crackles with lightning for `ticks`, then dies.
// Wire format: entity id (int), variant 1-3 (byte), ticks (short).
public record ShockPayload(int entityId, byte variant, short ticks) implements CustomPacketPayload {
	public static final Type<ShockPayload> TYPE = new Type<>(TacticalMovement.id("shock"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ShockPayload> STREAM_CODEC = StreamCodec.of(
		(buf, p) -> {
			buf.writeInt(p.entityId);
			buf.writeByte(p.variant);
			buf.writeShort(p.ticks);
		},
		buf -> new ShockPayload(buf.readInt(), buf.readByte(), buf.readShort())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
