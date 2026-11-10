package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.TacticalMovement;
import com.mzcod.tacticalmovement.client.animation.TacticalRenderStateAccess;
import com.mzcod.tacticalmovement.movement.MovementStance;
import com.mzcod.tacticalmovement.movement.MovementStateAccess;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.tags.ItemTags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRenderStateMixin {
	private static final java.util.Map<Integer, double[]> TACTICAL_SMOOTH = new java.util.HashMap<>();

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private <T extends Avatar & ClientAvatarEntity> void tacticalMovement$captureState(T entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		TacticalRenderStateAccess access = (TacticalRenderStateAccess) state;
		MovementStance stance = ((MovementStateAccess) entity).tacticalMovement$getStance();
		access.tacticalMovement$setStance(stance);
		access.tacticalMovement$setHoldingSword(entity.getMainHandItem().is(com.mzcod.tacticalmovement.TacticalItems.MELEE));

		// Tag this render state as the LOCAL player's model so the model mixin can gate
		// local-camera effects (the prone head pitch tracking) to the local player only --
		// we must not drive another player's head with our own camera pitch.
		Minecraft mc = Minecraft.getInstance();
		access.tacticalMovement$setLocalPlayer(mc.player != null && entity == mc.player);

		MovementStateAccess movement = (MovementStateAccess) entity;
		access.tacticalMovement$setProneBlend(movement.tacticalMovement$getProneBlend());
		double dx = entity.getDeltaMovement().x;
		double dz = entity.getDeltaMovement().z;
		float horizontal = (float) Math.sqrt(dx * dx + dz * dz);
		// Prone crawl is slow; map a small horizontal speed to the 0..1 stroke weight.
		access.tacticalMovement$setProneMove(Math.min(1.0F, horizontal / 0.045F));
		// Crouch-walk is faster than the prone crawl (server caps it at ~0.12 b/t).
		access.tacticalMovement$setCrouchBlend(movement.tacticalMovement$getCrouchBlend());
		access.tacticalMovement$setCrouchMove(Math.min(1.0F, horizontal / 0.1F));
		// Smoothed sprint / sneak weights (eased per entity by wall-clock time, so they are frame-rate independent).
		long now = System.nanoTime();
		if (TACTICAL_SMOOTH.size() > 64) {
			// Drop entries for entities that haven't been rendered for a few seconds (left the world / unloaded).
			TACTICAL_SMOOTH.values().removeIf(v -> (now - v[2]) > 5.0E9);
		}
		double[] sm = TACTICAL_SMOOTH.computeIfAbsent(entity.getId(), k -> new double[] { 0.0, 0.0, 0.0 });
		float dt = sm[2] == 0.0 ? 0.0F : (float) Math.min(0.1, Math.max(0.0, (now - sm[2]) / 1.0E9));
		sm[2] = now;
		float ease = Math.min(1.0F, dt * 9.0F);
		sm[0] += ((entity.isSprinting() ? 1.0 : 0.0) - sm[0]) * ease;
		sm[1] += ((state.isCrouching ? 1.0 : 0.0) - sm[1]) * ease;
		access.tacticalMovement$setSprintBlend((float) sm[0]);
		access.tacticalMovement$setSneakBlend((float) sm[1]);
	}

	@Mixin(AvatarRenderState.class)
	public static class State implements TacticalRenderStateAccess {
		@Unique private MovementStance tacticalMovement$stance = MovementStance.STANDING;
		@Unique private boolean tacticalMovement$holdingSword;
		@Unique private float tacticalMovement$proneBlend;
		@Unique private float tacticalMovement$proneMove;
		@Unique private boolean tacticalMovement$localPlayer;
		@Unique private float tacticalMovement$crouchBlend;
		@Unique private float tacticalMovement$crouchMove;
		@Unique private float tacticalMovement$sprintBlend;
		@Unique private float tacticalMovement$sneakBlend;

		@Override public MovementStance tacticalMovement$getStance() { return tacticalMovement$stance; }
		@Override public void tacticalMovement$setStance(MovementStance stance) { tacticalMovement$stance = stance; }
		@Override public boolean tacticalMovement$isHoldingSword() { return tacticalMovement$holdingSword; }
		@Override public void tacticalMovement$setHoldingSword(boolean holdingSword) { tacticalMovement$holdingSword = holdingSword; }
		@Override public float tacticalMovement$getProneBlend() { return tacticalMovement$proneBlend; }
		@Override public void tacticalMovement$setProneBlend(float blend) { tacticalMovement$proneBlend = blend; }
		@Override public float tacticalMovement$getProneMove() { return tacticalMovement$proneMove; }
		@Override public void tacticalMovement$setProneMove(float move) { tacticalMovement$proneMove = move; }
		@Override public float tacticalMovement$getCrouchBlend() { return tacticalMovement$crouchBlend; }
		@Override public void tacticalMovement$setCrouchBlend(float blend) { tacticalMovement$crouchBlend = blend; }
		@Override public float tacticalMovement$getCrouchMove() { return tacticalMovement$crouchMove; }
		@Override public void tacticalMovement$setCrouchMove(float move) { tacticalMovement$crouchMove = move; }
		@Override public float tacticalMovement$getSprintBlend() { return tacticalMovement$sprintBlend; }
		@Override public void tacticalMovement$setSprintBlend(float blend) { tacticalMovement$sprintBlend = blend; }
		@Override public float tacticalMovement$getSneakBlend() { return tacticalMovement$sneakBlend; }
		@Override public void tacticalMovement$setSneakBlend(float blend) { tacticalMovement$sneakBlend = blend; }
		@Override public boolean tacticalMovement$isLocalPlayer() { return tacticalMovement$localPlayer; }
		@Override public void tacticalMovement$setLocalPlayer(boolean isLocalPlayer) { tacticalMovement$localPlayer = isLocalPlayer; }
	}
}