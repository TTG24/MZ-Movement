package com.mzcod.tacticalmovement.network;

import com.mzcod.tacticalmovement.TacticalMovement;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// Server -> client: a player fired their gun, or started reloading it (the MinecraftZombies plugin sends it to everyone with
// the mod who can see them), so the gun and both arms animate in first and third person.
// Wire format: entity id (int), kind (byte: 0 shot, 1 reload), ticks (short: the fire delay, or the reload's length).
public record GunAnimPayload(int entityId, byte kind, short ticks) implements CustomPacketPayload {
	public static final byte SHOT = 0, RELOAD = 1, DRINK = 2, PICKUP = 3;
	public static final Type<GunAnimPayload> TYPE = new Type<>(TacticalMovement.id("gun"));
	public static final StreamCodec<RegistryFriendlyByteBuf, GunAnimPayload> STREAM_CODEC = StreamCodec.of(
		(buf, p) -> {
			buf.writeInt(p.entityId);
			buf.writeByte(p.kind);
			buf.writeShort(p.ticks);
		},
		buf -> new GunAnimPayload(buf.readInt(), buf.readByte(), buf.readShort())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
