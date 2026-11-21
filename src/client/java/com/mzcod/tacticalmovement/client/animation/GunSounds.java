package com.mzcod.tacticalmovement.client.animation;

import com.mzcod.tacticalmovement.network.GunAnimPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * The gun handling sounds (the guns pack's mzguns:handling.*), played in time with the animations (GunAnim): a magazine
 * out and in, a bolt worked, a pump racked, shells pushed in, a break-action or a revolver's cylinder opened and closed.
 * Each is heard where the gun's holder is.
 */
public final class GunSounds {
	private record Due(int entity, long at, String sound) {
	}

	private static final List<Due> QUEUE = new ArrayList<>();

	private GunSounds() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(GunSounds::tick);
	}

	/** A shot or reload began (GunAnim's receiver): queue its sounds over its length. */
	static void onAnim(Minecraft mc, GunAnimPayload payload) {
		if (mc.level == null) {
			return;
		}
		Entity e = mc.level.getEntity(payload.entityId());
		if (!(e instanceof LivingEntity holder)) {
			return;
		}
		var stack = holder.getMainHandItem();
		GunAnim.Style style = GunAnim.style(stack);
		if (style == null) {
			return;
		}
		String gunId = GunAnim.gunId(stack);
		float ticks = Math.max(1, payload.ticks());
		if (payload.kind() == GunAnimPayload.SHOT) {
			// working the action between shots
			switch (style) {
				case BOLT -> {
					at(payload.entityId(), ticks * 0.32F, "bolt_open");
					at(payload.entityId(), ticks * 0.8F, "bolt_close");
				}
				case PUMP -> at(payload.entityId(), ticks * 0.35F, "pump");
				default -> {
				}
			}
			return;
		}
		int id = payload.entityId();
		if (payload.kind() == GunAnimPayload.DRINK) {
			return; // (the server plays the drink's sounds, for everyone)
		}
		if (payload.kind() == GunAnimPayload.PICKUP) {
			at(id, ticks * 0.3F, "inspect");
			at(id, ticks * 0.7F, "charge");
			return;
		}
		switch (style) {
			case BOLT -> {
				at(id, ticks * 0.06F, "bolt_open");
				for (float p : new float[] {0.125F, 0.375F, 0.625F, 0.875F}) at(id, ticks * p, "shell");
				at(id, ticks * 0.95F, "bolt_close");
			}
			case PUMP -> {
				for (float p : new float[] {0.125F, 0.375F, 0.625F, 0.875F}) at(id, ticks * p, "shell");
				at(id, ticks * 0.94F, "pump");
			}
			case DOUBLE -> {
				at(id, ticks * 0.05F, "break_open");
				at(id, ticks * 0.25F, "shell");
				at(id, ticks * 0.75F, "shell");
				at(id, ticks * 0.92F, "break_close");
			}
			case REVOLVER -> {
				at(id, ticks * 0.1F, "cyl_open");
				at(id, ticks * 0.42F, "shell");
				at(id, ticks * 0.55F, "shell");
				at(id, ticks * 0.85F, "cyl_close");
			}
			default -> {
				at(id, ticks * 0.25F, "mag_out");
				at(id, ticks * 0.7F, "mag_in");
				if (GunFirstPerson.hasMagazine(gunId) && style != GunAnim.Style.PISTOL) {
					at(id, ticks * 0.86F, "charge");
				}
			}
		}
	}

	/** The I key's inspect: a rattle as it's turned each way. */
	public static void inspect(int entity) {
		at(entity, GunAnim.INSPECT_TICKS * 0.12F, "inspect");
		at(entity, GunAnim.INSPECT_TICKS * 0.5F, "inspect");
	}

	private static void at(int entity, float ticks, String sound) {
		if (QUEUE.size() > 256) {
			QUEUE.clear();
		}
		QUEUE.add(new Due(entity, System.nanoTime() + (long) (ticks * 50_000_000L), sound));
	}

	private static void tick(Minecraft mc) {
		if (QUEUE.isEmpty()) {
			return;
		}
		if (mc.level == null) {
			QUEUE.clear();
			return;
		}
		long now = System.nanoTime();
		QUEUE.removeIf(d -> {
			if (d.at > now) {
				return false;
			}
			Entity e = mc.level.getEntity(d.entity);
			if (e != null) {
				SoundEvent ev = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("mzguns", "handling." + d.sound));
				mc.level.playLocalSound(e.getX(), e.getY() + 1.2, e.getZ(), ev, SoundSource.PLAYERS, 0.8F,
					0.95F + java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 0.1F, false);
			}
			return true;
		});
	}
}
