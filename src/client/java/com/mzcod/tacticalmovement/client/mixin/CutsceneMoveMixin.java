package com.mzcod.tacticalmovement.client.mixin;

import com.mzcod.tacticalmovement.client.cutscene.GameOverCutscene;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// During a game-over cutscene the movement keys do nothing: you don't walk, jump, sneak or sprint underneath it (which
// also kept the first-person arm bobbing along).
@Mixin(KeyboardInput.class)
public abstract class CutsceneMoveMixin extends ClientInput {
	@Inject(method = "tick", at = @At("TAIL"))
	private void tacticalMovement$noMoving(CallbackInfo ci) {
		if (GameOverCutscene.locksInput()) {
			this.keyPresses = Input.EMPTY;
			this.moveVector = Vec2.ZERO;
		}
	}
}
