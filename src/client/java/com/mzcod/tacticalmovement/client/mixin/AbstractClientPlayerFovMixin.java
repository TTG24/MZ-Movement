package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.movement.MovementStance;
import com.mzcod.tacticalmovement.movement.MovementStateAccess;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Vanilla scales the field of view by the movement-speed attribute (so sprinting widens it and a slow
// effect narrows it). The tactical crouch halves the movement-speed attribute (see ServerMovementController),
// which would squeeze the FOV by ~25%. Undo exactly that part so the FOV is unchanged while crouch-walking.
//
// Vanilla factor = (speed / walkingSpeed + 1) / 2 and `speed` already includes the -50% crouch modifier, so
// the unslowed factor is (2*ratio + 1) / 2 where ratio = speed / walkingSpeed. Multiply the result by
// unslowed / current = (2*ratio + 1) / (ratio + 1).
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerFovMixin {
	@Inject(method = "getFieldOfViewModifier", at = @At("RETURN"), cancellable = true)
	private void tacticalMovement$keepFovWhileCrouched(boolean firstPerson, float effectScale, CallbackInfoReturnable<Float> cir) {
		AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
		// Key off the synced attribute modifier itself (not the stance), so the correction is active exactly
		// when the speed is actually halved and the two never disagree for a frame.
		var attribute = self.getAttribute(Attributes.MOVEMENT_SPEED);
		if (attribute == null || !attribute.hasModifier(com.mzcod.tacticalmovement.server.ServerMovementController.CROUCH_SPEED_ID)) {
			return;
		}
		float current = (float) attribute.getValue();
		float withoutCrouch = (float) (current * 2.0);
		float walking = self.getAbilities().getWalkingSpeed();
		float curFactor = (current / walking + 1.0F) / 2.0F;
		float fullFactor = (withoutCrouch / walking + 1.0F) / 2.0F;
		if (curFactor > 0.0F) {
			cir.setReturnValue(cir.getReturnValue() * fullFactor / curFactor);
		}
	}
}
