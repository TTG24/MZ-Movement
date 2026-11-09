package com.mzcod.tacticalmovement.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mzcod.tacticalmovement.TacticalMovement;
import com.mzcod.tacticalmovement.client.animation.TacticalAnimations;
import com.mzcod.tacticalmovement.client.animation.TacticalArmRender;
import com.mzcod.tacticalmovement.client.debug.TacticalCrouchDebug;
import com.mzcod.tacticalmovement.client.debug.TacticalPoseDebug;
import com.mzcod.tacticalmovement.movement.MovementStance;
import com.mzcod.tacticalmovement.movement.MovementAction;
import com.mzcod.tacticalmovement.movement.MovementStateAccess;
import com.mzcod.tacticalmovement.network.MovementRequestPayload;
import com.mzcod.tacticalmovement.network.MovementStatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

public final class TacticalMovementClient implements ClientModInitializer {
	private static final int PRONE_HOLD_TICKS = 8;
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(TacticalMovement.id("movement"));
	private static KeyMapping stanceKey;
	private static KeyMapping crouchKey;
	private static KeyMapping reloadKey;
	private static KeyMapping inspectKey;
	private static boolean crouchWasDown;
	private static boolean stanceWasDown;
	private static boolean proneTriggered;
	private static boolean jumpWasDown;
	private static boolean shiftWasDown;
	private static int stanceHeldTicks;
	private static boolean textureRegistered;

	@Override
	public void onInitializeClient() {
		// Dev convenience: start the game window maximized (windowed, with its title bar/border).
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(
			client -> org.lwjgl.sdl.SDLVideo.SDL_MaximizeWindow(client.getWindow().handle()));

		stanceKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.tacticalmovement.stance", InputConstants.Type.KEYBOARD, InputConstants.KEY_C, CATEGORY));
		// Tactical crouch (toggle). Separate from vanilla shift-sneaking, which is untouched.
		crouchKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.tacticalmovement.crouch", InputConstants.Type.KEYBOARD, InputConstants.KEY_X, CATEGORY));

		// Reload (R): sent to the server, where the MinecraftZombies plugin reloads the held gun.
		reloadKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.tacticalmovement.reload", InputConstants.Type.KEYBOARD, InputConstants.KEY_R, CATEGORY));

		// Inspect the sword (first person only, nothing is sent to the server).
		inspectKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.tacticalmovement.inspect", InputConstants.Type.KEYBOARD, InputConstants.KEY_V, CATEGORY));

		ClientPlayNetworking.registerGlobalReceiver(MovementStatePayload.TYPE, (payload, context) -> {
			if (context.client().level == null) {
				return;
			}
			var entity = context.client().level.getEntity(payload.entityId());
			if (entity instanceof MovementStateAccess state) {
				state.tacticalMovement$setStance(payload.stance());
				state.tacticalMovement$setDiveTicks(payload.diveTicks());
				entity.refreshDimensions();
			} else {
				TacticalMovement.LOGGER.warn("[tactical] client received stance={} for unknown/non-accessible entity id={}",
					payload.stance(), payload.entityId());
			}
		});

		// Stamina is server-authoritative; the owning client just mirrors it for the HUD and for blocking sprint.
		ClientPlayNetworking.registerGlobalReceiver(com.mzcod.tacticalmovement.network.StaminaPayload.TYPE, (payload, context) -> {
			if (context.client().player instanceof MovementStateAccess state) {
				state.tacticalMovement$setStamina(payload.stamina());
				state.tacticalMovement$setExhausted(payload.exhausted());
			}
		});
		StaminaHud.register();

		// Last stand: the server says the player is downed, so the screen turns black and white (a post-processing shader).
		ClientPlayNetworking.registerGlobalReceiver(com.mzcod.tacticalmovement.network.DownedPayload.TYPE, (payload, context) -> {
			if (context.client().player != null) {
				int level = Math.max(0, Math.min(9, payload.level()));
				context.client().player.setActivePostEffects(level == 0
					? java.util.List.of()
					: java.util.List.of(TacticalMovement.id("grayscale_" + level)));
			}
		});

		// Tell a companion Paper plugin that this client has the mod, so it hides its fallback models/HUD from us.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
			(handler, sender, client) -> send(MovementAction.HELLO));

