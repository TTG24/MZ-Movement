package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.ZombieSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Zombie groans are played attached to the zombie (see ZombieSounds) so they stop when it dies.
@Mixin(ClientPacketListener.class)
public abstract class ClientSoundPacketMixin {
	@Inject(method = "handleSoundEvent", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
		target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V"))
	private void tacticalMovement$attachZombieSound(ClientboundSoundPacket packet, CallbackInfo ci) {
		if (ZombieSounds.play(Minecraft.getInstance(), packet.getSound(), packet.getSource(), packet.getX(), packet.getY(), packet.getZ(),
			packet.getVolume(), packet.getPitch(), packet.getSeed())) {
			ci.cancel();
		}
	}
}
