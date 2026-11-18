package com.mzcod.tacticalmovement.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mzcod.tacticalmovement.client.cutscene.body.BodyRig;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A cutscene body with a rig is drawn bending (BodyRig) instead of as the normal player model.
@Mixin(LivingEntityRenderer.class)
public abstract class BodyRigRenderMixin {
	@org.spongepowered.asm.mixin.Shadow
	public abstract net.minecraft.resources.Identifier getTextureLocation(LivingEntityRenderState state);

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At("HEAD"), cancellable = true)
	private void tacticalMovement$rigBody(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera,
		CallbackInfo ci) {
		if (state instanceof AvatarRenderState avatar && BodyRig.has(avatar.id)) {
			BodyRig.submit(avatar, poseStack, collector);
			ci.cancel();
			return;
		}
		// A cutscene zombie on a rig
		Integer id = state.getData(com.mzcod.tacticalmovement.client.wounds.WoundFx.ENTITY_ID);
		if (id != null && BodyRig.has(id) && !(state instanceof AvatarRenderState)) {
			BodyRig.submitMob(state, id, this.getTextureLocation(state), poseStack, collector);
			ci.cancel();
			return;
		}
		// Ordinary zombies (and husks) in the world, alive: on the bending body too. Babies (a different shape), the dead
		// (the ragdoll draws those), drowned and zombie villagers (other models) stay as they were.
		if (id != null && state instanceof net.minecraft.client.renderer.entity.state.HumanoidRenderState humanoid
			&& (state.entityType == net.minecraft.world.entity.EntityTypes.ZOMBIE || state.entityType == net.minecraft.world.entity.EntityTypes.HUSK)
			&& !state.isBaby && !state.isInvisible && state.deathTime <= 0.0F
			&& state.getData(com.mzcod.tacticalmovement.client.wounds.Corpses.POSE) == null
			&& com.mzcod.tacticalmovement.client.animation.ZombieAnim.enabled) {
			// The Blockbench model (the same one players without the mod see); the bending body if it isn't there
			if (state.entityType == net.minecraft.world.entity.EntityTypes.ZOMBIE
				&& com.mzcod.tacticalmovement.client.zombie.ZombieModel.submit(state, id, this.getTextureLocation(state),
					com.mzcod.tacticalmovement.client.wounds.WoundFx.textureFor(id),
					state.getData(com.mzcod.tacticalmovement.client.animation.ZombieRise.TIME), poseStack, collector)) {
				ci.cancel();
				return;
			}
			BodyRig.submitMobPose(state, com.mzcod.tacticalmovement.client.cutscene.body.ZombieRig.pose(humanoid), this.getTextureLocation(state),
				com.mzcod.tacticalmovement.client.wounds.WoundFx.textureFor(id), poseStack, collector);
			ci.cancel();
		}
	}
}
