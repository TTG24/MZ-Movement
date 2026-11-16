package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene;
import com.mzcod.tacticalmovement.client.wounds.Corpses;
import com.mzcod.tacticalmovement.client.wounds.WoundFx;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The game-over cutscene's copies of the player: the body is a ragdoll (Corpses) whose limbs follow the physics, with its
// head hidden (the camera is in it) and arms gone once torn off; a torn-off arm is a copy showing only that arm. The model
// is shared by every player drawn, so the parts the cutscene hides are shown again for everyone else.
@Mixin(PlayerModel.class)
public abstract class PlayerModelCorpseMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void tacticalMovement$cutsceneBody(AvatarRenderState state, CallbackInfo ci) {
		PlayerModel self = (PlayerModel) (Object) this;
		self.head.visible = true;
		self.body.visible = true;
		self.rightArm.visible = true;
		self.leftArm.visible = true;
		self.rightLeg.visible = true;
		self.leftLeg.visible = true;
		if (state.getData(Corpses.POSE) != null) {
			Corpses.applyLimbs(self, state);
		}
		Integer id = state.getData(WoundFx.ENTITY_ID);
		if (id != null) {
			GameOverCutscene.applyParts(self, id);
		}
	}
}
