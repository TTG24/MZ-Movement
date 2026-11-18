package com.mzcod.tacticalmovement.client.zombie;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Blockbench model (.bbmodel) as the mod draws it: its bones (pivot, parent, cubes) and its animations' keyframes.
 * Written for tools/models/zombie_body.py's output (the same file BetterModel draws for players without the mod), so it
 * reads what that writes: cubes without their own rotation, per-face UVs, inflate, light emission; keyframes with linear,
 * catmullrom or step easing. Rotations are stored the way BetterModel wants them (X and Y negated); they are turned back
 * into Blockbench's own angles here, so everything below is in Blockbench terms: pixels, y up, front -z, right +x.
 */
public final class BbModel {
	public static final int ROT = 0, POS = 1, SCALE = 2;
	/** Face order: north, east, south, west, up, down. */
	static final String[] FACES = {"north", "east", "south", "west", "up", "down"};

	public final List<Bone> bones = new ArrayList<>();
	public final Map<String, Integer> boneIndex = new HashMap<>();
	public final Map<String, Animation> animations = new HashMap<>();
	/** Texture index -> UV size (64 for the skin and eyes, 16 for the gore). */
	final float[] uvSize;

	public static final class Bone {
		public final String name;
		public final int parent;
		public final float px, py, pz;
		final List<Cube> cubes = new ArrayList<>();

		Bone(String name, int parent, float px, float py, float pz) {
			this.name = name;
			this.parent = parent;
			this.px = px;
			this.py = py;
			this.pz = pz;
		}
	}

	static final class Cube {
		final float x0, y0, z0, x1, y1, z1;
		final boolean glowing;
		/** Per face (FACES order): u0, v0, u1, v1; null when the face isn't drawn. */
		final float[][] uv = new float[6][];
		final int[] texture = new int[6];

		Cube(float x0, float y0, float z0, float x1, float y1, float z1, boolean glowing) {
			this.x0 = x0;
			this.y0 = y0;
			this.z0 = z0;
			this.x1 = x1;
			this.y1 = y1;
			this.z1 = z1;
			this.glowing = glowing;
		}
	}

	/** One channel of one bone: keyframe times, values (x, y, z each) and easing. */
	static final class Track {
		final float[] times;
		final float[][] values;
		final byte[] easing; // 0 linear, 1 catmullrom, 2 step

		Track(float[] times, float[][] values, byte[] easing) {
			this.times = times;
			this.values = values;
			this.easing = easing;
		}

		void sample(float t, float[] out, int at) {
			int n = times.length;
			if (t <= times[0] || n == 1) {
				System.arraycopy(values[0], 0, out, at, 3);
				return;
			}
			if (t >= times[n - 1]) {
				System.arraycopy(values[n - 1], 0, out, at, 3);
				return;
			}
			int i = Arrays.binarySearch(times, t);
			if (i < 0) {
				i = -i - 2;
			}
			i = Math.min(i, n - 2);
			float t0 = times[i], t1 = times[i + 1];
			float f = t1 > t0 ? (t - t0) / (t1 - t0) : 0;
			float[] a = values[i], b = values[i + 1];
			if (easing[i] == 2) {
				System.arraycopy(a, 0, out, at, 3);
			} else if (easing[i] == 1) {
				float[] p = values[Math.max(0, i - 1)], q = values[Math.min(n - 1, i + 2)];
				float f2 = f * f, f3 = f2 * f;
				for (int k = 0; k < 3; k++) {
					out[at + k] = 0.5F * (2 * a[k] + (-p[k] + b[k]) * f + (2 * p[k] - 5 * a[k] + 4 * b[k] - q[k]) * f2
						+ (-p[k] + 3 * a[k] - 3 * b[k] + q[k]) * f3);
				}
			} else {
				for (int k = 0; k < 3; k++) {
					out[at + k] = a[k] + (b[k] - a[k]) * f;
				}
			}
		}
	}

	public static final class Animation {
		public final String name;
		public final float length;
		/** "loop", "once" or "hold". */
		public final String loop;
		/** [bone][channel], null where the animation doesn't key it. */
		final Track[][] tracks;

		Animation(String name, float length, String loop, int bones) {
			this.name = name;
			this.length = length;
			this.loop = loop;
			this.tracks = new Track[bones][3];
		}

		public boolean loops() {
			return loop.equals("loop");
		}

		/** Does it key this bone at all (an attack keys only the upper body)? */
		public boolean keys(int bone) {
			Track[] t = tracks[bone];
			return t[ROT] != null || t[POS] != null || t[SCALE] != null;
		}

		/**
		 * The bone's pose at time t (seconds) into out[at..at+9): rotation (degrees), position (pixels), scale. Channels it
		 * doesn't key stay at rest (0 / 0 / 1).
		 */
		public void sample(int bone, float t, float[] out, int at) {
			if (loops() && length > 0) {
				t %= length;
				if (t < 0) {
					t += length;
				}
			}
			Track[] tr = tracks[bone];
			for (int c = 0; c < 3; c++) {
				if (tr[c] != null) {
					tr[c].sample(t, out, at + c * 3);
				} else {
					float rest = c == SCALE ? 1 : 0;
					out[at + c * 3] = rest;
					out[at + c * 3 + 1] = rest;
					out[at + c * 3 + 2] = rest;
				}
			}
		}
	}

	public static BbModel read(Reader reader) {
		return new BbModel(JsonParser.parseReader(reader).getAsJsonObject());
	}

