package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.animation.TacticalRenderStateAccess;
import com.mzcod.tacticalmovement.movement.MovementStance;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The mod drives the low-profile stances by forcing Pose.SWIMMING (for the low
// hitbox). Vanilla AvatarRenderer.setupRotations reacts to swimAmount>0 by tilting
// the WHOLE model -90deg around X and translating it, which double-rotates against
// TacticalAnimations' per-part flat layout and scrambles the prone pose. When our
// stance is active we suppress that swim tilt and let TacticalAnimations own the
// pose on a clean, upright model frame.
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

	// swimAmount is driven straight to 0 for our stances so vanilla's swim branch
	// never fires, while the rest of setupRotations (the base body-yaw rotation)
	// still runs untouched. Done with a local redirect via field write at HEAD so we
	// don't @Overwrite and risk drift with vanilla internals. We do NOT restore it
	// after setupRotations — keeping it at 0 means setupAnim uses the normal
	// walking/idle animation instead of the swimming animation during the get-up.

	// True when we own the model pose and must suppress vanilla's swim tilt:
	//   - prone (low-profile stance), OR
	//   - the GET-UP: stance is already STANDING (or CROUCHING, prone -> crouch) but we're still rising out of the low/swim
	//     state (proneBlend > 0). Without this, the moment the stance flips to STANDING the
	//     vanilla swim-exit tilt (whole-model -90° X + arms-twist) re-fires and clobbers our
	//     custom get-up pose until the blend finishes -- the "stuck until it ends" look.
	private static boolean tacticalMovement$ownsPose(MovementStance stance, float blend) {
		return stance.isLowProfile()
			|| ((stance == MovementStance.STANDING || stance == MovementStance.CROUCHING) && blend > 0.001F);
	}

	@Inject(method = "setupRotations", at = @At("HEAD"))
	private void tacticalMovement$silenceSwimTilt(AvatarRenderState state, PoseStack poseStack, float bodyRot, float entityScale, CallbackInfo ci) {
		TacticalRenderStateAccess access = (TacticalRenderStateAccess) state;
		if (tacticalMovement$ownsPose(access.tacticalMovement$getStance(), access.tacticalMovement$getProneBlend())) {
			state.swimAmount = 0.0F;
		}
	}

	// NOTE: We intentionally do NOT restore swimAmount after setupRotations. Keeping it at 0
	// for the entire render pipeline means setupAnim uses the normal walking/idle animation
	// instead of the swimming animation during the get-up transition. This gives the lerp a
	// live, natural walking target instead of a frozen/swimming pose.
}
