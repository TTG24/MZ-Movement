package com.mzcod.tacticalmovement.network;

import com.mzcod.tacticalmovement.TacticalMovement;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// Server -> client: a grenade blew this zombie's legs off; it is a crawler now (the MinecraftZombies plugin sends it).
// "fresh" is true when it just happened (the client plays the fall), false when the player only now sees an old crawler.
// Wire format: entity id (int), fresh (byte 0/1).
public record CrawlerPayload(int entityId, boolean fresh) implements CustomPacketPayload {
	public static final Type<CrawlerPayload> TYPE = new Type<>(TacticalMovement.id("crawler"));
	public static final StreamCodec<RegistryFriendlyByteBuf, CrawlerPayload> STREAM_CODEC = StreamCodec.of(
		(buf, p) -> {
			buf.writeInt(p.entityId);
			buf.writeByte(p.fresh ? 1 : 0);
		},
		buf -> new CrawlerPayload(buf.readInt(), buf.readByte() != 0)
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