	private BbModel(JsonObject json) {
		JsonArray textures = json.getAsJsonArray("textures");
		uvSize = new float[textures.size()];
		for (int i = 0; i < uvSize.length; i++) {
			JsonObject t = textures.get(i).getAsJsonObject();
			uvSize[i] = t.has("uv_width") ? t.get("uv_width").getAsFloat() : 64;
		}
		Map<String, JsonObject> elements = new HashMap<>();
		for (JsonElement e : json.getAsJsonArray("elements")) {
			elements.put(e.getAsJsonObject().get("uuid").getAsString(), e.getAsJsonObject());
		}
		Map<String, JsonObject> groups = new HashMap<>();
		for (JsonElement g : json.getAsJsonArray("groups")) {
			groups.put(g.getAsJsonObject().get("uuid").getAsString(), g.getAsJsonObject());
		}
		Map<String, Integer> byUuid = new HashMap<>();
		for (JsonElement node : json.getAsJsonArray("outliner")) {
			addBone(node.getAsJsonObject(), -1, groups, elements, byUuid);
		}
		for (JsonElement a : json.getAsJsonArray("animations")) {
			Animation anim = readAnimation(a.getAsJsonObject(), byUuid);
			animations.put(anim.name, anim);
		}
	}

	private void addBone(JsonObject node, int parent, Map<String, JsonObject> groups, Map<String, JsonObject> elements,
		Map<String, Integer> byUuid) {
		String uuid = node.get("uuid").getAsString();
		JsonObject g = groups.get(uuid);
		JsonArray origin = g.getAsJsonArray("origin");
		Bone bone = new Bone(g.get("name").getAsString(), parent, origin.get(0).getAsFloat(), origin.get(1).getAsFloat(),
			origin.get(2).getAsFloat());
		int index = bones.size();
		bones.add(bone);
		boneIndex.put(bone.name, index);
		byUuid.put(uuid, index);
		for (JsonElement child : node.getAsJsonArray("children")) {
			if (child.isJsonPrimitive()) {
				JsonObject e = elements.get(child.getAsString());
				if (e != null) {
					bone.cubes.add(readCube(e));
				}
			} else {
				addBone(child.getAsJsonObject(), index, groups, elements, byUuid);
			}
		}
	}

	private static Cube readCube(JsonObject e) {
		float inf = e.has("inflate") ? e.get("inflate").getAsFloat() : 0;
		JsonArray from = e.getAsJsonArray("from"), to = e.getAsJsonArray("to");
		Cube c = new Cube(from.get(0).getAsFloat() - inf, from.get(1).getAsFloat() - inf, from.get(2).getAsFloat() - inf,
			to.get(0).getAsFloat() + inf, to.get(1).getAsFloat() + inf, to.get(2).getAsFloat() + inf,
			e.has("light_emission") && e.get("light_emission").getAsInt() > 0);
		JsonObject faces = e.getAsJsonObject("faces");
		for (int i = 0; i < 6; i++) {
			JsonObject f = faces.has(FACES[i]) ? faces.getAsJsonObject(FACES[i]) : null;
			if (f == null || !f.has("texture") || f.get("texture").isJsonNull()) {
				continue;
			}
			JsonArray uv = f.getAsJsonArray("uv");
			c.uv[i] = new float[] {uv.get(0).getAsFloat(), uv.get(1).getAsFloat(), uv.get(2).getAsFloat(), uv.get(3).getAsFloat()};
			c.texture[i] = f.get("texture").getAsInt();
		}
		return c;
	}

	private Animation readAnimation(JsonObject a, Map<String, Integer> byUuid) {
		Animation anim = new Animation(a.get("name").getAsString(), a.get("length").getAsFloat(), a.get("loop").getAsString(),
			bones.size());
		JsonObject animators = a.getAsJsonObject("animators");
		for (Map.Entry<String, JsonElement> entry : animators.entrySet()) {
			Integer bone = byUuid.get(entry.getKey());
			if (bone == null) {
				continue;
			}
			Map<Integer, List<JsonObject>> byChannel = new HashMap<>();
			for (JsonElement k : entry.getValue().getAsJsonObject().getAsJsonArray("keyframes")) {
				JsonObject key = k.getAsJsonObject();
				int ch = switch (key.get("channel").getAsString()) {
					case "rotation" -> ROT;
					case "position" -> POS;
					case "scale" -> SCALE;
					default -> -1;
				};
				if (ch >= 0) {
					byChannel.computeIfAbsent(ch, c -> new ArrayList<>()).add(key);
				}
			}
			for (Map.Entry<Integer, List<JsonObject>> c : byChannel.entrySet()) {
				List<JsonObject> keys = c.getValue();
				keys.sort((x, y) -> Float.compare(x.get("time").getAsFloat(), y.get("time").getAsFloat()));
				int n = keys.size();
				float[] times = new float[n];
				float[][] values = new float[n][3];
				byte[] easing = new byte[n];
				for (int i = 0; i < n; i++) {
					JsonObject key = keys.get(i);
					times[i] = key.get("time").getAsFloat();
					JsonObject p = key.getAsJsonArray("data_points").get(0).getAsJsonObject();
					values[i][0] = p.get("x").getAsFloat();
					values[i][1] = p.get("y").getAsFloat();
					values[i][2] = p.get("z").getAsFloat();
					if (c.getKey() == ROT) {
						// Stored for BetterModel (X and Y turned the other way): back to Blockbench's angles
						values[i][0] = -values[i][0];
						values[i][1] = -values[i][1];
					}
					String ease = key.has("interpolation") ? key.get("interpolation").getAsString() : "linear";
					easing[i] = (byte) (ease.equals("catmullrom") ? 1 : ease.equals("step") ? 2 : 0);
				}
				anim.tracks[bone][c.getKey()] = new Track(times, values, easing);
			}
		}
		return anim;
	}
}
