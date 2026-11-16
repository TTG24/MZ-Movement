package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The game-over cutscene moves the camera itself (the player is a spectator somewhere else meanwhile).
@Mixin(Camera.class)
public abstract class CameraCutsceneMixin {
	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	@Final
	private Quaternionf rotation;

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void tacticalMovement$cutsceneCamera(float partialTicks, CallbackInfo ci) {
		float[] view = GameOverCutscene.camera(partialTicks);
		if (view != null) {
			this.setRotation(view[3], view[4]);
			if (view[5] != 0.0F) {
				this.rotation.rotateZ((float) Math.toRadians(view[5]));
			}
			this.setPosition(new Vec3(view[0], view[1], view[2]));
		}
	}
}
