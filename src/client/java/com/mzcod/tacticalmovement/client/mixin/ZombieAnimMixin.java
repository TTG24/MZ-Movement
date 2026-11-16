package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.animation.ZombieAnim;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.monster.zombie.DrownedModel;
import net.minecraft.client.model.monster.zombie.ZombieModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Replaces the vanilla zombie pose (arms stuck straight out) with CoD-style idle / shamble / sprint / swipe animations.
// Applied after vanilla's setupAnim, so it also drives the zombie's armor model (same state, same call).
@Mixin(HumanoidModel.class)
public abstract class ZombieAnimMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
	private void tacticalMovement$zombieAnim(HumanoidRenderState state, CallbackInfo ci) {
		Object self = this;
		// Corpses are dead still: no idle sway, head shakes or twitches
		boolean corpse = state.getData(com.mzcod.tacticalmovement.client.wounds.Corpses.POSE) != null;
		if (self instanceof ZombieModel && !(self instanceof DrownedModel) && ZombieAnim.enabled && !corpse) {
			ZombieAnim.apply((HumanoidModel<?>) self, state);
		}
		// Digging up out of the ground right after spawning (overrides the pose until it is out)
		if (self instanceof ZombieModel && !(self instanceof DrownedModel) && !corpse) {
			com.mzcod.tacticalmovement.client.animation.ZombieRise.apply((HumanoidModel<?>) self, state);
		}
		// Corpses: ragdoll limbs, and no head after a headshot kill (also resets the head for every other zombie)
		if (self instanceof ZombieModel) {
			com.mzcod.tacticalmovement.client.wounds.Corpses.applyLimbs((HumanoidModel<?>) self, state);
			com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene.applyPose((HumanoidModel<?>) self, state);
		}
	}
}
