package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.cutscene.body.BodyRig;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// A rigged cutscene body reaches well outside its entity's little box (arms out, legs back): always draw it.
@Mixin(EntityRenderer.class)
public abstract class BodyRigCullMixin {
	@Inject(method = "affectedByCulling", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$rigNotCulled(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (BodyRig.has(entity.getId())) {
			cir.setReturnValue(false);
		}
	}
}
