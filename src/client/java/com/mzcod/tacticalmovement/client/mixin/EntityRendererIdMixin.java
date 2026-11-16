package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.wounds.WoundFx;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Remembers which entity a render state was made for, so render layers can find per-entity extras (zombie wounds).
@Mixin(EntityRenderer.class)
public abstract class EntityRendererIdMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void tacticalMovement$rememberEntity(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
		state.setData(WoundFx.ENTITY_ID, entity.getId());
	}
}
