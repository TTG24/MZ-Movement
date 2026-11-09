package com.mzcod.tacticalmovement.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Guns (MinecraftZombies) are fired with left click. The click is still sent to the server, but the local attack
// swing animation is skipped while the held item is tagged as a gun (custom data key "tacticalmovement:gun",
// set by the MinecraftZombies plugin).
@Mixin(Minecraft.class)
public abstract class MinecraftGunSwingMixin {
	@Shadow
	public LocalPlayer player;

	@Shadow
	int missTime;

	@Shadow
	abstract boolean startAttack();

	@Unique
	private int tacticalMovement$heldFireTicks;

	// Holding left click with a gun auto-fires (like holding right click used to): re-run the attack every 4 ticks and
	// clear vanilla's 10-tick "miss" delay, which would otherwise cap the fire rate at one shot per half second.
	// The server's per-gun fire delay still decides whether each shot actually goes off. Block-breaking progress
	// is skipped while a gun is held.
	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$gunAutoFire(boolean down, CallbackInfo ci) {
		if (this.player == null || !com.mzcod.tacticalmovement.client.GunItems.isGun(this.player.getMainHandItem())) {
			this.tacticalMovement$heldFireTicks = 0;
			return;
		}
		this.missTime = 0;
		if (down) {
			if (++this.tacticalMovement$heldFireTicks >= 4) {
				this.tacticalMovement$heldFireTicks = 0;
				this.startAttack();
				this.missTime = 0;
			}
		} else {
			this.tacticalMovement$heldFireTicks = 0;
		}
		ci.cancel();
	}

	@Redirect(method = "startAttack", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z"))
	private boolean tacticalMovement$skipGunSwing(LocalPlayer player, InteractionHand hand, SwingAnimation animation, boolean sendToSelf) {
		if (com.mzcod.tacticalmovement.client.GunItems.isGun(player.getItemInHand(hand))) {
			return false;
		}
		return player.swing(hand, animation, sendToSelf);
	}
}
