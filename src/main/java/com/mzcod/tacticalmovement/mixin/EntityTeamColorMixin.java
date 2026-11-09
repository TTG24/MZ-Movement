package com.mzcod.tacticalmovement.mixin;

import com.mzcod.tacticalmovement.movement.MovementStance;
import com.mzcod.tacticalmovement.movement.MovementStateAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// A downed (last stand) player's glow outline takes any colour: the server sends the colour, which shifts from green through
// yellow to red as the time to be revived runs out. For DOWNED the "dive ticks" field of the state packet carries the RGB.
@Mixin(Entity.class)
public abstract class EntityTeamColorMixin {
	@Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$downedGlowColor(CallbackInfoReturnable<Integer> cir) {
		if ((Object) this instanceof Player player) {
			MovementStateAccess state = (MovementStateAccess) player;
			if (state.tacticalMovement$getStance() == MovementStance.DOWNED) {
				cir.setReturnValue(state.tacticalMovement$getDiveTicks() & 0xFFFFFF);
			}
		}
	}
}
