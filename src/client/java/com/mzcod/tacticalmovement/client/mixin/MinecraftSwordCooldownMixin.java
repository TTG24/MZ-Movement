package com.mzcod.tacticalmovement.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Client side of the sword cooldown: while holding a sword, a click (or held attack button) only
// counts once every 20 ticks. Blocking startAttack here also blocks the swing animation and the
// attack packet, so nothing plays during the cooldown. Held-button repeats go through startAttack too.
@Mixin(Minecraft.class)
public abstract class MinecraftSwordCooldownMixin {
	@Unique
	private static final int SWORD_ATTACK_COOLDOWN_TICKS = 13;

	@Unique
	private long tacticalMovement$lastAttackTick = Long.MIN_VALUE / 2;

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$swordCooldown(CallbackInfoReturnable<Boolean> cir) {
		Minecraft self = (Minecraft) (Object) this;
		if (self.player == null || self.level == null || !self.player.getMainHandItem().is(com.mzcod.tacticalmovement.TacticalItems.MELEE)) {
			return;
		}
		long now = self.level.getGameTime();
		if (now - this.tacticalMovement$lastAttackTick < SWORD_ATTACK_COOLDOWN_TICKS) {
			cir.setReturnValue(false);
			return;
		}
		this.tacticalMovement$lastAttackTick = now;
	}
}