		ClientTickEvents.START_CLIENT_TICK.register(TacticalMovementClient::tickInput);
		ClientTickEvents.END_CLIENT_TICK.register(TacticalMovementClient::tickStanceVisual);
		registerDebugCommands();
	}

	// Ease the per-entity prone blend toward 1 while low-profile, toward 0 otherwise.
	// Drop-in is a touch faster than the get-up for a tactical feel. Purely visual, so it is
	// applied to every player in the client world (local + remote). Speed is live-tunable via
	// /tacticaledbg pronespeed <rate> (TacticalPoseDebug.proneBlendRate).
	private static void tickStanceVisual(Minecraft client) {
		if (client.level == null) {
			return;
		}
		float dropRate = TacticalPoseDebug.proneBlendRate;
		float upRate = TacticalPoseDebug.proneGetUpRate;
		for (var player : client.level.players()) {
			if (!(player instanceof MovementStateAccess state)) {
				continue;
			}
			boolean low = state.tacticalMovement$getStance().isLowProfile();
			boolean crouchingNow = state.tacticalMovement$getStance() == MovementStance.CROUCHING;
			float target = low ? 1.0F : 0.0F;
			float rate = low ? dropRate : upRate;
			float prevProne = state.tacticalMovement$getProneBlend();
			float next = prevProne + (target - prevProne) * rate;
			if (Math.abs(target - next) < 0.002F) {
				next = target;
			}
			// Prone -> crouch: hold the prone pose as the cross-fade's start point until the crouch blend
			// has fully taken over (see PlayerModelMixin), then drop it.
			if (crouchingNow && prevProne > 0.0F) {
				next = state.tacticalMovement$getCrouchBlend() >= 0.999F ? 0.0F : prevProne;
			}
			state.tacticalMovement$setProneBlend(next);

			// Tactical crouch blend: eases toward 1 while CROUCHING, toward 0 otherwise.
			boolean crouching = state.tacticalMovement$getStance() == MovementStance.CROUCHING;
			float crouchTarget = crouching ? 1.0F : 0.0F;
			float crouchNext = state.tacticalMovement$getCrouchBlend()
				+ (crouchTarget - state.tacticalMovement$getCrouchBlend()) * TacticalCrouchDebug.blendRate;
			if (Math.abs(crouchTarget - crouchNext) < 0.002F) {
				crouchNext = crouchTarget;
			}
			// Crouch -> prone: hold the crouch pose as the cross-fade's start point until the prone blend has
			// fully taken over (see PlayerModelMixin), then drop it.
			if (low && state.tacticalMovement$getCrouchBlend() > 0.0F) {
				crouchNext = next >= 0.999F ? 0.0F : state.tacticalMovement$getCrouchBlend();
			}
			state.tacticalMovement$setCrouchBlend(crouchNext);
		}
	}

	private static void tickInput(Minecraft client) {
		if (!textureRegistered && client.getTextureManager() != null) {
			textureRegistered = true;
			NativeImage pixel = new NativeImage(1, 1, false);
			pixel.setPixelABGR(0, 0, (255 << 24) | (55 << 16) | (80 << 8) | 70);
			DynamicTexture texture = new DynamicTexture(() -> "tacticalmovement_uniform_arm", pixel);
			client.getTextureManager().register(TacticalArmRender.UNIFORM_TEXTURE, texture);
		}

		if (client.player == null || client.gui.screen() != null) {
			resetInput();
			return;
		}

		if (((MovementStateAccess) client.player).tacticalMovement$isExhausted()) {
			com.mzcod.tacticalmovement.movement.SprintLock.locked = true;
		} else if (com.mzcod.tacticalmovement.movement.SprintLock.locked && !client.options.keySprint.isDown()) {
			com.mzcod.tacticalmovement.movement.SprintLock.locked = false;
		}

		while (inspectKey.consumeClick()) {
			com.mzcod.tacticalmovement.client.animation.SwordInspect.begin();
		}
		com.mzcod.tacticalmovement.client.animation.SwordInspect.tick(client);

		while (reloadKey.consumeClick()) {
			try {
				ClientPlayNetworking.send(new com.mzcod.tacticalmovement.network.ReloadRequestPayload());
			} catch (RuntimeException e) {
				TacticalMovement.LOGGER.warn("[tactical] could not send reload ({})", e.getMessage());
			}
		}

		boolean stanceDown = stanceKey.isDown();
		if (stanceDown) {
			stanceHeldTicks++;
			if (!proneTriggered && stanceHeldTicks >= PRONE_HOLD_TICKS) {
				proneTriggered = true;
				send(MovementAction.ENTER_PRONE);
			}
		} else if (stanceWasDown) {
			// A quick TAP of C (released before the prone hold threshold) toggles the tactical crouch;
			// holding it still goes prone.
			if (!proneTriggered && stanceHeldTicks < PRONE_HOLD_TICKS) {
				toggleCrouch((MovementStateAccess) client.player);
			}
			stanceHeldTicks = 0;
			proneTriggered = false;
		}
		stanceWasDown = stanceDown;

		MovementStateAccess state = (MovementStateAccess) client.player;
		boolean crouchDown = crouchKey.isDown();
		if (crouchDown && !crouchWasDown) {
			toggleCrouch(state);
		}
		crouchWasDown = crouchDown;

		boolean jumpDown = client.options.keyJump.isDown();
		if (jumpDown && !jumpWasDown) {
			if (state.tacticalMovement$getStance() == MovementStance.CROUCHING) {
				// Space stands you back up instead of jumping (the jump is cancelled in LivingEntityJumpMixin).
				send(MovementAction.EXIT_CROUCH);
			}
			if (state.tacticalMovement$getStance().isLowProfile()) {
				send(MovementAction.EXIT_PRONE);
			}
		}
		jumpWasDown = jumpDown;

		// Sprinting and then pressing shift does the dolphin dive (space is a normal jump again). Checked at the start of the
		// tick, before vanilla turns the shift press into "stop sprinting".
		boolean shiftDown = client.options.keyShift.isDown();
		if (shiftDown && !shiftWasDown && client.player.isSprinting() && state.tacticalMovement$getStance() == MovementStance.STANDING) {
			send(MovementAction.DOLPHIN_DIVE);
		}
		shiftWasDown = shiftDown;
	}

	private static void send(MovementAction action) {
		// Not gated on canSend: a Paper/Purpur server running the companion plugin receives this as a plain plugin
		// message and does not always advertise the channel to Fabric clients.
		try {
			ClientPlayNetworking.send(new MovementRequestPayload(action));
		} catch (RuntimeException e) {
			TacticalMovement.LOGGER.warn("[tactical] client could not send {} ({})", action, e.getMessage());
		}
	}

	private static void toggleCrouch(MovementStateAccess state) {
		send(state.tacticalMovement$getStance() == MovementStance.CROUCHING
			? MovementAction.EXIT_CROUCH
			: MovementAction.ENTER_CROUCH);
	}

	private static void resetInput() {
		crouchWasDown = false;
		stanceWasDown = false;
		proneTriggered = false;
		jumpWasDown = false;
		shiftWasDown = false;
		stanceHeldTicks = 0;
	}

	// ---- Live pose editor (accurate debugging) ------------------------------------

	private static void registerDebugCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommands.literal("tacticaledbg")
				.requires(source -> source.getPlayer() != null)
				.then(ClientCommands.literal("on").executes(TacticalMovementClient::cmdOn))
				.then(ClientCommands.literal("off").executes(TacticalMovementClient::cmdOff))
				.then(ClientCommands.literal("prone").executes(TacticalMovementClient::cmdProne))
				.then(ClientCommands.literal("copy").executes(TacticalMovementClient::cmdCopy))
				.then(ClientCommands.literal("reset").executes(TacticalMovementClient::cmdReset))
				.then(ClientCommands.literal("dump").executes(TacticalMovementClient::cmdDump))
				.then(ClientCommands.literal("headcam")
					.then(ClientCommands.literal("on").executes(TacticalMovementClient::cmdHeadcamOn))
					.then(ClientCommands.literal("off").executes(TacticalMovementClient::cmdHeadcamOff)))
				.then(ClientCommands.literal("downed")
					.then(ClientCommands.literal("rarm").then(ClientCommands.argument("rad", FloatArgumentType.floatArg()).executes(ctx -> {
						com.mzcod.tacticalmovement.client.animation.DownedTune.rightArm = FloatArgumentType.getFloat(ctx, "rad");
						return 1;
					})))
					.then(ClientCommands.literal("larm").then(ClientCommands.argument("rad", FloatArgumentType.floatArg()).executes(ctx -> {
						com.mzcod.tacticalmovement.client.animation.DownedTune.leftArm = FloatArgumentType.getFloat(ctx, "rad");
						return 1;
					})))
					.then(ClientCommands.literal("rleg").then(ClientCommands.argument("rad", FloatArgumentType.floatArg()).executes(ctx -> {
						com.mzcod.tacticalmovement.client.animation.DownedTune.rightLeg = FloatArgumentType.getFloat(ctx, "rad");
						return 1;
					})))
					.then(ClientCommands.literal("rshin").then(ClientCommands.argument("rad", FloatArgumentType.floatArg()).executes(ctx -> {
						com.mzcod.tacticalmovement.client.animation.DownedTune.rightShin = FloatArgumentType.getFloat(ctx, "rad");
						return 1;
					}))))
				.then(ClientCommands.literal("headpitch").then(ClientCommands.argument("factor", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdHeadPitch)))
				.then(ClientCommands.literal("headoffset").then(ClientCommands.argument("deg", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdHeadOffset)))
				.then(ClientCommands.literal("swordamp").then(ClientCommands.argument("rad", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdSwordAmp)))
				.then(ClientCommands.literal("swordlift").then(ClientCommands.argument("rad", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdSwordLift)))
				.then(ClientCommands.literal("standingswordamp").then(ClientCommands.argument("rad", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdStandingSwordAmp)))
				.then(ClientCommands.literal("swordtwist").then(ClientCommands.argument("rad", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdSwordTwist)))
				.then(ClientCommands.literal("cstance").executes(TacticalMovementClient::cmdCrouchStance))
				.then(ClientCommands.literal("cswing")
					.then(ClientCommands.argument("sweep", FloatArgumentType.floatArg())
						.then(ClientCommands.argument("twist", FloatArgumentType.floatArg())
							.then(ClientCommands.argument("lift", FloatArgumentType.floatArg()).executes(ctx -> {
								TacticalCrouchDebug.swingSweep = FloatArgumentType.getFloat(ctx, "sweep");
								TacticalCrouchDebug.swingTwist = FloatArgumentType.getFloat(ctx, "twist");
								TacticalCrouchDebug.swingLift = FloatArgumentType.getFloat(ctx, "lift");
								send(ctx, "crouch sword swing: sweep=" + TacticalCrouchDebug.swingSweep
									+ " twist=" + TacticalCrouchDebug.swingTwist + " lift=" + TacticalCrouchDebug.swingLift);
								return 1;
							})))))
				.then(ClientCommands.literal("cshin").then(ClientCommands.argument("rad", FloatArgumentType.floatArg()).executes(ctx -> {
					TacticalCrouchDebug.shinBend = FloatArgumentType.getFloat(ctx, "rad");
					send(ctx, "crouch knee bend (shin vs thigh) = " + TacticalCrouchDebug.shinBend + " rad");
					return 1;
				})))
				.then(ClientCommands.literal("ceye").then(ClientCommands.argument("height", FloatArgumentType.floatArg(0.3F, 1.8F)).executes(ctx -> {
					float h = FloatArgumentType.getFloat(ctx, "height");
					com.mzcod.tacticalmovement.movement.CrouchConfig.eyeHeight = h;
					com.mzcod.tacticalmovement.movement.CrouchConfig.boxHeight = Math.max(1.0F, h + 0.27F);
					var p = Minecraft.getInstance().player;
					if (p != null) {
						p.refreshDimensions();
					}
					send(ctx, "crouch camera/eye height = " + h);
					return 1;
				})))
				.then(ClientCommands.literal("fpbob")
					.then(ClientCommands.argument("kind", StringArgumentType.word())
						.then(ClientCommands.argument("camY", FloatArgumentType.floatArg())
							.then(ClientCommands.argument("pitch", FloatArgumentType.floatArg())
								.then(ClientCommands.argument("handY", FloatArgumentType.floatArg()).executes(ctx -> {
									String kind = StringArgumentType.getString(ctx, "kind");
									String[] names = com.mzcod.tacticalmovement.client.animation.FpStanceAnim.BOB_NAMES;
									for (int i = 0; i < names.length; i++) {
										if (names[i].equalsIgnoreCase(kind)) {
											float[] b = com.mzcod.tacticalmovement.client.animation.FpStanceAnim.bob[i];
											b[0] = FloatArgumentType.getFloat(ctx, "camY");
											b[1] = FloatArgumentType.getFloat(ctx, "pitch");
											b[2] = FloatArgumentType.getFloat(ctx, "handY");
											send(ctx, "1p bob " + names[i] + " set (negative = dips first, positive = lifts first)");
											return 1;
										}
									}
									send(ctx, "kind must be one of: entercrouch, enterprone, exitcrouch, exitprone");
									return 0;
								}))))))
				.then(ClientCommands.literal("fpbobtime")
					.then(ClientCommands.argument("decay", FloatArgumentType.floatArg(0.5F, 30.0F))
						.then(ClientCommands.argument("freq", FloatArgumentType.floatArg(1.0F, 40.0F)).executes(ctx -> {
							com.mzcod.tacticalmovement.client.animation.FpStanceAnim.bobDecay = FloatArgumentType.getFloat(ctx, "decay");
							com.mzcod.tacticalmovement.client.animation.FpStanceAnim.bobFreq = FloatArgumentType.getFloat(ctx, "freq");
							send(ctx, "1p transition bob decay/freq set");
							return 1;
						}))))
				.then(ClientCommands.literal("fpidle")
					.then(ClientCommands.argument("cam", FloatArgumentType.floatArg())
						.then(ClientCommands.argument("hand", FloatArgumentType.floatArg())
							.then(ClientCommands.argument("rate", FloatArgumentType.floatArg()).executes(ctx -> {
								com.mzcod.tacticalmovement.client.animation.FpStanceAnim.idleCam = FloatArgumentType.getFloat(ctx, "cam");
								com.mzcod.tacticalmovement.client.animation.FpStanceAnim.idleHand = FloatArgumentType.getFloat(ctx, "hand");
								com.mzcod.tacticalmovement.client.animation.FpStanceAnim.idleRate = FloatArgumentType.getFloat(ctx, "rate");
								send(ctx, "1p standing idle breathing set");
								return 1;
							})))))
				.then(ClientCommands.literal("stamina")
					.executes(ctx -> {
						send(ctx, String.format(java.util.Locale.ROOT,
							"stamina: max=%.0f sprintDrain=%.2f/tick regen=%.2f/tick regenDelay=%d diveCost=%.0f recover=%.0f",
							com.mzcod.tacticalmovement.movement.StaminaConfig.max,
							com.mzcod.tacticalmovement.movement.StaminaConfig.sprintDrain,
							com.mzcod.tacticalmovement.movement.StaminaConfig.regen,
							com.mzcod.tacticalmovement.movement.StaminaConfig.regenDelay,
							com.mzcod.tacticalmovement.movement.StaminaConfig.diveCost,
							com.mzcod.tacticalmovement.movement.StaminaConfig.recoverThreshold));
						return 1;
					})
					.then(ClientCommands.argument("sprintDrain", FloatArgumentType.floatArg(0.0F))
						.then(ClientCommands.argument("regen", FloatArgumentType.floatArg(0.0F))
							.then(ClientCommands.argument("diveCost", FloatArgumentType.floatArg(0.0F)).executes(ctx -> {
								com.mzcod.tacticalmovement.movement.StaminaConfig.sprintDrain = FloatArgumentType.getFloat(ctx, "sprintDrain");
								com.mzcod.tacticalmovement.movement.StaminaConfig.regen = FloatArgumentType.getFloat(ctx, "regen");
								com.mzcod.tacticalmovement.movement.StaminaConfig.diveCost = FloatArgumentType.getFloat(ctx, "diveCost");
								send(ctx, "stamina drain/regen/dive cost set (per tick; single-player/integrated server)");
								return 1;
							})))))
				.then(ClientCommands.literal("inspect")
					.then(ClientCommands.literal("reset").executes(ctx -> {
						com.mzcod.tacticalmovement.client.animation.SwordInspect.reset();
						send(ctx, "inspect values reset");
						return 1;
					}))
					.then(ClientCommands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
						.then(ClientCommands.argument("value", FloatArgumentType.floatArg()).executes(ctx -> {
							String name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name");
							var names = com.mzcod.tacticalmovement.client.animation.SwordInspect.NAMES;
							for (int i = 0; i < names.length; i++) {
								if (names[i].equals(name)) {
									com.mzcod.tacticalmovement.client.animation.SwordInspect.p[i] = FloatArgumentType.getFloat(ctx, "value");
									send(ctx, "inspect " + name + " set");
									return 1;
								}
							}
							send(ctx, "name must be one of: " + String.join(", ", names));
							return 0;
						}))))
				.then(ClientCommands.literal("fpdive")
					.then(ClientCommands.argument("reach", FloatArgumentType.floatArg())
						.then(ClientCommands.argument("lift", FloatArgumentType.floatArg())
							.then(ClientCommands.argument("impact", FloatArgumentType.floatArg()).executes(ctx -> {
								com.mzcod.tacticalmovement.client.animation.FpStanceAnim.diveReach = FloatArgumentType.getFloat(ctx, "reach");
								com.mzcod.tacticalmovement.client.animation.FpStanceAnim.diveLift = FloatArgumentType.getFloat(ctx, "lift");
								com.mzcod.tacticalmovement.client.animation.FpStanceAnim.diveImpact = FloatArgumentType.getFloat(ctx, "impact");
								send(ctx, "1p dive: reach/lift/impact set");
								return 1;
							})))))
				.then(ClientCommands.literal("fpcrouch")
					.then(ClientCommands.argument("x", FloatArgumentType.floatArg())
						.then(ClientCommands.argument("y", FloatArgumentType.floatArg())
							.then(ClientCommands.argument("z", FloatArgumentType.floatArg())
								.then(ClientCommands.argument("pitch", FloatArgumentType.floatArg())
									.then(ClientCommands.argument("yaw", FloatArgumentType.floatArg())
										.then(ClientCommands.argument("roll", FloatArgumentType.floatArg()).executes(ctx -> {
											com.mzcod.tacticalmovement.client.animation.FpStanceAnim.crouchX = FloatArgumentType.getFloat(ctx, "x");
											com.mzcod.tacticalmovement.client.animation.FpStanceAnim.crouchY = FloatArgumentType.getFloat(ctx, "y");
											com.mzcod.tacticalmovement.client.animation.FpStanceAnim.crouchZ = FloatArgumentType.getFloat(ctx, "z");
											com.mzcod.tacticalmovement.client.animation.FpStanceAnim.crouchPitch = FloatArgumentType.getFloat(ctx, "pitch");
											com.mzcod.tacticalmovement.client.animation.FpStanceAnim.crouchYaw = FloatArgumentType.getFloat(ctx, "yaw");
											com.mzcod.tacticalmovement.client.animation.FpStanceAnim.crouchRoll = FloatArgumentType.getFloat(ctx, "roll");
											send(ctx, "1p crouch sword arm offsets set (x is toward the centre)");
											return 1;
										}))))))))
				.then(ClientCommands.literal("stand")
					.executes(ctx -> {
						send(ctx, com.mzcod.tacticalmovement.client.debug.TacticalStandingDebug.dump());
						return 1;
					})
					.then(ClientCommands.argument("name", StringArgumentType.word())
						.then(ClientCommands.argument("value", FloatArgumentType.floatArg()).executes(ctx -> {
							String n = StringArgumentType.getString(ctx, "name");
							var p = com.mzcod.tacticalmovement.client.debug.TacticalStandingDebug.ALL.get(n);
							if (p == null) {
								send(ctx, "unknown standing value '" + n + "' (run /tacticaledbg stand to list)");
								return 0;
							}
							p.v = FloatArgumentType.getFloat(ctx, "value");
							send(ctx, "standing " + n + " = " + p.v);
							return 1;
						}))))
				.then(ClientCommands.literal("standon").then(ClientCommands.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool()).executes(ctx -> {
					com.mzcod.tacticalmovement.client.debug.TacticalStandingDebug.enabled = com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "on");
					send(ctx, "custom standing animations = " + com.mzcod.tacticalmovement.client.debug.TacticalStandingDebug.enabled);
					return 1;
				})))
				.then(ClientCommands.literal("standreset").executes(ctx -> {
					com.mzcod.tacticalmovement.client.debug.TacticalStandingDebug.reset();
					send(ctx, "standing animation values reset");
					return 1;
				}))
				.then(ClientCommands.literal("cdump").executes(TacticalMovementClient::cmdCrouchDump))
				.then(ClientCommands.literal("creset").executes(TacticalMovementClient::cmdCrouchReset))
				.then(ClientCommands.literal("cspeed").then(ClientCommands.argument("rate", FloatArgumentType.floatArg(0.02F, 1.0F)).executes(TacticalMovementClient::cmdCrouchSpeed)))
				.then(ClientCommands.literal("cwalk")
					.then(ClientCommands.argument("legSwing", FloatArgumentType.floatArg())
						.then(ClientCommands.argument("armSway", FloatArgumentType.floatArg())
							.then(ClientCommands.argument("speed", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdCrouchWalk)))))
				.then(ClientCommands.literal("cset")
					.then(ClientCommands.argument("part", StringArgumentType.word())
						.then(ClientCommands.argument("x", FloatArgumentType.floatArg())
							.then(ClientCommands.argument("y", FloatArgumentType.floatArg())
								.then(ClientCommands.argument("z", FloatArgumentType.floatArg())
									.then(ClientCommands.argument("xr", FloatArgumentType.floatArg())
										.then(ClientCommands.argument("yr", FloatArgumentType.floatArg())
											.then(ClientCommands.argument("zr", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdCrouchSet)))))))))
				.then(ClientCommands.literal("fpwalk")
					.executes(TacticalMovementClient::cmdFpSwordDump)
					.then(ClientCommands.argument("roll", FloatArgumentType.floatArg())
						.then(ClientCommands.argument("reach", FloatArgumentType.floatArg())
							.then(ClientCommands.argument("crawl", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdFpWalk)))))
				.then(ClientCommands.literal("custombob")
					.executes(TacticalMovementClient::cmdFpSwordDump)
					.then(ClientCommands.literal("on").executes(ctx -> cmdCustomBob(ctx, true)))
					.then(ClientCommands.literal("off").executes(ctx -> cmdCustomBob(ctx, false))))
				.then(ClientCommands.literal("fpsway")
					.executes(TacticalMovementClient::cmdFpSwordDump)
					.then(ClientCommands.argument("amp", FloatArgumentType.floatArg())
						.then(ClientCommands.argument("rate", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdFpSway)
							.then(ClientCommands.argument("camera", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdFpSway)))))
				.then(ClientCommands.literal("fpslash")
					.executes(TacticalMovementClient::cmdFpSwordDump)
					.then(ClientCommands.argument("x", FloatArgumentType.floatArg())
						.then(ClientCommands.argument("yaw", FloatArgumentType.floatArg())
							.then(ClientCommands.argument("roll", FloatArgumentType.floatArg())
								.then(ClientCommands.argument("forward", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdFpSlash)
									.then(ClientCommands.argument("lift", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdFpSlash)))))))
				.then(ClientCommands.literal("fparm")
					.executes(TacticalMovementClient::cmdFpSwordDump)
					.then(ClientCommands.argument("x", FloatArgumentType.floatArg())
						.then(ClientCommands.argument("y", FloatArgumentType.floatArg())
							.then(ClientCommands.argument("z", FloatArgumentType.floatArg())
								.then(ClientCommands.argument("xRot", FloatArgumentType.floatArg())
									.then(ClientCommands.argument("yRot", FloatArgumentType.floatArg())
										.then(ClientCommands.argument("zRot", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdFpArm))))))))
				.then(ClientCommands.literal("fpsword")
					.executes(TacticalMovementClient::cmdFpSwordDump)
					.then(ClientCommands.argument("x", FloatArgumentType.floatArg())
						.then(ClientCommands.argument("y", FloatArgumentType.floatArg())
							.then(ClientCommands.argument("z", FloatArgumentType.floatArg())
								.then(ClientCommands.argument("yaw", FloatArgumentType.floatArg())
									.then(ClientCommands.argument("pitch", FloatArgumentType.floatArg())
										.then(ClientCommands.argument("roll", FloatArgumentType.floatArg())
											.then(ClientCommands.argument("slide", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdFpSword)))))))))
				.then(ClientCommands.literal("swordspeed").then(ClientCommands.argument("factor", FloatArgumentType.floatArg(0.01F, 1.0F)).executes(TacticalMovementClient::cmdSwordSpeed)))
				.then(ClientCommands.literal("swordarm")
					.executes(TacticalMovementClient::cmdSwordSwingDump)
					.then(ClientCommands.argument("x", FloatArgumentType.floatArg())
						.then(ClientCommands.argument("y", FloatArgumentType.floatArg())
							.then(ClientCommands.argument("z", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdSwordArm)))))
				.then(ClientCommands.literal("standingswordthrust").then(ClientCommands.argument("rad", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdStandingSwordThrust)))
			.then(ClientCommands.literal("swordhold")
				.executes(TacticalMovementClient::cmdSwordHoldDump)
				.then(ClientCommands.argument("x", FloatArgumentType.floatArg())
					.then(ClientCommands.argument("y", FloatArgumentType.floatArg())
						.then(ClientCommands.argument("z", FloatArgumentType.floatArg())
							.then(ClientCommands.argument("xRot", FloatArgumentType.floatArg())
								.then(ClientCommands.argument("yRot", FloatArgumentType.floatArg())
									.then(ClientCommands.argument("zRot", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdSwordHold))))))))
				.then(ClientCommands.literal("pronespeed").then(ClientCommands.argument("rate", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdProneSpeed)))
			.then(ClientCommands.literal("getupspeed").then(ClientCommands.argument("rate", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdGetUpSpeed)))
				.then(ClientCommands.literal("crouch").then(ClientCommands.argument("amount", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdCrouch)))
			.then(ClientCommands.literal("getup").then(ClientCommands.argument("amount", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdGetUp)))
			.then(ClientCommands.literal("getuparm").then(ClientCommands.argument("rad", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdGetUpArm)))
			.then(ClientCommands.literal("getuparmin").then(ClientCommands.argument("rad", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdGetUpArmIn)))
			.then(ClientCommands.literal("getupleglift").then(ClientCommands.argument("rad", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdGetUpLeg)))
			.then(ClientCommands.literal("getupbody").then(ClientCommands.argument("units", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdGetUpBody)))
				.then(ClientCommands.literal("set")
					.then(ClientCommands.argument("part", StringArgumentType.word())
						.then(ClientCommands.argument("x", FloatArgumentType.floatArg())
							.then(ClientCommands.argument("y", FloatArgumentType.floatArg())
								.then(ClientCommands.argument("z", FloatArgumentType.floatArg())
									.then(ClientCommands.argument("xr", FloatArgumentType.floatArg())
										.then(ClientCommands.argument("yr", FloatArgumentType.floatArg())
											.then(ClientCommands.argument("zr", FloatArgumentType.floatArg())
												.executes(TacticalMovementClient::cmdSet)))))))))
				.then(ClientCommands.literal("anim").then(ClientCommands.literal("on").executes(TacticalMovementClient::cmdAnimOn)).then(ClientCommands.literal("off").executes(TacticalMovementClient::cmdAnimOff)).then(ClientCommands.literal("idle").executes(TacticalMovementClient::cmdAnimIdle)).then(ClientCommands.literal("none").executes(TacticalMovementClient::cmdAnimNone))).then(ClientCommands.literal("animspeed").then(ClientCommands.argument("value", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdAnimSpeed))).then(ClientCommands.literal("animdump").executes(TacticalMovementClient::cmdAnimDump)).then(ClientCommands.literal("animset").then(ClientCommands.argument("part", StringArgumentType.word()).then(ClientCommands.argument("axis", IntegerArgumentType.integer(0, 2)).then(ClientCommands.argument("amp", FloatArgumentType.floatArg()).then(ClientCommands.argument("phase", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdAnimSet))))))
				.then(ClientCommands.literal("crawl").then(ClientCommands.literal("preset").executes(TacticalMovementClient::cmdCrawlPreset)).then(ClientCommands.literal("none").executes(TacticalMovementClient::cmdCrawlNone)))
				.then(ClientCommands.literal("crawlspeed").then(ClientCommands.argument("value", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdCrawlSpeed)))
				.then(ClientCommands.literal("crawldump").executes(TacticalMovementClient::cmdCrawlDump))
				.then(ClientCommands.literal("crawlset").then(ClientCommands.argument("part", StringArgumentType.word()).then(ClientCommands.argument("axis", IntegerArgumentType.integer(0, 2)).then(ClientCommands.argument("amp", FloatArgumentType.floatArg()).then(ClientCommands.argument("phase", FloatArgumentType.floatArg()).executes(TacticalMovementClient::cmdCrawlSet))))))
				.executes(context -> {
					send(context, "subcommands: on | off | prone | copy | reset | dump | headcam on|off | headpitch <f> | headoffset <deg> | swordamp <rad> | swordlift <rad> | standingswordamp <rad> | standingswordthrust <rad> | swordhold x y z xRot yRot zRot | pronespeed <rate> | getupspeed <rate> | crouch <amount> | getup <amount> | getuparm <rad> | getuparmin <rad> | getupleglift <rad> | getupbody <units> | anim on|off|idle|none | animspeed <v> | animdump | animset <part> <axis> <amp> <phase> | crawl preset|none | crawlspeed <v> | crawldump | crawlset <part> <axis> <amp> <phase> | set <part> x y z xRot yRot zRot");
					return 1;
				})
		)
		);
	}
	private static int cmdOn(CommandContext<FabricClientCommandSource> ctx) {
		TacticalPoseDebug.enabled = true;
		TacticalPoseDebug.copyVanilla(); // start from the normal standing pose, then sculpt
		send(ctx, "POSE EDITOR ON - sculpt with: /tacticaledbg set <part> x y z xRot yRot zRot (parts: "
				+ String.join(" ", TacticalPoseDebug.PARTS) + ")");
		return 1;
	}

	private static int cmdOff(CommandContext<FabricClientCommandSource> ctx) {
		TacticalPoseDebug.enabled = false;
		TacticalAnimations.reapplyEnabled = true;
		send(ctx, "POSE EDITOR OFF - normal play (holding C still uses the tuned prone pose; standing reverts to vanilla)");
		return 1;
	}
	
	private static int cmdProne(CommandContext<FabricClientCommandSource> ctx) {
		TacticalPoseDebug.enabled = true;
		TacticalPoseDebug.prone();
		send(ctx, "applied the tuned prone baseline (head follows the camera). Fine-tune any part: /tacticaledbg set <part> x y z xRot yRot zRot");
		return 1;
	}
	
	private static int cmdHeadcamOn(CommandContext<FabricClientCommandSource> ctx) {
		TacticalPoseDebug.headFollowsCamera = true;
		send(ctx, "head rotation now FOLLOWS the camera (billboard)");
		return 1;
	}
	
	private static int cmdHeadcamOff(CommandContext<FabricClientCommandSource> ctx) {
		TacticalPoseDebug.headFollowsCamera = false;
		send(ctx, "head rotation now uses the sculpted pose");
		return 1;
	}

	private static int cmdHeadPitch(CommandContext<FabricClientCommandSource> ctx) {
		float f = FloatArgumentType.getFloat(ctx, "factor");
		TacticalPoseDebug.headPitchFactor = f;
		send(ctx, "prone head camera-PITCH factor = " + f
			+ "  (1 = full up/down tracking, 0 = off, -1 = inverted) -- enter prone and look up/down to test");
		return 1;
	}

	private static int cmdHeadOffset(CommandContext<FabricClientCommandSource> ctx) {
		float deg = FloatArgumentType.getFloat(ctx, "deg");
		TacticalPoseDebug.headPitchOffset = deg;
		send(ctx, "prone head RESTING pitch offset = " + deg + " deg (positive lowers the head, negative raises it;"
			+ " default 35) -- independent of the headpitch factor");
		return 1;
	}

	private static int cmdSwordAmp(CommandContext<FabricClientCommandSource> ctx) {
		float rad = FloatArgumentType.getFloat(ctx, "rad");
		TacticalPoseDebug.proneSwordSwingAmp = rad;
		send(ctx, "prone SWORD swing arc = " + rad + " rad (total sweep = 2x = "
			+ (2.0F * rad) + " rad; default 0.5) -- enter prone, hold a sword, and attack to test");
		return 1;
	}

	private static int cmdSwordLift(CommandContext<FabricClientCommandSource> ctx) {
		float rad = FloatArgumentType.getFloat(ctx, "rad");
		TacticalPoseDebug.proneSwordSwingLift = rad;
		send(ctx, "prone SWORD swing mid-lift = " + rad + " rad (0 = flat sweep, negative = invert; default 1.0)");
		return 1;
	}

	private static int cmdStandingSwordAmp(CommandContext<FabricClientCommandSource> ctx) {
		float rad = FloatArgumentType.getFloat(ctx, "rad");
		TacticalPoseDebug.standingSwordSwingAmp = rad;
		send(ctx, "standing SWORD swing arc = " + rad + " rad (total sweep = 2x = "
			+ (2.0F * rad) + " rad; default 0.5) -- hold a sword and attack while standing to test");
		return 1;
	}

	private static int cmdSwordTwist(CommandContext<FabricClientCommandSource> ctx) {
		TacticalPoseDebug.standingSwordTwist = FloatArgumentType.getFloat(ctx, "rad");
		return cmdSwordSwingDump(ctx);
	}

	// ---- Tactical crouch debug ----

	private static int cmdCrouchStance(CommandContext<FabricClientCommandSource> ctx) {
		var player = ctx.getSource().getPlayer();
		if (player instanceof MovementStateAccess state) {
			toggleCrouch(state);
			send(ctx, "toggled tactical crouch (was " + state.tacticalMovement$getStance() + ")");
		}
		return 1;
	}

	private static int cmdCrouchDump(CommandContext<FabricClientCommandSource> ctx) {
		for (String line : TacticalCrouchDebug.dump().split("\n")) {
			send(ctx, line);
		}
		return 1;
	}

	private static int cmdCrouchReset(CommandContext<FabricClientCommandSource> ctx) {
		TacticalCrouchDebug.reset();
		send(ctx, "crouch pose reset to the baked defaults");
		return 1;
	}

	private static int cmdCrouchSpeed(CommandContext<FabricClientCommandSource> ctx) {
		TacticalCrouchDebug.blendRate = FloatArgumentType.getFloat(ctx, "rate");
		send(ctx, "crouch blend rate = " + TacticalCrouchDebug.blendRate + " (higher = faster crouch down/up)");
		return 1;
	}

	private static int cmdCrouchWalk(CommandContext<FabricClientCommandSource> ctx) {
		TacticalCrouchDebug.legSwing = FloatArgumentType.getFloat(ctx, "legSwing");
		TacticalCrouchDebug.armSway = FloatArgumentType.getFloat(ctx, "armSway");
		TacticalCrouchDebug.walkSpeed = FloatArgumentType.getFloat(ctx, "speed");
		send(ctx, "crouch walk: legSwing=" + TacticalCrouchDebug.legSwing + " armSway=" + TacticalCrouchDebug.armSway
			+ " speed=" + TacticalCrouchDebug.walkSpeed);
		return 1;
	}

	private static int cmdCrouchSet(CommandContext<FabricClientCommandSource> ctx) {
		int i = TacticalPoseDebug.findPart(StringArgumentType.getString(ctx, "part"));
		if (i < 0) {
			ctx.getSource().sendError(Component.literal("unknown part, use: " + String.join(" ", TacticalPoseDebug.PARTS)));
			return 0;
		}
		float[] pose = TacticalCrouchDebug.pose[i];
		pose[0] = FloatArgumentType.getFloat(ctx, "x");
		pose[1] = FloatArgumentType.getFloat(ctx, "y");
		pose[2] = FloatArgumentType.getFloat(ctx, "z");
		pose[3] = FloatArgumentType.getFloat(ctx, "xr");
		pose[4] = FloatArgumentType.getFloat(ctx, "yr");
		pose[5] = FloatArgumentType.getFloat(ctx, "zr");
		send(ctx, "crouch " + TacticalPoseDebug.PARTS[i] + " -> " + TacticalPoseDebug.describe(pose));
		return 1;
	}

	private static int cmdFpWalk(CommandContext<FabricClientCommandSource> ctx) {
		TacticalPoseDebug.fpWalkRoll = FloatArgumentType.getFloat(ctx, "roll");
		TacticalPoseDebug.fpWalkReach = FloatArgumentType.getFloat(ctx, "reach");
		TacticalPoseDebug.fpCrawlMult = FloatArgumentType.getFloat(ctx, "crawl");
		return cmdFpSwordDump(ctx);
	}

	private static int cmdCustomBob(CommandContext<FabricClientCommandSource> ctx, boolean on) {
		TacticalPoseDebug.customBob = on;
		send(ctx, "custom bob = " + (on ? "ON (custom hand sway + camera bob for every item)" : "OFF (vanilla bobbing)"));
		return 1;
	}

	private static int cmdFpSway(CommandContext<FabricClientCommandSource> ctx) {
		TacticalPoseDebug.fpSwayAmp = FloatArgumentType.getFloat(ctx, "amp");
		TacticalPoseDebug.fpSwayRate = FloatArgumentType.getFloat(ctx, "rate");
		try {
			TacticalPoseDebug.fpCameraBobAmp = FloatArgumentType.getFloat(ctx, "camera");
		} catch (IllegalArgumentException ignored) {
			// camera amount is optional
		}
		return cmdFpSwordDump(ctx);
	}

	private static int cmdFpSlash(CommandContext<FabricClientCommandSource> ctx) {
		TacticalPoseDebug.fpSlashX = FloatArgumentType.getFloat(ctx, "x");
		TacticalPoseDebug.fpSlashYaw = FloatArgumentType.getFloat(ctx, "yaw");
		TacticalPoseDebug.fpSlashRoll = FloatArgumentType.getFloat(ctx, "roll");
		TacticalPoseDebug.fpSlashForward = FloatArgumentType.getFloat(ctx, "forward");
		try {
			TacticalPoseDebug.fpSlashLift = FloatArgumentType.getFloat(ctx, "lift");
		} catch (IllegalArgumentException ignored) {
			// lift is optional
		}
		return cmdFpSwordDump(ctx);
	}

	private static int cmdFpArm(CommandContext<FabricClientCommandSource> ctx) {
		TacticalPoseDebug.fpArmX = FloatArgumentType.getFloat(ctx, "x");
		TacticalPoseDebug.fpArmY = FloatArgumentType.getFloat(ctx, "y");
		TacticalPoseDebug.fpArmZ = FloatArgumentType.getFloat(ctx, "z");
		TacticalPoseDebug.fpArmXR = FloatArgumentType.getFloat(ctx, "xRot");
		TacticalPoseDebug.fpArmYR = FloatArgumentType.getFloat(ctx, "yRot");
		TacticalPoseDebug.fpArmZR = FloatArgumentType.getFloat(ctx, "zRot");
		return cmdFpSwordDump(ctx);
	}

	private static int cmdFpSword(CommandContext<FabricClientCommandSource> ctx) {
		TacticalPoseDebug.fpSwordX = FloatArgumentType.getFloat(ctx, "x");
		TacticalPoseDebug.fpSwordY = FloatArgumentType.getFloat(ctx, "y");
		TacticalPoseDebug.fpSwordZ = FloatArgumentType.getFloat(ctx, "z");
		TacticalPoseDebug.fpSwordYaw = FloatArgumentType.getFloat(ctx, "yaw");
		TacticalPoseDebug.fpSwordPitch = FloatArgumentType.getFloat(ctx, "pitch");
		TacticalPoseDebug.fpSwordRoll = FloatArgumentType.getFloat(ctx, "roll");
		TacticalPoseDebug.fpSwordSlide = FloatArgumentType.getFloat(ctx, "slide");
		return cmdFpSwordDump(ctx);
	}

	private static int cmdFpSwordDump(CommandContext<FabricClientCommandSource> ctx) {
		send(ctx, "first-person arm offset xyz=" + TacticalPoseDebug.fpArmX + ", " + TacticalPoseDebug.fpArmY + ", "
			+ TacticalPoseDebug.fpArmZ + " rot(x,y,z deg)=" + TacticalPoseDebug.fpArmXR + ", "
			+ TacticalPoseDebug.fpArmYR + ", " + TacticalPoseDebug.fpArmZR + " | slash x=" + TacticalPoseDebug.fpSlashX
			+ " yaw=" + TacticalPoseDebug.fpSlashYaw + " roll=" + TacticalPoseDebug.fpSlashRoll
			+ " forward=" + TacticalPoseDebug.fpSlashForward + " lift=" + TacticalPoseDebug.fpSlashLift + " | walk roll=" + TacticalPoseDebug.fpWalkRoll + " reach=" + TacticalPoseDebug.fpWalkReach
			+ " crawl=" + TacticalPoseDebug.fpCrawlMult + " | custombob=" + (TacticalPoseDebug.customBob ? "on" : "off") + " sway amp=" + TacticalPoseDebug.fpSwayAmp
			+ " rate=" + TacticalPoseDebug.fpSwayRate + " camera=" + TacticalPoseDebug.fpCameraBobAmp + " | sword pos(x,y,z)=" + TacticalPoseDebug.fpSwordX + ", " + TacticalPoseDebug.fpSwordY + ", " + TacticalPoseDebug.fpSwordZ
			+ " yaw=" + TacticalPoseDebug.fpSwordYaw + " pitch=" + TacticalPoseDebug.fpSwordPitch
			+ " roll=" + TacticalPoseDebug.fpSwordRoll + " slide=" + TacticalPoseDebug.fpSwordSlide);
		return 1;
	}

	private static int cmdSwordSpeed(CommandContext<FabricClientCommandSource> ctx) {
		float f = FloatArgumentType.getFloat(ctx, "factor");
		TacticalPoseDebug.swordSwingSpeed = f;
		send(ctx, "sword swing speed = " + f + " (1 = normal, 0.2 = 5x slower)");
		return 1;
	}

	private static int cmdSwordArm(CommandContext<FabricClientCommandSource> ctx) {
		TacticalPoseDebug.standingSwordArmX = FloatArgumentType.getFloat(ctx, "x");
		TacticalPoseDebug.standingSwordArmY = FloatArgumentType.getFloat(ctx, "y");
		TacticalPoseDebug.standingSwordArmZ = FloatArgumentType.getFloat(ctx, "z");
		return cmdSwordSwingDump(ctx);
	}

	private static int cmdSwordSwingDump(CommandContext<FabricClientCommandSource> ctx) {
		send(ctx, "standing sword swing: amp=" + TacticalPoseDebug.standingSwordSwingAmp
			+ " thrust=" + TacticalPoseDebug.standingSwordSwingThrust
			+ " twist=" + TacticalPoseDebug.standingSwordTwist
			+ " armExtra(x,y,z)=" + TacticalPoseDebug.standingSwordArmX + ", "
			+ TacticalPoseDebug.standingSwordArmY + ", " + TacticalPoseDebug.standingSwordArmZ);
		return 1;
	}

	private static int cmdStandingSwordThrust(CommandContext<FabricClientCommandSource> ctx) {
		float rad = FloatArgumentType.getFloat(ctx, "rad");
		TacticalPoseDebug.standingSwordSwingThrust = rad;
		send(ctx, "standing SWORD swing mid-thrust = " + rad + " rad (0 = flat sweep, negative = invert; default 0.2)");
		return 1;
	}

	private static int cmdSwordHold(CommandContext<FabricClientCommandSource> ctx) {
		float x = FloatArgumentType.getFloat(ctx, "x");
		float y = FloatArgumentType.getFloat(ctx, "y");
		float z = FloatArgumentType.getFloat(ctx, "z");
		float xr = FloatArgumentType.getFloat(ctx, "xRot");
		float yr = FloatArgumentType.getFloat(ctx, "yRot");
		float zr = FloatArgumentType.getFloat(ctx, "zRot");
		TacticalPoseDebug.swordHoldX = x;
		TacticalPoseDebug.swordHoldY = y;
		TacticalPoseDebug.swordHoldZ = z;
		TacticalPoseDebug.swordHoldXR = xr;
		TacticalPoseDebug.swordHoldYR = yr;
		TacticalPoseDebug.swordHoldZR = zr;
		send(ctx, "sword hold = x=" + x + " y=" + y + " z=" + z + " | xRot=" + xr + " yRot=" + yr + " zRot=" + zr
			+ " (deg) -- nudges the handle relative to the arm; 0 0 0 0 0 0 = vanilla. Look from the side/front.");
		return 1;
	}

	// Read back the current sword-hold offset (no args: /tacticaledbg swordhold).
	private static int cmdSwordHoldDump(CommandContext<FabricClientCommandSource> ctx) {
		send(ctx, "sword hold (current) = x=" + TacticalPoseDebug.swordHoldX
			+ " y=" + TacticalPoseDebug.swordHoldY + " z=" + TacticalPoseDebug.swordHoldZ
			+ " | xRot=" + TacticalPoseDebug.swordHoldXR + " yRot=" + TacticalPoseDebug.swordHoldYR
			+ " zRot=" + TacticalPoseDebug.swordHoldZR + " (deg) -- 0 0 0 0 0 0 = vanilla");
		return 1;
	}

	private static int cmdProneSpeed(CommandContext<FabricClientCommandSource> ctx) {
		float rate = FloatArgumentType.getFloat(ctx, "rate");
		TacticalPoseDebug.proneBlendRate = rate;
		send(ctx, "prone DROP-IN speed (ease rate) = " + rate
			+ "  (higher = faster drop-in; default 0.30 ~0.5s, 0.14 ~1.5s) -- hold C to feel it. Get-up uses getupspeed.");
		return 1;
	}

	private static int cmdGetUpSpeed(CommandContext<FabricClientCommandSource> ctx) {
		float rate = FloatArgumentType.getFloat(ctx, "rate");
		TacticalPoseDebug.proneGetUpRate = rate;
		send(ctx, "prone GET-UP speed (ease rate) = " + rate
			+ "  (higher = faster rise; default 0.24 = 0.8x the drop) -- exit prone to feel it. Drop-in uses pronespeed.");
		return 1;
	}

	private static int cmdCrouch(CommandContext<FabricClientCommandSource> ctx) {
		float amount = FloatArgumentType.getFloat(ctx, "amount");
		TacticalPoseDebug.crouchAmount = amount;
		send(ctx, "prone transition CROUCH amount = " + amount
			+ "  (0 = off / straight line, 1 = full crouch; default 0.5) -- hold/release C to feel the crouch");
		return 1;
	}

	private static int cmdGetUp(CommandContext<FabricClientCommandSource> ctx) {
		float amount = FloatArgumentType.getFloat(ctx, "amount");
		TacticalPoseDebug.getUpAmount = amount;
		send(ctx, "GET-UP (pull up with arms + stand on one leg) amount = " + amount
			+ "  (0 = off, 1 = full; default 1.0) -- exit prone to feel it. "
			+ "arm bring-in=" + TacticalPoseDebug.getUpArmIn + " rad, arm push=" + TacticalPoseDebug.getUpArmPush + " rad, standing-leg lift=" + TacticalPoseDebug.getUpLegLift + " rad, body rise=" + TacticalPoseDebug.getUpBodyRise
			+ " (tune each: getuparmin / getuparm / getupleglift / getupbody; negative flips a channel)");
		return 1;
	}

	private static int cmdGetUpArm(CommandContext<FabricClientCommandSource> ctx) {
		float rad = FloatArgumentType.getFloat(ctx, "rad");
		TacticalPoseDebug.getUpArmPush = rad;
		send(ctx, "get-up ARM push-up = " + rad + " rad (both arms raise; negative = flip) -- exit prone to feel it");
		return 1;
	}

	private static int cmdGetUpArmIn(CommandContext<FabricClientCommandSource> ctx) {
		float rad = FloatArgumentType.getFloat(ctx, "rad");
		TacticalPoseDebug.getUpArmIn = rad;
		send(ctx, "get-up ARM bring-in (fold the arms IN from the sides / away from the swim look) = " + rad
			+ " rad (0 = arms stay splayed; higher = more folded under the body; negative = flip) -- exit prone to feel it");
		return 1;
	}

	private static int cmdGetUpLeg(CommandContext<FabricClientCommandSource> ctx) {
		float rad = FloatArgumentType.getFloat(ctx, "rad");
		TacticalPoseDebug.getUpLegLift = rad;
		send(ctx, "get-up STANDING-LEG lift = " + rad + " rad (right leg bends up = stand on one leg; negative = flip) -- exit prone to feel it");
		return 1;
	}

	private static int cmdGetUpBody(CommandContext<FabricClientCommandSource> ctx) {
		float units = FloatArgumentType.getFloat(ctx, "units");
		TacticalPoseDebug.getUpBodyRise = units;
		send(ctx, "get-up BODY rise = " + units + " units (extra hip lift; negative = dip) -- exit prone to feel it");
		return 1;
	}

	private static int cmdCopy(CommandContext<FabricClientCommandSource> ctx) {
		TacticalPoseDebug.copyVanilla();
		send(ctx, "copied the current vanilla pose into the editor");
		return 1;
	}

	private static int cmdReset(CommandContext<FabricClientCommandSource> ctx) {
		TacticalPoseDebug.reset();
		send(ctx, "all parts zeroed");
		return 1;
	}

	private static int cmdDump(CommandContext<FabricClientCommandSource> ctx) {
		for (String line : TacticalPoseDebug.dump().split("\n")) {
			send(ctx, line);
		}
		return 1;
	}

	private static int cmdSet(CommandContext<FabricClientCommandSource> ctx) {
		int i = TacticalPoseDebug.findPart(StringArgumentType.getString(ctx, "part"));
		if (i < 0) {
			ctx.getSource().sendError(Component.literal("unknown part, use: " + String.join(" ", TacticalPoseDebug.PARTS)));
			return 0;
		}
		TacticalPoseDebug.pose[i][0] = FloatArgumentType.getFloat(ctx, "x");
		TacticalPoseDebug.pose[i][1] = FloatArgumentType.getFloat(ctx, "y");
		TacticalPoseDebug.pose[i][2] = FloatArgumentType.getFloat(ctx, "z");
		TacticalPoseDebug.pose[i][3] = FloatArgumentType.getFloat(ctx, "xr");
		TacticalPoseDebug.pose[i][4] = FloatArgumentType.getFloat(ctx, "yr");
		TacticalPoseDebug.pose[i][5] = FloatArgumentType.getFloat(ctx, "zr");
		send(ctx, TacticalPoseDebug.PARTS[i] + " -> " + TacticalPoseDebug.describe(TacticalPoseDebug.pose[i]));
		return 1;
	}

	private static void send(CommandContext<FabricClientCommandSource> ctx, String message) {
		ctx.getSource().sendFeedback(Component.literal("[tacticaledbg] " + message));
	}
				private static int cmdAnimOn(CommandContext<FabricClientCommandSource> ctx) { TacticalPoseDebug.animIdle(); send(ctx, "arm/leg idle animation ENABLED"); return 1; }
				private static int cmdAnimOff(CommandContext<FabricClientCommandSource> ctx) { TacticalPoseDebug.animNone(); send(ctx, "arm/leg idle animation DISABLED"); return 1; }
				private static int cmdAnimIdle(CommandContext<FabricClientCommandSource> ctx) { TacticalPoseDebug.animIdle(); send(ctx, "arm/leg idle animation: idle preset"); return 1; }
				private static int cmdAnimNone(CommandContext<FabricClientCommandSource> ctx) { TacticalPoseDebug.animNone(); send(ctx, "arm/leg idle animation: none (flat)"); return 1; }
				private static int cmdAnimSpeed(CommandContext<FabricClientCommandSource> ctx) { TacticalPoseDebug.animSpeed = FloatArgumentType.getFloat(ctx, "value"); send(ctx, "animSpeed = " + TacticalPoseDebug.animSpeed); return 1; }
				private static int cmdAnimDump(CommandContext<FabricClientCommandSource> ctx) { send(ctx, TacticalPoseDebug.dumpAnim()); return 1; }
				private static int cmdAnimSet(CommandContext<FabricClientCommandSource> ctx) { String part = StringArgumentType.getString(ctx, "part"); int axis = IntegerArgumentType.getInteger(ctx, "axis"); float amp = FloatArgumentType.getFloat(ctx, "amp"); float phase = FloatArgumentType.getFloat(ctx, "phase"); int i = TacticalPoseDebug.findPart(part); if (i < 0) { send(ctx, "unknown part " + part + " (parts: " + String.join("|", TacticalPoseDebug.PARTS) + ")"); return 1; } TacticalPoseDebug.animAmp[i] = amp; TacticalPoseDebug.animAxis[i] = axis; TacticalPoseDebug.animPhase[i] = phase; send(ctx, part + " axis=" + axis + " amp=" + amp + " phase=" + phase); return 1; }
				private static int cmdCrawlPreset(CommandContext<FabricClientCommandSource> ctx) { TacticalPoseDebug.movePreset(); send(ctx, "crawl animation: preset (limbs pump fore-aft while moving)"); return 1; }
				private static int cmdCrawlNone(CommandContext<FabricClientCommandSource> ctx) { TacticalPoseDebug.moveNone(); send(ctx, "crawl animation: OFF (no extra motion while moving)"); return 1; }
				private static int cmdCrawlSpeed(CommandContext<FabricClientCommandSource> ctx) { TacticalPoseDebug.moveSpeed = FloatArgumentType.getFloat(ctx, "value"); send(ctx, "crawl speed = " + TacticalPoseDebug.moveSpeed); return 1; }
				private static int cmdCrawlDump(CommandContext<FabricClientCommandSource> ctx) { send(ctx, TacticalPoseDebug.dumpCrawl()); return 1; }
				private static int cmdCrawlSet(CommandContext<FabricClientCommandSource> ctx) { String part = StringArgumentType.getString(ctx, "part"); int axis = IntegerArgumentType.getInteger(ctx, "axis"); float amp = FloatArgumentType.getFloat(ctx, "amp"); float phase = FloatArgumentType.getFloat(ctx, "phase"); int i = TacticalPoseDebug.findPart(part); if (i < 0) { send(ctx, "unknown part " + part + " (parts: " + String.join("|", TacticalPoseDebug.PARTS) + ")"); return 1; } TacticalPoseDebug.moveAmp[i] = amp; TacticalPoseDebug.moveAxis[i] = axis; TacticalPoseDebug.movePhase[i] = phase; send(ctx, "crawl " + part + " axis=" + axis + " amp=" + amp + " phase=" + phase); return 1; }
}
