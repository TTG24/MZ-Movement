package com.mzcod.tacticalmovement.client;

import com.mzcod.tacticalmovement.TacticalMovement;
import com.mzcod.tacticalmovement.movement.MovementStateAccess;
import com.mzcod.tacticalmovement.movement.StaminaConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

// Small stamina bar just above the hotbar. It is hidden while stamina is full and fades in when it drops (or
// while exhausted). Turns amber when low and red while exhausted (locked out of sprint/dive).
public final class StaminaHud {
	private static final int WIDTH = 100;
	private static final int HEIGHT = 3;
	private static long lastActive;

	private StaminaHud() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, TacticalMovement.id("stamina"), StaminaHud::render);
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		var player = mc.player;
		if (player == null || player.isCreative() || player.isSpectator()) {
			return;
		}
		MovementStateAccess state = (MovementStateAccess) player;
		float max = StaminaConfig.max;
		float fraction = Mth.clamp(state.tacticalMovement$getStamina() / max, 0.0F, 1.0F);
		boolean exhausted = state.tacticalMovement$isExhausted();
		long now = System.nanoTime();
		if (fraction < 0.995F || exhausted) {
			lastActive = now;
		}
		float idle = (now - lastActive) / 1.0E9F;
		float alpha = 1.0F - Mth.clamp((idle - 0.8F) / 0.5F, 0.0F, 1.0F);
		if (alpha <= 0.01F) {
			return;
		}
		int a = (int) (alpha * 255.0F) & 0xFF;
		int rgb = exhausted ? 0xE04848 : (fraction < 0.15F ? 0xFFB347 : 0x9FD8FF);
		int x0 = graphics.guiWidth() / 2 - WIDTH / 2;
		int y0 = graphics.guiHeight() - 66; // above the hearts/armor row, clear of the item-name tooltip
		int bg = ((a * 5 / 8) << 24);
		graphics.fill(x0 - 1, y0 - 1, x0 + WIDTH + 1, y0 + HEIGHT + 1, bg);
		int filled = Math.round(fraction * WIDTH);
		if (filled > 0) {
			graphics.fill(x0, y0, x0 + filled, y0 + HEIGHT, (a << 24) | rgb);
		}
	}
}
