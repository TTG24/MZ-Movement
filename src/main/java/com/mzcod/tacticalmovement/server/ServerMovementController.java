package com.mzcod.tacticalmovement.server;

import com.mzcod.tacticalmovement.TacticalMovement;
import com.mzcod.tacticalmovement.movement.MovementAction;
import com.mzcod.tacticalmovement.movement.MovementStance;
import com.mzcod.tacticalmovement.movement.MovementStateAccess;
import com.mzcod.tacticalmovement.network.MovementStatePayload;
import com.mzcod.tacticalmovement.network.StaminaPayload;
import com.mzcod.tacticalmovement.movement.StaminaConfig;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

public final class ServerMovementController {
	public static final int DIVE_DURATION_TICKS = 18;
	public static final Identifier CROUCH_SPEED_ID = TacticalMovement.id("crouch_speed");
	private static final double DIVE_HORIZONTAL_SPEED = 0.8;
	private static final double DIVE_VERTICAL_SPEED = 0.18;
	public static final int DIVE_COOLDOWN_TICKS = 20;
	private static final java.util.Map<java.util.UUID, Long> LAST_DIVE = new java.util.HashMap<>();

	private ServerMovementController() {
	}

	public static void handleRequest(ServerPlayer player, MovementAction action) {
		if (action == MovementAction.HELLO || !canChangeStance(player)) {
			return;
		}

		MovementStateAccess state = (MovementStateAccess) player;
		switch (action) {
			case HELLO -> {
			}
			case ENTER_PRONE -> setStance(player, MovementStance.PRONE, 0);
			case EXIT_PRONE -> setStance(player, MovementStance.STANDING, 0);
			case DOLPHIN_DIVE -> startDive(player, state);
			case ENTER_CROUCH -> {
				if (state.tacticalMovement$getStance() != MovementStance.DIVING) {
					player.setSprinting(false);
					setStance(player, MovementStance.CROUCHING, 0);
				}
			}
			case EXIT_CROUCH -> {
				if (state.tacticalMovement$getStance() == MovementStance.CROUCHING) {
					setStance(player, MovementStance.STANDING, 0);
				}
			}
		}
	}

	public static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			MovementStateAccess state = (MovementStateAccess) player;
			tickStamina(player, state);
			if (!canChangeStance(player)) {
				if (state.tacticalMovement$getStance() != MovementStance.STANDING) {
					setStance(player, MovementStance.STANDING, 0);
				}
				continue;
			}

