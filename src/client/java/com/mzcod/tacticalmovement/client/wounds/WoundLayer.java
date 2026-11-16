package com.mzcod.tacticalmovement.client.wounds;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

// Draws a zombie's wound texture (bullet holes, slashes) over it, on the same model, so the wounds follow its movement.
public class WoundLayer<S extends LivingEntityRenderState, M extends EntityModel<S>> extends RenderLayer<S, M> {
	public WoundLayer(RenderLayerParent<S, M> renderer) {
		super(renderer);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, S state, float yRot, float xRot) {
		if (state.isBaby || state.isInvisible) {
			return;
		}
		Integer id = state.getData(WoundFx.ENTITY_ID);
		Identifier texture = id == null ? null : WoundFx.textureFor(id);
		if (texture == null) {
			return;
		}
		collector.order(1).submitModel(this.getParentModel(), state, poseStack, RenderTypes.entityTranslucent(texture), lightCoords,
			OverlayTexture.NO_OVERLAY, state.outlineColor);
	}
}
