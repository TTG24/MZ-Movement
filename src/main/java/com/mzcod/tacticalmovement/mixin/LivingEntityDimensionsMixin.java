package com.mzcod.tacticalmovement.mixin;

import com.mzcod.tacticalmovement.movement.CrouchConfig;
import com.mzcod.tacticalmovement.movement.MovementStance;
import com.mzcod.tacticalmovement.movement.MovementStateAccess;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Avatar overrides getDefaultDimensions (players use a fixed pose->dimensions map), so this must target Avatar:
// a mixin on LivingEntity would never run for players.
@Mixin(Avatar.class)
public abstract class LivingEntityDimensionsMixin {
	@Unique
	private static final EntityDimensions TACTICAL_MOVEMENT_PRONE_DIMENSIONS = EntityDimensions.scalable(0.6F, 0.6F).withEyeHeight(0.48F);

	@Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$useStanceDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
		if ((Object) this instanceof MovementStateAccess access) {
			MovementStance stance = access.tacticalMovement$getStance();
			if (stance.isLowProfile()) {
				cir.setReturnValue(TACTICAL_MOVEMENT_PRONE_DIMENSIONS);
			} else if (stance == MovementStance.CROUCHING) {
				cir.setReturnValue(EntityDimensions.scalable(0.6F, CrouchConfig.boxHeight).withEyeHeight(CrouchConfig.eyeHeight));
			}
		}
	}
}
