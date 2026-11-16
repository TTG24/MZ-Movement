package com.mzcod.tacticalmovement.client.animation;

import com.mzcod.tacticalmovement.TacticalMovement;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

// Glowing orange eyes on zombies (full-bright, like spider eyes), drawn over the reworked dead face.
public class ZombieEyesLayer<S extends LivingEntityRenderState, M extends EntityModel<S>> extends EyesLayer<S, M> {
	private static final RenderType EYES = RenderTypes.eyes(TacticalMovement.id("textures/entity/zombie_eyes.png"));
	private static final RenderType DEAD_EYES = RenderTypes.entityTranslucent(TacticalMovement.id("textures/entity/zombie_eyes.png"));

	public ZombieEyesLayer(RenderLayerParent<S, M> renderer) {
		super(renderer);
	}

	@Override
	public void submit(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.SubmitNodeCollector collector,
		int lightCoords, S state, float yRot, float xRot) {
		// Baby zombies use a different texture layout; only adults get the eyes.
		if (state.isBaby) {
			return;
		}
		if (state.getData(com.mzcod.tacticalmovement.client.wounds.Corpses.POSE) != null) {
			// Dead: the glow is gone, the eyes are dull dark red, lit like the rest of the body
			collector.order(1).submitModel(this.getParentModel(), state, poseStack, DEAD_EYES, lightCoords,
				net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0xFF2A0404, null, state.outlineColor);
			return;
		}
		super.submit(poseStack, collector, lightCoords, state, yRot, xRot);
	}

	@Override
	public RenderType renderType() {
		return EYES;
	}
}
