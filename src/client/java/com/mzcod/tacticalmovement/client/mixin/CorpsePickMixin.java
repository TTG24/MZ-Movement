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
		if (id <= -1_000_000 || Corpses.isCorpse(id)) {
			cir.setReturnValue(false);
		}
	}
}
