package com.mzcod.tacticalmovement.network;

import com.mzcod.tacticalmovement.TacticalMovement;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// Server -> owning client: the player's current stamina and whether they are exhausted (locked out of sprint/dive).
public record StaminaPayload(float stamina, boolean exhausted) implements CustomPacketPayload {
	public static final Type<StaminaPayload> TYPE = new Type<>(TacticalMovement.id("stamina"));
	public static final StreamCodec<RegistryFriendlyByteBuf, StaminaPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.FLOAT, StaminaPayload::stamina,
		ByteBufCodecs.BOOL, StaminaPayload::exhausted,
		StaminaPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
