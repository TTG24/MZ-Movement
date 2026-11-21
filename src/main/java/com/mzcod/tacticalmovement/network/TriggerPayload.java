package com.mzcod.tacticalmovement.network;

import com.mzcod.tacticalmovement.TacticalMovement;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// Client -> server: the trigger (attack key) went down or up while holding a gun, so the MinecraftZombies plugin can
// keep an automatic gun firing while it is held ("down" is repeated every few ticks while held).
// Wire format: one byte, 1 down, 0 up.
public record TriggerPayload(boolean down) implements CustomPacketPayload {
	public static final Type<TriggerPayload> TYPE = new Type<>(TacticalMovement.id("trigger"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TriggerPayload> STREAM_CODEC = StreamCodec.of(
		(buf, p) -> buf.writeByte(p.down ? 1 : 0),
		buf -> new TriggerPayload(buf.readByte() != 0)
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
