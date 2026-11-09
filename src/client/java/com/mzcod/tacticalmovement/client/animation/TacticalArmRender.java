package com.mzcod.tacticalmovement.client.animation;

import com.mzcod.tacticalmovement.TacticalMovement;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

public final class TacticalArmRender {
	public static final Identifier UNIFORM_TEXTURE = TacticalMovement.id("textures/entity/uniform_arm.png");
	public static final RenderType UNIFORM_RENDER_TYPE = RenderTypes.entitySolid(UNIFORM_TEXTURE);

	private TacticalArmRender() {
	}
}
