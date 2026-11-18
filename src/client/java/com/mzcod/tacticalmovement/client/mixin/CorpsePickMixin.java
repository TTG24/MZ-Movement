package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.wounds.Corpses;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Ragdoll corpses are client-only copies of dead zombies: the crosshair must go straight through them, so a melee hit
// lands on the living zombie behind a body instead of on the (unhittable) body.
@Mixin(LivingEntity.class)
public abstract class CorpsePickMixin {
	@Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$corpsesNotPickable(CallbackInfoReturnable<Boolean> cir) {
		// Corpse copies get client-only ids from -1,000,000 down (also ones no longer simulated, still lying there)
		int id = ((LivingEntity) (Object) this).getId();
		// BlockBlocker's wall colliders are invisible shulkers: no crosshair target or attack indicator on them, so you
		// aim at whatever is behind the wall piece (the server already lets those hits through)
		LivingEntity self = (LivingEntity) (Object) this;
		if (self instanceof net.minecraft.world.entity.monster.Shulker && self.isInvisible()) {
			cir.setReturnValue(false);
			return;
		}
		if (id <= -1_000_000 || Corpses.isCorpse(id)) {
			cir.setReturnValue(false);
		}
	}
}
