package com.mzcod.tacticalmovement.client;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

// Guns come from the MinecraftZombies plugin, which tags them with the custom-data key "tacticalmovement:gun".
public final class GunItems {
	private GunItems() {
	}

	public static boolean isGun(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().toString().contains("tacticalmovement:gun");
	}
}
