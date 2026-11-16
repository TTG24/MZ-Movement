package com.mzcod.tacticalmovement.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mzcod.tacticalmovement.client.wounds.Corpses;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Corpses are drawn from their physics (Corpses) instead of Minecraft's death tip-over: their own facing, tilt and limbs,
// and red only for the first moment of the death (a dead mob is otherwise drawn red for as long as it is dead).
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererCorpseMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void tacticalMovement$corpseState(LivingEntity entity, LivingEntityRenderState state, float partialTicks, CallbackInfo ci) {
		Corpses.Pose pose = Corpses.pose(entity.getId(), partialTicks);
		state.setData(Corpses.POSE, pose);
		state.setData(com.mzcod.tacticalmovement.client.animation.ZombieRise.TIME,
			com.mzcod.tacticalmovement.client.animation.ZombieRise.progress(entity.getId()));
		// Cutscene zombies bending over the player (render states are reused, so this is set every frame)
		state.setData(com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene.LEAN,
			com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene.lean(entity.getId(), partialTicks));
		if (com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene.isProp(entity.getId())) {
			state.nameTag = null; // a torn-off arm
		}
		state.setData(com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene.KNEEL,
			com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene.kneel(entity.getId(), partialTicks));
		state.setData(com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene.ARMS,
			com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene.arms(entity.getId(), partialTicks));
		if (pose != null) {
			state.deathTime = 0.0F; // no vanilla tip-over: the physics tips it
			state.nameTag = null; // the cutscene's body of the player has no name over it
			state.hasRedOverlay = pose.red();
			// The renderer turns the model by (180 - bodyRot): 180 makes that no turn at all, the ragdoll's torso sets it
			state.bodyRot = 180.0F;
			state.yRot = 0.0F;
			state.xRot = 0.0F;
		}
	}

	@Inject(method = "setupRotations", at = @At("TAIL"))
	private void tacticalMovement$corpseTilt(LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float entityScale, CallbackInfo ci) {
		Corpses.Pose pose = state.getData(Corpses.POSE);
		if (pose != null) {
			Corpses.applyRotation(poseStack, pose);
		}
		Float lean = state.getData(com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene.LEAN);
		Float kneel = state.getData(com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene.KNEEL);
		float k = kneel == null ? 0.0F : kneel;
		if (k != 0.0F) {
			// Kneeling: down by a leg's length (the legs fold back along the ground, see GameOverCutscene.applyPose)
			poseStack.translate(0.0F, -com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene.KNEEL_DROP * k, 0.0F);
		}
		if (lean != null && lean != 0.0F) {
			// Bends forward (its front is -Z here): from the feet standing, from the hips kneeling
			float hip = com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene.HIP * k;
			poseStack.translate(0.0F, hip, 0.0F);
			poseStack.mulPose(new org.joml.Matrix4f().rotationX((float) Math.toRadians(-lean)));
			poseStack.translate(0.0F, -hip, 0.0F);
		}
	}
}
