package com.mzcod.tacticalmovement.mixin;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Server-authoritative sword cooldown: while holding a sword a player can only attack once every
// SWORD_ATTACK_COOLDOWN_TICKS ticks. The client has its own matching gate (see the client mixin) so
// the swing animation is blocked too; the server allows one tick of jitter so a legitimate click that
// arrives slightly early is not dropped.
@Mixin(Player.class)
public abstract class PlayerAttackCooldownMixin {
	@Unique
	private static final int SWORD_ATTACK_COOLDOWN_TICKS = 13;

	@Unique
	private long tacticalMovement$lastSwordAttackTick = Long.MIN_VALUE / 2;

	@Inject(method = "attack", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$swordCooldown(Entity target, CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (!self.getMainHandItem().is(ItemTags.SWORDS)) {
			return;
		}
		long now = self.level().getGameTime();
		int required = self.level().isClientSide() ? SWORD_ATTACK_COOLDOWN_TICKS : SWORD_ATTACK_COOLDOWN_TICKS - 1;
		if (now - this.tacticalMovement$lastSwordAttackTick < required) {
			ci.cancel();
			return;
		}
		this.tacticalMovement$lastSwordAttackTick = now;
	}
}
