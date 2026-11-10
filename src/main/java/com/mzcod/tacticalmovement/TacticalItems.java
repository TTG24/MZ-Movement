package com.mzcod.tacticalmovement;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** The item the tactical melee animations (sword arm pose, swing, cooldown, inspect) apply to. */
public final class TacticalItems {
	/** The knife item. Swords are free to be used as guns. Keep in sync with the plugin's melee item. */
	public static final Item MELEE = Items.END_ROD;

	private TacticalItems() {
	}
}