			if (state.tacticalMovement$getStance() == MovementStance.DIVING) {
				int remaining = state.tacticalMovement$getDiveTicks() - 1;
				state.tacticalMovement$setDiveTicks(Math.max(remaining, 0));
				if (remaining <= 0 || player.onGround() && state.tacticalMovement$getDiveTicks() < DIVE_DURATION_TICKS - 4) {
					setStance(player, MovementStance.PRONE, 0);
				}
			} else {
				if (state.tacticalMovement$getStance() == MovementStance.CROUCHING) {
					player.setSprinting(false);
				}
				limitHorizontalSpeed(player, state.tacticalMovement$getStance());
			}
		}
	}

	// Stamina: sprinting drains it, a dive costs a chunk, and it regenerates after a short rest. Running dry locks
	// sprint/dive out until it climbs back over the recovery threshold. Creative/spectator players are unlimited.
	private static void tickStamina(ServerPlayer player, MovementStateAccess state) {
		float max = StaminaConfig.max;
		float stamina = state.tacticalMovement$getStamina();
		boolean exhausted = state.tacticalMovement$isExhausted();
		if (player.isCreative() || player.isSpectator()) {
			stamina = max;
			exhausted = false;
		} else {
			// The sprint flag is what the client reports; vanilla already clears it when the player stops moving or
			// hits a wall, so it is the reliable signal. (Position/velocity deltas on the server are unreliable: the
			// player has usually already been moved by the incoming packet before this tick runs.)
			if (player.isSprinting() && player.isShiftKeyDown()) {
				player.setSprinting(false); // sneaking cancels sprint
			}
			boolean sprinting = player.isSprinting();
			if (sprinting) {
				stamina -= StaminaConfig.sprintDrain;
				state.tacticalMovement$setStaminaDelay(StaminaConfig.regenDelay);
			} else if (state.tacticalMovement$getStaminaDelay() > 0) {
				state.tacticalMovement$setStaminaDelay(state.tacticalMovement$getStaminaDelay() - 1);
			} else if (state.tacticalMovement$getStance() != MovementStance.DIVING) {
				stamina += StaminaConfig.regen;
			}
			if (stamina <= 0.0F) {
				stamina = 0.0F;
				if (!exhausted) {
					exhausted = true;
					state.tacticalMovement$setStaminaDelay(StaminaConfig.exhaustedDelay);
				}
			} else if (exhausted && stamina >= StaminaConfig.recoverThreshold) {
				exhausted = false;
			}
			stamina = Math.min(stamina, max);
		}
		state.tacticalMovement$setStamina(stamina);
		state.tacticalMovement$setExhausted(exhausted);
		if (exhausted && player.isSprinting()) {
			player.setSprinting(false);
		}
		syncStamina(player, state);
	}

	private static void syncStamina(ServerPlayer player, MovementStateAccess state) {
		// Encode the rounded value + exhausted flag so a change in either triggers a packet.
		int code = Math.round(state.tacticalMovement$getStamina()) * 2 + (state.tacticalMovement$isExhausted() ? 1 : 0);
		if (code != state.tacticalMovement$getStaminaSent()) {
			state.tacticalMovement$setStaminaSent(code);
			ServerPlayNetworking.send(player, new StaminaPayload(state.tacticalMovement$getStamina(), state.tacticalMovement$isExhausted()));
		}
	}

	public static void synchronizeWith(ServerPlayer recipient) {
		// Force a fresh stamina packet for the joining player.
		((MovementStateAccess) recipient).tacticalMovement$setStaminaSent(-1);
		for (ServerPlayer player : recipient.level().getServer().getPlayerList().getPlayers()) {
			MovementStateAccess state = (MovementStateAccess) player;
			ServerPlayNetworking.send(recipient, new MovementStatePayload(
				player.getId(), state.tacticalMovement$getStance(), state.tacticalMovement$getDiveTicks()));
		}
	}

	private static void startDive(ServerPlayer player, MovementStateAccess state) {
		if (!player.onGround() || state.tacticalMovement$getStance() == MovementStance.DIVING) {
			return;
		}
		long now = player.level().getGameTime();
		Long last = LAST_DIVE.get(player.getUUID());
		if (last != null && now - last < DIVE_COOLDOWN_TICKS) {
			return;
		}
		boolean unlimited = player.isCreative() || player.isSpectator();
		if (!unlimited && (state.tacticalMovement$isExhausted() || state.tacticalMovement$getStamina() < StaminaConfig.diveCost)) {
			return;
		}

		Vec3 look = player.getLookAngle();
		Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
		if (horizontal.lengthSqr() < 1.0E-4) {
			return;
		}

		Vec3 direction = horizontal.normalize();
		if (!unlimited) {
			state.tacticalMovement$setStamina(state.tacticalMovement$getStamina() - StaminaConfig.diveCost);
			state.tacticalMovement$setStaminaDelay(StaminaConfig.regenDelay);
		}
		LAST_DIVE.put(player.getUUID(), now);
		player.setSprinting(false);
		player.setDeltaMovement(direction.x * DIVE_HORIZONTAL_SPEED, DIVE_VERTICAL_SPEED, direction.z * DIVE_HORIZONTAL_SPEED);
		player.connection.send(new ClientboundSetEntityMotionPacket(player));
		setStance(player, MovementStance.DIVING, DIVE_DURATION_TICKS);
	}

	private static void setStance(ServerPlayer player, MovementStance stance, int diveTicks) {
		MovementStateAccess state = (MovementStateAccess) player;
		state.tacticalMovement$setStance(stance);
		state.tacticalMovement$setDiveTicks(diveTicks);
		applyStanceSpeed(player, stance);
		player.refreshDimensions();

		MovementStatePayload payload = new MovementStatePayload(player.getId(), stance, diveTicks);
		for (ServerPlayer recipient : PlayerLookup.all(player.level().getServer())) {
			ServerPlayNetworking.send(recipient, payload);
		}
	}

	// Crouch-walking is half of normal walking speed. Done with a (transient) movement-speed attribute
	// modifier so the server and the client's own movement prediction agree (the attribute is synced to
	// the client), instead of only clamping the speed server-side.
	private static void applyStanceSpeed(ServerPlayer player, MovementStance stance) {
		AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed == null) {
			return;
		}
		speed.removeModifier(CROUCH_SPEED_ID);
		if (stance == MovementStance.CROUCHING) {
			speed.addTransientModifier(new AttributeModifier(CROUCH_SPEED_ID, -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
	}

	private static void limitHorizontalSpeed(ServerPlayer player, MovementStance stance) {
		double maximum = switch (stance) {
			case PRONE -> 0.09;
			case CROUCHING -> 0.12;
			default -> Double.POSITIVE_INFINITY;
		};
		Vec3 velocity = player.getDeltaMovement();
		double horizontalSpeed = velocity.horizontalDistance();
		if (horizontalSpeed > maximum) {
			double scale = maximum / horizontalSpeed;
			player.setDeltaMovement(velocity.x * scale, velocity.y, velocity.z * scale);
		}
	}

	private static boolean canChangeStance(ServerPlayer player) {
		return player.isAlive() && !player.isSpectator() && !player.isPassenger() && !player.isFallFlying() && !player.isSwimming();
	}
}