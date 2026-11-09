package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.GunItems;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Guns do not use the vanilla attack cooldown: report it as always recovered while a gun is held, so the
// crosshair attack indicator stays hidden and the held item does not dip/"put away" after each shot.
@Mixin(Player.class)
public abstract class GunCooldownMixin {
	@Inject(method = "getAttackStrengthScale", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$gunAttackScale(float a, CallbackInfoReturnable<Float> cir) {
		Player self = (Player) (Object) this;
		if (self.level().isClientSide() && GunItems.isGun(self.getMainHandItem())) {
			cir.setReturnValue(1.0F);
		}
	}

	@Inject(method = "getItemSwapScale", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$gunSwapScale(float a, CallbackInfoReturnable<Float> cir) {
		Player self = (Player) (Object) this;
		if (self.level().isClientSide() && GunItems.isGun(self.getMainHandItem())) {
			cir.setReturnValue(1.0F);
		}
	}
}
