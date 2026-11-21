package com.mzcod.tacticalmovement.client.animation;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mzcod.tacticalmovement.network.GunAnimPayload;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Gun animations, for the MinecraftZombies guns with their models (mzguns:..., tools/gun_models in MinecraftZombies):
 * shooting (recoil, and working the bolt or pump between shots), reloading (the gun tilts, the other hand goes down
 * to the magazine and back, or feeds shells / rounds one by one) and sprinting (the gun lowered across the body). Both
 * hands hold the gun, in first person (GunFirstPerson) and third person (arms here, the gun in ItemInHandLayerMixin).
 * <p>
 * The server says when someone fires or starts a reload (GunAnimPayload); sprinting is the player's own.
 * <p>
 * How the gun is turned: the gun hangs from the hand as in third person (its model's thirdperson_righthand display),
 * and {@link #align} turns it about the grip to point where it should (at the crosshair, along the look in third
 * person, across the body when sprinting), with the animations' extra tilt.
 */
public final class GunAnim {
	/** How a gun is worked. */
	public enum Style {
		PISTOL(0.0F), REVOLVER(0.0F), SMG(0.30F), RIFLE(0.40F), BOLT(0.42F), PUMP(0.36F), DOUBLE(0.38F), LMG(0.44F);

		/** How far ahead of the grip the other hand holds it (blocks, along the gun); 0: both hands on the grip. */
		public final float fore;

		Style(float fore) {
			this.fore = fore;
		}
	}

	private static final Map<String, Style> STYLES = new HashMap<>();

	static {
		put(Style.PISTOL, "m1911", "b23r", "five_seven", "kap40", "ray_gun");
		put(Style.REVOLVER, "executioner", "python");
		put(Style.SMG, "thompson", "mp40", "type100", "chicom", "mp5", "pdw57");
		put(Style.RIFLE, "stg44", "gewehr43", "fg42", "an94", "m16a1", "fal", "m8a1", "m14", "m27", "mtar", "smr", "type25",
			"m1_garand", "m1_carbine", "svu", "barrett", "m1216", "s12", "ptrs41");
		// (the PTRS-41 is a semi-automatic anti-tank rifle: no bolt to work)
		put(Style.BOLT, "kar98k", "springfield", "arisaka", "dsr50");
		put(Style.PUMP, "trench_gun", "r870");
		put(Style.DOUBLE, "double_barrel", "olympia", "sawed_off");
		put(Style.LMG, "bar", "hamr", "lsat", "rpd");
	}

	private static void put(Style style, String... ids) {
		for (String id : ids) {
			STYLES.put(id, style);
		}
	}

	/** The gun's style from its model (mzguns:kar98k, mzguns:kar98k_pap), or null when it isn't one of the gun models. */
	/** The gun's id (its item model's path, without "_pap"), or null. */
	public static String gunId(ItemStack stack) {
		Identifier model = stack == null ? null : stack.get(DataComponents.ITEM_MODEL);
		if (model == null || !model.getNamespace().equals("mzguns")) {
			return null;
		}
		String id = model.getPath();
		return id.endsWith("_pap") ? id.substring(0, id.length() - 4) : id;
	}

	public static Style style(ItemStack stack) {
		if (stack == null || stack.isEmpty() || !com.mzcod.tacticalmovement.client.GunItems.isGun(stack)) {
			return null;
		}
		Identifier model = stack.get(DataComponents.ITEM_MODEL);
		if (model == null || !model.getNamespace().equals("mzguns")) {
			return null;
		}
		String id = model.getPath();
		if (id.endsWith("_pap")) {
			id = id.substring(0, id.length() - 4);
		}
		return STYLES.getOrDefault(id, Style.RIFLE);
	}

	// ------------------------------------------------------------------------------------------------ what's going on

	private static final class Anim {
		float shotAt = -1000, shotTicks = 1;
		float reloadAt = -1000, reloadTicks = 1;
		/** Drinking a perk; a gun just picked up (looked over); the I key's inspect (only ever the local player's). */
		float drinkAt = -1000, drinkTicks = 1;
		float lookAt = -1000, lookTicks = 1;
		boolean pickup;
	}

	/** How long the I key's inspect takes (ticks). */
	public static final float INSPECT_TICKS = 50;

	private static final Map<Integer, Anim> ANIMS = new HashMap<>();

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(GunAnimPayload.TYPE, (payload, context) -> {
			Minecraft mc = context.client();
			if (mc.level == null) {
				return;
			}
			if (ANIMS.size() > 64) {
				ANIMS.clear();
			}
			Anim a = ANIMS.computeIfAbsent(payload.entityId(), k -> new Anim());
			com.mzcod.tacticalmovement.TacticalMovement.LOGGER.info("[gun] {} for entity {} ({} ticks)",
				payload.kind() == GunAnimPayload.SHOT ? "shot" : "reload", payload.entityId(), payload.ticks());
			float now = now();
			GunSounds.onAnim(mc, payload);
			switch (payload.kind()) {
				case GunAnimPayload.SHOT -> {
					a.shotAt = now;
					a.shotTicks = payload.ticks();
					a.lookAt = -1000; // firing ends an inspect
				}
				case GunAnimPayload.DRINK -> {
					a.drinkAt = now;
					a.drinkTicks = Math.max(1, payload.ticks());
					a.lookAt = -1000;
				}
				case GunAnimPayload.PICKUP -> {
					a.lookAt = now;
					a.lookTicks = Math.max(1, payload.ticks());
					a.pickup = true;
				}
				default -> {
					a.reloadAt = now;
					a.reloadTicks = Math.max(1, payload.ticks());
					a.lookAt = -1000;
				}
			}
		});
	}

	private static final long CLOCK_START = System.nanoTime();

	/** The animations' clock, in ticks (50 ms) of real time. */
	private static float now() {
		Minecraft mc = Minecraft.getInstance();
		return (System.nanoTime() - CLOCK_START) / 50_000_000.0F;
	}

	/** How far into drinking a perk (0..1), or -1. */
	public static float drink(int entityId) {
		Anim a = ANIMS.get(entityId);
		if (a == null) {
			return -1;
		}
		float p = (now() - a.drinkAt) / a.drinkTicks;
		return p < 0 || p >= 1 ? -1 : p;
	}

	/** The I key: look the held gun over (not while firing, reloading or drinking). */
	public static void inspect(int entityId) {
		Anim a = ANIMS.computeIfAbsent(entityId, k -> new Anim());
		if (reload(entityId) >= 0 || drink(entityId) >= 0 || look(entityId) >= 0 || now() - a.shotAt < 8) {
			return;
		}
		a.lookAt = now();
		a.lookTicks = INSPECT_TICKS;
		a.pickup = false;
	}

	/** How far into looking the gun over (0..1), or -1. */
	public static float look(int entityId) {
		Anim a = ANIMS.get(entityId);
		if (a == null) {
			return -1;
		}
		float p = (now() - a.lookAt) / a.lookTicks;
		return p < 0 || p >= 1 ? -1 : p;
	}

	/** Whether the current look is a new gun's (bought or from the box) rather than the I key's. */
	public static boolean lookIsPickup(int entityId) {
		Anim a = ANIMS.get(entityId);
		return a != null && a.pickup;
	}

	/**
	 * The look's pose: {right, up, back (blocks), yaw, pitch, roll (degrees), charge (0..1: the other hand on the charging
	 * handle)}. The I key: turned to show its left side, then over to its right and top. A new gun: brought up from
	 * below, turned to look at, and its charging handle pulled.
	 */
	public static float[] lookPose(int entityId) {
		float p = look(entityId);
		float[] out = new float[7];
		if (p < 0) {
			return out;
		}
		float w = Math.min(smooth(p / 0.15F), smooth((1 - p) / 0.15F));
		if (!lookIsPickup(entityId)) {
			float s1 = smooth((p - 0.08F) / 0.17F) * (1 - smooth((p - 0.45F) / 0.15F));
			float s2 = smooth((p - 0.45F) / 0.15F) * (1 - smooth((p - 0.8F) / 0.15F));
			out[0] = -0.1F * w;
			out[1] = 0.05F * w;
			out[2] = 0.08F * w;
			out[3] = 35 * s1 - 25 * s2;
			out[4] = 10 * s1 + 18 * s2;
			out[5] = -55 * s1 + 45 * s2;
			return out;
		}
		float rise = 1 - smooth(p / 0.22F);
		float s1 = smooth((p - 0.15F) / 0.15F) * (1 - smooth((p - 0.5F) / 0.12F));
		float charge = smooth((p - 0.52F) / 0.1F) * (1 - smooth((p - 0.8F) / 0.1F));
		float pull = p > 0.68F && p < 0.78F ? Mth.sin((p - 0.68F) / 0.1F * Mth.PI) : 0;
		out[0] = -0.06F * w;
		out[1] = -0.5F * rise + 0.03F * w;
		out[2] = 0.05F * w + 0.02F * pull;
		out[3] = 28 * s1 + 8 * charge;
		out[4] = 8 * s1 + 6 * charge - 30 * rise;
		out[5] = -40 * s1 - 20 * charge;
		out[6] = charge;
		return out;
	}

	/** Just fired (holding the trigger keeps it at 1): 1, fading out a little after the last shot. Raises a sprinting gun. */
	public static float firing(int entityId) {
		Anim a = ANIMS.get(entityId);
		if (a == null) {
			return 0;
		}
		float t = now() - a.shotAt;
		return t < 0 ? 0 : t < 10 ? 1 : 1 - smooth((t - 10) / 8);
	}

	/** The kick of a shot: up fast, settling back over a few ticks (0..1). */
	public static float recoil(int entityId) {
		Anim a = ANIMS.get(entityId);
		if (a == null) {
			return 0;
		}
		float t = (now() - a.shotAt) / Mth.clamp(a.shotTicks * 0.7F, 3, 6);
		if (t < 0 || t >= 1) {
			return 0;
		}
		return t < 0.15F ? t / 0.15F : smooth(1 - (t - 0.15F) / 0.85F);
	}

	/** How hard a kind of gun kicks (1: a pistol). */
	public static float kick(Style style) {
		return switch (style) {
			case PISTOL -> 1.0F;
			case REVOLVER -> 1.6F;
			case SMG -> 0.7F;
			case RIFLE -> 1.1F;
			case BOLT -> 1.9F;
			case PUMP, DOUBLE -> 2.2F;
			case LMG -> 0.9F;
		};
	}

	/** The wobble after a shot's kick: a spring settling over about half a second (-1..1, 0 when still). */
	public static float bob(int entityId) {
		Anim a = ANIMS.get(entityId);
		if (a == null) {
			return 0;
		}
		float t = now() - a.shotAt - 1.0F;
		if (t < 0 || t > 12) {
			return 0;
		}
		return Mth.sin(t * 1.3F) * (float) Math.exp(-t * 0.33F);
	}

	/** Working the action between shots (the bolt or the pump), 0..1..0 over the fire delay; 0 for other guns. */
	public static float cycle(int entityId, Style style) {
		Anim a = ANIMS.get(entityId);
		if (a == null || (style != Style.BOLT && style != Style.PUMP)) {
			return 0;
		}
		float t = (now() - a.shotAt) / Math.max(6, a.shotTicks);
		if (t < 0.25F || t > 0.95F) {
			return 0;
		}
		return Mth.sin((t - 0.25F) / 0.7F * Mth.PI);
	}

	/** How far into a reload it is (0..1), or -1 when not reloading. */
	public static float reload(int entityId) {
		Anim a = ANIMS.get(entityId);
		if (a == null) {
			return -1;
		}
		float p = (now() - a.reloadAt) / a.reloadTicks;
		return p < 0 || p >= 1 ? -1 : p;
	}

	/** The reload's weight: in over the first tenth, out over the last. */
	public static float reloadWeight(float p) {
		return p < 0 ? 0 : smooth(Math.min(p / 0.1F, (1 - p) / 0.1F));
	}

	/**
	 * Where the other hand is in a reload, 0 (on the gun as usual) .. 1 (down at the magazine / feeding a round), and
	 * 2 (away below, fetching the next magazine): magazine guns go 0 -> 1 -> 2 -> 1 -> 0; the bolt, pump and double
	 * guns go to the gun's top and back again and again (a round at a time); a revolver's hand stays at the cylinder.
	 */
	public static float reloadHand(float p, Style style) {
		if (p < 0) {
			return 0;
		}
		switch (style) {
			case BOLT, PUMP, DOUBLE -> {
				float e = reloadWeight(p);
				return e * (0.5F + 0.5F * Mth.sin(p * Mth.PI * 2 * (style == Style.DOUBLE ? 2 : 4) - Mth.HALF_PI));
			}
			case REVOLVER -> {
				return reloadWeight(p);
			}
			default -> {
				// down to the magazine (0.1-0.25), away with it (0.25-0.4), back with a new one (0.55-0.7), on again (0.8-0.92)
				if (p < 0.1F) return 0;
				if (p < 0.25F) return smooth((p - 0.1F) / 0.15F);
				if (p < 0.4F) return 1 + smooth((p - 0.25F) / 0.15F);
				if (p < 0.55F) return 2;
				if (p < 0.7F) return 2 - smooth((p - 0.55F) / 0.15F);
				if (p < 0.8F) return 1;
				if (p < 0.92F) return 1 - smooth((p - 0.8F) / 0.12F);
				return 0;
			}
		}
	}

	static float smooth(float x) {
		x = Mth.clamp(x, 0, 1);
		return x * x * (3 - 2 * x);
	}

	// ------------------------------------------------------------------------------------------------ turning the gun

	// The gun's own directions in the hand's frame (after ItemInHandLayer's turn to the hand): the models' third-person
	// display turns them 72 degrees about x (guns.py TP_ROTATION), so forward (-z) and up (+y) become these
	private static final float C72 = Mth.cos(72 * Mth.DEG_TO_RAD), S72 = Mth.sin(72 * Mth.DEG_TO_RAD);
	private static final Vector3f GUN_FORWARD = new Vector3f(0, S72, -C72), GUN_UP = new Vector3f(0, C72, S72);

	/**
	 * Turns the pose (the hand's frame, the grip at its origin) so the gun points along {@code forward} with its top
	 * towards {@code up} (both in the pose's parent space: camera space for first person, view space for third), keeping
	 * where the grip is and the pose's scale.
	 */
	public static void align(PoseStack poseStack, Vector3f forward, Vector3f up) {
		PoseStack.Pose pose = poseStack.last();
		Matrix4f m = pose.pose();
		float scale = new Vector3f(m.m00(), m.m01(), m.m02()).length();
		Vector3f f = new Vector3f(forward).normalize();
		Vector3f u = new Vector3f(up).sub(new Vector3f(f).mul(up.dot(f))).normalize();
		Vector3f r = new Vector3f(f).cross(u);
		Vector3f lf = GUN_FORWARD, lu = GUN_UP, lr = new Vector3f(lf).cross(lu);
		// R maps the local basis to the wanted one: R = [f u r] * [lf lu lr]^T
		Matrix3f want = new Matrix3f(f.x, f.y, f.z, u.x, u.y, u.z, r.x, r.y, r.z);
		Matrix3f local = new Matrix3f(lf.x, lf.y, lf.z, lu.x, lu.y, lu.z, lr.x, lr.y, lr.z).transpose();
		Matrix3f rot = want.mul(local);
		float tx = m.m30(), ty = m.m31(), tz = m.m32();
		m.set(rot).scale(scale);
		m.setTranslation(tx, ty, tz);
		pose.normal().set(rot);
	}

	/** v turned by `degrees` about `axis` (unit). */
	static Vector3f turn(Vector3f v, Vector3f axis, float degrees) {
		return new Vector3f(v).rotateAxis(degrees * Mth.DEG_TO_RAD, axis.x, axis.y, axis.z);
	}

	/**
	 * The gun's direction and top after the animations: yaw (to the left, about up), pitch (muzzle up, about right) and
	 * roll (top to the right, about forward), in degrees. Writes over forward and up.
	 */
	static void tilt(Vector3f forward, Vector3f up, float yaw, float pitch, float roll) {
		Vector3f f = new Vector3f(forward).normalize();
		Vector3f u = new Vector3f(up).sub(new Vector3f(f).mul(up.dot(f))).normalize();
		Vector3f r = new Vector3f(f).cross(u);
		f = turn(f, u, yaw);
		r = new Vector3f(f).cross(u).normalize();
		f = turn(f, r, pitch);
		u = turn(u, r, pitch);
		u = turn(u, f, roll);
		forward.set(f);
		up.set(u);
	}

	// ------------------------------------------------------------------------------------------- the gun's own tilt

	/** {yaw, pitch, roll} the animations add to the gun (degrees), with sprint 0..1. */
	public static float[] gunTilt(int entityId, Style style, float sprint) {
		float r = recoil(entityId);
		float c = cycle(entityId, style);
		float p = reload(entityId);
		float e = reloadWeight(p);
		float yaw = 38 * sprint;
		float k = kick(style);
		float b = bob(entityId);
		float pitch = (16 * r + 3 * b) * k - 28 * sprint + 14 * e;
		float roll = 2.5F * b * k + 30 * sprint + (style == Style.REVOLVER ? 70 : 32) * e + (style == Style.BOLT ? 18 : 0) * c;
		if (style == Style.DOUBLE) {
			pitch -= 15 * e; // tipped down a little; the barrels themselves swing open (GunFirstPerson)
		}
		return new float[] {yaw, pitch, roll};
	}

	// ----------------------------------------------------------------------------------------------- third person

	/** Whether the gun pose applies: standing or crouched (prone, swimming, gliding and the downed pose keep their own arms). */
	public static boolean posed(AvatarRenderState state) {
		var stance = ((TacticalRenderStateAccess) state).tacticalMovement$getStance();
		return stance != com.mzcod.tacticalmovement.movement.MovementStance.PRONE && !state.isFallFlying && !state.isVisuallySwimming
			&& style(state.mainArm == net.minecraft.world.entity.HumanoidArm.LEFT ? state.leftHandItemStack : state.rightHandItemStack) != null;
	}

	/** Both arms on the gun in third person (called after the player model's own setupAnim). */
	public static void applyModel(AvatarRenderState state, PlayerModel model, float sprint) {
		Style style = style(state.mainArm == net.minecraft.world.entity.HumanoidArm.LEFT ? state.leftHandItemStack : state.rightHandItemStack);
		if (style == null) {
			return;
		}
		sprint *= 1 - firing(state.id);
		boolean rightHanded = state.mainArm != net.minecraft.world.entity.HumanoidArm.LEFT;
		var gunArm = rightHanded ? model.rightArm : model.leftArm;
		var otherArm = rightHanded ? model.leftArm : model.rightArm;
		float side = rightHanded ? 1 : -1;
		float headX = state.xRot * Mth.DEG_TO_RAD, headY = state.yRot * Mth.DEG_TO_RAD;
		float r = recoil(state.id), c = cycle(state.id, style), p = reload(state.id), e = reloadWeight(p);
		float hand = reloadHand(p, style);
		float aim = 1 - sprint;
		// Aiming: the gun arm along the look, the other reaching across to hold it ahead (a pistol: both hands on the grip)
		float otherYaw = side * (style.fore > 0 ? 0.62F : 0.38F);
		gunArm.xRot = aim * (-Mth.HALF_PI + headX + 0.1F) + sprint * -0.55F - 0.2F * r + 0.35F * e;
		gunArm.yRot = aim * (-side * 0.1F + headY) + sprint * (-side * 0.25F);
		gunArm.zRot = 0;
		otherArm.xRot = aim * (-Mth.HALF_PI + headX + 0.05F) + sprint * -0.95F - 0.15F * r + 0.2F * c;
		otherArm.yRot = aim * (otherYaw + headY) + sprint * (side * 0.75F);
		otherArm.zRot = 0;
		// Reloading: the other hand down to the magazine and away (or up to the top, feeding rounds)
		if (p >= 0) {
			float down = Math.min(1, hand), away = Math.max(0, hand - 1);
			boolean top = style == Style.BOLT || style == Style.PUMP || style == Style.DOUBLE;
			otherArm.xRot = Mth.lerp(down, otherArm.xRot, top ? -1.25F : -0.55F) + 0.45F * away;
			otherArm.yRot = Mth.lerp(down, otherArm.yRot, side * (top ? 0.45F : 0.3F)) - side * 0.2F * away;
		}
	}

	/** Third person: where the gun points, in the model's space (y down, the body facing -z), and its top. */
	public static void thirdPersonAim(AvatarRenderState state, Style style, float sprint, Vector3f forward, Vector3f up) {
		float pitch = state.xRot * Mth.DEG_TO_RAD, yaw = state.yRot * Mth.DEG_TO_RAD;
		// the head's look: Ry(yaw) Rx(pitch) (0, 0, -1)
		Vector3f look = new Vector3f(-Mth.cos(pitch) * Mth.sin(yaw), Mth.sin(pitch), -Mth.cos(pitch) * Mth.cos(yaw));
		forward.set(look);
		up.set(0, -1, 0);
		float side = state.mainArm == net.minecraft.world.entity.HumanoidArm.LEFT ? -1 : 1;
		float[] t = gunTilt(state.id, style, sprint * (1 - firing(state.id)));
		// (the model's space is the camera's turned over, not mirrored: with `up` the real up, the tilt is the same)
		tilt(forward, up, side * t[0], t[1], side * t[2]);
	}

	// ----------------------------------------------------------------------------------------------- first person

	/** The other hand's place relative to the grip (camera space; forward, up and right are the gun's). */
	public static Vector3f otherHand(int entityId, Style style, Vector3f grip, Vector3f forward, Vector3f up) {
		return otherHand(entityId, style, grip, forward, up, null);
	}

	/** magAt: where the gun's magazine really is (its own model's), or null to guess from the kind of gun. */
	/**
	 * Third person: drinking a perk, the off hand (the bottle's) raised to the mouth and tipped, then thrown away. Any
	 * held item: called for every player model.
	 */
	public static void applyDrink(AvatarRenderState state, PlayerModel model) {
		float p = drink(state.id);
		if (p < 0) {
			return;
		}
		boolean rightHanded = state.mainArm != net.minecraft.world.entity.HumanoidArm.LEFT;
		var arm = rightHanded ? model.leftArm : model.rightArm;
		float side = rightHanded ? -1 : 1;
		float up = smooth((p - 0.12F) / 0.2F) * (1 - smooth((p - 0.7F) / 0.12F));
		float toss = p > 0.7F && p < 0.86F ? Mth.sin((p - 0.7F) / 0.16F * Mth.PI) : 0;
		arm.xRot = Mth.lerp(up, arm.xRot, -2.3F + state.xRot * Mth.DEG_TO_RAD * 0.5F) - 1.2F * toss;
		arm.yRot = Mth.lerp(up, arm.yRot, side * -0.55F);
		arm.zRot = Mth.lerp(up, arm.zRot, 0) + side * 0.6F * toss;
	}

	public static Vector3f otherHand(int entityId, Style style, Vector3f grip, Vector3f forward, Vector3f up, Vector3f magAt) {
		Vector3f f = new Vector3f(forward).normalize();
		Vector3f u = new Vector3f(up).normalize();
		Vector3f r = new Vector3f(f).cross(u).normalize();
		float p = reload(entityId);
		float hand = reloadHand(p, style);
		float c = cycle(entityId, style);
		// Holding it: ahead on the fore-end (a pistol: cupped under the grip)
		Vector3f at = new Vector3f(grip).add(new Vector3f(f).mul(style.fore)).add(new Vector3f(u).mul(style.fore > 0 ? -0.04F : -0.09F));
		if (style.fore == 0) {
			at.add(new Vector3f(r).mul(-0.03F));
		}
		if (style == Style.PUMP) {
			at.add(new Vector3f(f).mul(-0.12F * c)); // working the pump
		}
		float charge = lookPose(entityId)[6];
		if (charge > 0) {
			at.lerp(new Vector3f(grip).add(new Vector3f(u).mul(0.09F)).add(new Vector3f(f).mul(0.06F)).add(new Vector3f(r).mul(0.04F)), charge);
		}
		if (p >= 0) {
			boolean top = style == Style.BOLT || style == Style.PUMP || style == Style.DOUBLE;
			Vector3f mag = new Vector3f(grip).add(new Vector3f(f).mul(style.fore * 0.35F + 0.04F))
				.add(new Vector3f(u).mul(top ? 0.06F : -0.12F));
			if (magAt != null && !top) {
				mag.set(magAt);
			}
			Vector3f below = new Vector3f(mag).add(0, -0.45F, 0.1F);
			if (hand <= 1) {
				at.lerp(mag, hand);
			} else {
				at.set(mag).lerp(below, hand - 1);
			}
		}
		return at;
	}
}
