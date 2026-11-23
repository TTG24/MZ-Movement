package com.mzcod.tacticalmovement.client.animation;

import com.mzcod.tacticalmovement.client.debug.TacticalCrouchDebug;
import com.mzcod.tacticalmovement.client.debug.TacticalPoseDebug;
import com.mzcod.tacticalmovement.movement.MovementStance;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Map;

/**
 * Tactical stances (prone, tactical crouch, diving) of avatars that aren't players, such as the mannequins a mirror
 * plugin uses for a player's reflection: the server sends them the same movement_state as players, under the
 * mannequin's id. Players keep theirs on the entity (MovementStateAccess); these are kept here, with the prone and
 * crouch blends eased each tick exactly as players' are, so a reflection drops prone and crouches like the player.
 */
public final class AvatarStances {

	public static final class Entry {
		public MovementStance stance = MovementStance.STANDING;
		public int diveTicks;
		public float proneBlend;
		public float crouchBlend;
		int missingTicks;
	}

	private static final Map<Integer, Entry> ENTRIES = new HashMap<>();

	private AvatarStances() {
	}

	public static void set(int entityId, MovementStance stance, int diveTicks) {
		Entry e = ENTRIES.computeIfAbsent(entityId, k -> new Entry());
		e.stance = stance;
		e.diveTicks = diveTicks;
		e.missingTicks = 0;
	}

	/** The stance of a non-player avatar, or null when the server hasn't sent one. */
	public static Entry get(int entityId) {
		return ENTRIES.get(entityId);
	}

	/** Once per client tick: ease the blends (same rates as players), and forget avatars that are gone. */
	public static void tick(Minecraft client) {
		if (ENTRIES.isEmpty()) {
			return;
		}
		if (client.level == null) {
			ENTRIES.clear();
			return;
		}
		ENTRIES.entrySet().removeIf(en -> {
			Entry e = en.getValue();
			if (client.level.getEntity(en.getKey()) == null) {
				// spawn packets can land a moment after the stance: give it a few seconds
				return ++e.missingTicks > 100;
			}
			e.missingTicks = 0;
			boolean low = e.stance.isLowProfile();
			boolean crouching = e.stance == MovementStance.CROUCHING;
			float target = low ? 1.0F : 0.0F;
			float rate = low ? TacticalPoseDebug.proneBlendRate : TacticalPoseDebug.proneGetUpRate;
			float prevProne = e.proneBlend;
			float next = prevProne + (target - prevProne) * rate;
			if (Math.abs(target - next) < 0.002F) {
				next = target;
			}
			if (crouching && prevProne > 0.0F) {
				next = e.crouchBlend >= 0.999F ? 0.0F : prevProne;
			}
			e.proneBlend = next;

			float crouchTarget = crouching ? 1.0F : 0.0F;
			float crouchNext = e.crouchBlend + (crouchTarget - e.crouchBlend) * TacticalCrouchDebug.blendRate;
			if (Math.abs(crouchTarget - crouchNext) < 0.002F) {
				crouchNext = crouchTarget;
			}
			if (low && e.crouchBlend > 0.0F) {
				crouchNext = next >= 0.999F ? 0.0F : e.crouchBlend;
			}
			e.crouchBlend = crouchNext;
			return false;
		});
	}
}
