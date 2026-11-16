package com.mzcod.tacticalmovement.client.animation;

import com.mzcod.tacticalmovement.network.RisePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.Map;

// A zombie digging itself up out of the ground when it spawns. The server (MinecraftZombies) sends a RisePayload for each
// new zombie and keeps it frozen while this plays; the client sinks the real zombie model into the ground and plays the
// keyframes from ZombieRiseKeys (the same ones the server's BetterModel model uses for players without the mod). The
// terrain hides whatever is still underground. The last moments blend into the normal ZombieAnim pose.
public final class ZombieRise {
	private static final float BLEND_OUT = 0.3F;
	private static final float DEG = Mth.PI / 180.0F;
	/** entity id -> System.nanoTime() the rise started, and its length in seconds */
	private static final Map<Integer, long[]> RISING = new HashMap<>();
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("tacticalmovement");
	private static final java.util.Set<Integer> LOGGED = new java.util.HashSet<>();
	/** Rise time of the entity a render state is for (set when the state is filled in, from that entity's id). */
	public static final net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey<Float> TIME =
		net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey.create(() -> "tacticalmovement:rise_time");

	private ZombieRise() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(RisePayload.TYPE, (payload, context) -> {
			var lvl = context.client().level;
			LOG.info("[ZombieRise] rise for entity {} ({} ticks), in level yet: {}", payload.entityId(), payload.ticks(),
				lvl != null && lvl.getEntity(payload.entityId()) != null);
			RISING.put(payload.entityId(), new long[] {System.nanoTime(), payload.ticks() * 50_000_000L});
		});
	}

	/** How far into its rise this entity is, in keyframe seconds, or null when it is not rising. Read with the entity at hand. */
	/** Off in the no-mod test view (/tacticaledbg vanilla): the server's model is shown instead. */
	public static boolean enabled = true;

	public static Float progress(int entityId) {
		if (!enabled) {
			return null;
		}
		if (freezeAt >= 0) {
			return freezeAt;
		}
		if (RISING.isEmpty()) {
			return null;
		}
		long[] r = RISING.get(entityId);
		if (r == null) {
			return null;
		}
		long elapsed = System.nanoTime() - r[0];
		if (elapsed >= r[1] || elapsed < 0) {
			RISING.remove(entityId);
			return null;
		}
		if (LOGGED.add(entityId)) {
			LOG.info("[ZombieRise] animating entity {}", entityId);
		}
		// The keyframes are LENGTH long; a different length from the server just plays them faster or slower
		return (float) elapsed / r[1] * ZombieRiseKeys.LENGTH;
	}

	/** Debug: when >= 0, every zombie is held at this point of the rise (keyframe seconds). */
	public static float freezeAt = -1;
	private static long lastApplyLog;

	/** Debug: zombies within range of the player start rising now, over the given seconds. Returns how many. */
	public static int debugRise(net.minecraft.client.Minecraft mc, double range, float seconds) {
		if (mc.level == null || mc.player == null) {
			return 0;
		}
		int n = 0;
		long now = System.nanoTime();
		for (var e : mc.level.entitiesForRendering()) {
			if (e instanceof net.minecraft.world.entity.monster.zombie.Zombie && e.distanceTo(mc.player) <= range) {
				RISING.put(e.getId(), new long[] {now, (long) (seconds * 1e9)});
				LOGGED.remove(e.getId());
				n++;
			}
		}
		return n;
	}

	/** Called after ZombieAnim (or vanilla) has posed a zombie: overrides the pose while that zombie is rising. */
	public static void apply(HumanoidModel<?> m, HumanoidRenderState s) {
		Float time = s.getData(TIME);
		if (time == null) {
			return;
		}
		float t = time;
		long nowMs = System.currentTimeMillis();
		if (nowMs - lastApplyLog > 1000) {
			lastApplyLog = nowMs;
			LOG.info("[ZombieRise] posing a {} at t={}", m.getClass().getSimpleName(), t);
		}
		float w = smooth((ZombieRiseKeys.LENGTH - t) / BLEND_OUT);

		float[] root = sample(ZombieRiseKeys.ROOT_POS, t);
		float[] torso = sample(ZombieRiseKeys.TORSO_ROT, t);
		float[] head = sample(ZombieRiseKeys.HEAD_ROT, t);
		float[] ra = sample(ZombieRiseKeys.RIGHT_ARM_ROT, t);
		float[] la = sample(ZombieRiseKeys.LEFT_ARM_ROT, t);
		float[] rl = sample(ZombieRiseKeys.RIGHT_LEG_ROT, t);
		float[] ll = sample(ZombieRiseKeys.LEFT_LEG_ROT, t);

		// Blockbench space -> model space (x and y are mirrored): xRot and yRot flip sign, zRot keeps it, y flips.
		// In the keyframes the head and arms are children of the torso; here they are not, so the torso's lean is added.
		float lean = -torso[0] * DEG;
		float cos = Mth.cos(lean), sin = Mth.sin(lean);
		float by = 12.0F - 12.0F * cos, bz = -12.0F * sin;
		float sy = by + 2.0F * cos, sz = bz + 2.0F * sin;

		// Sunk into the ground: every part moves down (model y points down)
		float sink = -root[1] * w;
		pose(m.body, by, bz, lean, -torso[1] * DEG, torso[2] * DEG, w);
		pose(m.head, by, bz, lean - head[0] * DEG, -head[1] * DEG, head[2] * DEG, w);
		pose(m.rightArm, sy, sz, lean - ra[0] * DEG, -ra[1] * DEG, ra[2] * DEG, w);
		pose(m.leftArm, sy, sz, lean - la[0] * DEG, -la[1] * DEG, la[2] * DEG, w);
		m.rightLeg.xRot = lerp(m.rightLeg.xRot, -rl[0] * DEG, w);
		m.rightLeg.zRot = lerp(m.rightLeg.zRot, rl[2] * DEG, w);
		m.leftLeg.xRot = lerp(m.leftLeg.xRot, -ll[0] * DEG, w);
		m.leftLeg.zRot = lerp(m.leftLeg.zRot, ll[2] * DEG, w);
		for (ModelPart p : new ModelPart[] {m.head, m.body, m.rightArm, m.leftArm, m.rightLeg, m.leftLeg}) {
			p.y += sink;
		}
	}

	/**
	 * The rise on the bending body (BodyRig), which is what living zombies are drawn with when the zombie animations are on.
	 * Built in world space from the same keyframes, then blended into the normal pose over the last moments.
	 */
	public static com.mzcod.tacticalmovement.client.cutscene.body.BodyRig.Pose rigPose(HumanoidRenderState s, float t,
		com.mzcod.tacticalmovement.client.cutscene.body.BodyRig.Pose normal) {
		long nowMs = System.currentTimeMillis();
		if (nowMs - lastApplyLog > 1000) {
			lastApplyLog = nowMs;
			LOG.info("[ZombieRise] posing a rig zombie at t={}", t);
		}
		float[] root = sample(ZombieRiseKeys.ROOT_POS, t);
		float[] torso = sample(ZombieRiseKeys.TORSO_ROT, t);
		float[] head = sample(ZombieRiseKeys.HEAD_ROT, t);
		float[] ra = sample(ZombieRiseKeys.RIGHT_ARM_ROT, t);
		float[] la = sample(ZombieRiseKeys.LEFT_ARM_ROT, t);
		float[] rl = sample(ZombieRiseKeys.RIGHT_LEG_ROT, t);
		float[] ll = sample(ZombieRiseKeys.LEFT_LEG_ROT, t);

		var p = new com.mzcod.tacticalmovement.client.cutscene.body.BodyRig.Pose();
		double yr = Math.toRadians(s.bodyRot);
		net.minecraft.world.phys.Vec3 up = new net.minecraft.world.phys.Vec3(0, 1, 0);
		net.minecraft.world.phys.Vec3 fwd = new net.minecraft.world.phys.Vec3(-Math.sin(yr), 0, Math.cos(yr));
		net.minecraft.world.phys.Vec3 right = fwd.cross(up).normalize();
		// Sunk into the ground by the root's (negative) height
		net.minecraft.world.phys.Vec3 feet = new net.minecraft.world.phys.Vec3(s.x, s.y + root[1] / 16.0, s.z);
		float lean = -torso[0] * DEG; // + = forward
		float roll = torso[2] * DEG;
		p.pelvis = feet.add(0, 0.75, 0);
		p.up = com.mzcod.tacticalmovement.client.cutscene.body.BodyRig.rot(
			com.mzcod.tacticalmovement.client.cutscene.body.BodyRig.rot(up, right, -lean * 0.75), fwd, roll);
		p.front = com.mzcod.tacticalmovement.client.cutscene.body.BodyRig.rot(fwd, right, -lean * 0.75);
		p.bend = lean * 0.25F;
		p.twist = -torso[1] * DEG;
		p.headYaw = -head[1] * DEG;
		p.headPitch = -head[0] * DEG; // the keyframes' + X looks up; here + is chin down
		p.lean = head[2] * DEG * 0.3F;

		// Legs from the hips, swung forward by the keyframes' X (knees lifting as it steps out)
		for (int f = 0; f < 2; f++) {
			boolean r = f == 0;
			float x = (r ? rl[0] : ll[0]) * DEG;
			net.minecraft.world.phys.Vec3 hip = p.pelvis.add(right.scale(r ? 0.125 : -0.125));
			double reach = 0.74 - 0.25 * Math.min(1.0, x); // a raised leg bends at the knee
			net.minecraft.world.phys.Vec3 foot = hip.add(fwd.scale(Math.sin(x) * reach)).subtract(0, Math.cos(x) * reach, 0);
			if (r) {
				p.footR = foot;
				p.kneeR = fwd;
			} else {
				p.footL = foot;
				p.kneeL = fwd;
			}
		}

		// Arms from the shoulders of the posed chest: X swings them forward and up, Z out to the side
		var body = com.mzcod.tacticalmovement.client.cutscene.body.BodyRig.solve(p, false);
		float aR = ra[0] * DEG, aL = la[0] * DEG;
		net.minecraft.world.phys.Vec3 dr = body.cU.scale(-Math.cos(aR)).add(body.cF.scale(Math.sin(aR))).add(body.cR.scale(Math.sin(-ra[2] * DEG) * -1)).normalize();
		net.minecraft.world.phys.Vec3 dl = body.cU.scale(-Math.cos(aL)).add(body.cF.scale(Math.sin(aL))).subtract(body.cR.scale(Math.sin(la[2] * DEG))).normalize();
		p.handR = body.shoulderR.add(dr.scale(0.70));
		p.handL = body.shoulderL.add(dl.scale(0.70));
		p.elbowR = body.cU.scale(-1).add(body.cR.scale(0.4)).subtract(body.cF.scale(0.4));
		p.elbowL = body.cU.scale(-1).subtract(body.cR.scale(0.4)).subtract(body.cF.scale(0.4));

		// The last moments blend into the normal standing pose
		float w = 1.0F - smooth((ZombieRiseKeys.LENGTH - t) / BLEND_OUT);
		if (w > 0.0F && normal != null) {
			p.pelvis = mix(p.pelvis, normal.pelvis, w);
			p.up = mix(p.up, normal.up, w).normalize();
			p.front = mix(p.front, normal.front, w).normalize();
			p.bend = lerp(p.bend, normal.bend, w);
			p.twist = lerp(p.twist, normal.twist, w);
			p.lean = lerp(p.lean, normal.lean, w);
			p.headYaw = lerp(p.headYaw, normal.headYaw, w);
			p.headPitch = lerp(p.headPitch, normal.headPitch, w);
			p.handR = mix(p.handR, normal.handR, w);
			p.handL = mix(p.handL, normal.handL, w);
			p.footR = mix(p.footR, normal.footR, w);
			p.footL = mix(p.footL, normal.footL, w);
		}
		return p;
	}

	private static net.minecraft.world.phys.Vec3 mix(net.minecraft.world.phys.Vec3 a, net.minecraft.world.phys.Vec3 b, float w) {
		return a == null ? b : b == null ? a : a.lerp(b, w);
	}

	/** True while the entity is still climbing out (used to skip its idle twitches etc. if needed). */
	public static boolean isRising(int entityId) {
		return RISING.containsKey(entityId);
	}

	private static void pose(ModelPart p, float y, float z, float xRot, float yRot, float zRot, float w) {
		p.y = lerp(p.y, y, w);
		p.z = lerp(p.z, z, w);
		p.xRot = lerp(p.xRot, xRot, w);
		p.yRot = lerp(p.yRot, yRot, w);
		p.zRot = lerp(p.zRot, zRot, w);
	}

	/** Catmull-Rom through the keyframes, like Blockbench's "catmullrom" interpolation. Rows are {time, x, y, z}. */
	private static float[] sample(float[][] keys, float t) {
		int n = keys.length;
		if (t <= keys[0][0]) {
			return new float[] {keys[0][1], keys[0][2], keys[0][3]};
		}
		if (t >= keys[n - 1][0]) {
			return new float[] {keys[n - 1][1], keys[n - 1][2], keys[n - 1][3]};
		}
		int i = 0;
		while (i < n - 2 && t > keys[i + 1][0]) {
			i++;
		}
		float[] p0 = keys[Math.max(0, i - 1)], p1 = keys[i], p2 = keys[i + 1], p3 = keys[Math.min(n - 1, i + 2)];
		float u = (t - p1[0]) / Math.max(1e-4F, p2[0] - p1[0]);
		float[] out = new float[3];
		for (int c = 0; c < 3; c++) {
			float a = p0[c + 1], b = p1[c + 1], d = p2[c + 1], e = p3[c + 1];
			out[c] = 0.5F * (2 * b + (-a + d) * u + (2 * a - 5 * b + 4 * d - e) * u * u + (-a + 3 * b - 3 * d + e) * u * u * u);
		}
		return out;
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}
}
