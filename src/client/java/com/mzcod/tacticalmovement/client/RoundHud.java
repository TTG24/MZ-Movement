package com.mzcod.tacticalmovement.client;

import com.mzcod.tacticalmovement.TacticalMovement;
import com.mzcod.tacticalmovement.network.RoundPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

import java.util.Random;

// The zombies round, bottom-left, as red tally marks (four strokes and a slash across them for every five).
//  * A round ending (the server sends minus the round): the tally blinks on and off for a few seconds, then goes away.
//  * The next round starting: it comes back with the new mark, white at first, settling back to red.
//  * Past round 10 it is a number instead of marks, in the same place and colours.
//  * Round 1 (the game starting): the first mark appears big in the middle of the screen, then moves down into the corner.
public final class RoundHud {
	private static final int MARK_H = 18, MARK_W = 2, GAP = 5, GROUP_GAP = 9, GROUPS_PER_ROW = 6, ROW_GAP = 7;
	private static final int RED = 0xA80F0F, DARK = 0x3A0000, WHITE = 0xF2E6E6;
	/** Round 1 intro: how long it stays big in the middle, and how long the move to the corner takes (seconds). */
	private static final float INTRO_HOLD = 2.5F, INTRO_MOVE = 1.2F, INTRO_SCALE = 4.0F;
	/** Above this round the tally is drawn as a number; the number is this many times the font size. */
	private static final int TALLY_MAX = 10, NUM_SCALE = 2;
	private static final float BLINK_TIME = 3.0F, RETURN_FADE = 0.5F, WHITE_TIME = 2.5F;

	private static int round;
	private static int previous;
	private static long changedAt;
	private static boolean intro;
	/** The round on screen has ended: blinking, then hidden until the next one starts. */
	private static boolean ended;
	/** Small fixed wobble per mark, so they look hand-drawn but do not jitter. */
	private static final float[] WOBBLE = new float[512];

	static {
		Random r = new Random(1234);
		for (int i = 0; i < WOBBLE.length; i++) {
			WOBBLE[i] = r.nextFloat();
		}
	}

	private RoundHud() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(RoundPayload.TYPE, (payload, context) -> {
			int r = payload.round();
			if (r < 0) {
				// Round -r is over: blink it, then hide (straight to hidden if it was not on screen, e.g. joining now)
				if (!ended) {
					boolean shown = round == -r;
					ended = true;
					round = -r;
					previous = round;
					intro = false;
					changedAt = shown ? System.nanoTime() : 0;
				}
			} else if (r != round || ended) {
				// The marks added since the count on screen are new (a lower round, a new game: all of them)
				previous = r >= round ? round : 0;
				round = r;
				ended = false;
				changedAt = System.nanoTime();
				intro = r == 1;
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			round = 0;
			ended = false;
		});
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, TacticalMovement.id("round"), RoundHud::render);
	}

	private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (round <= 0) {
			return;
		}
		float since = (System.nanoTime() - changedAt) / 1.0E9F;
		if (ended && (changedAt == 0 || since >= BLINK_TIME || ((int) (since * 4.0F)) % 2 == 1)) {
			return; // round over: blinking, then gone until the next round
		}
		int groups = (round + 4) / 5;
		int rows = (groups + GROUPS_PER_ROW - 1) / GROUPS_PER_ROW;
		int cols = Math.min(groups, GROUPS_PER_ROW);
		int groupW = 4 * GAP + MARK_W;
		// Width of what is really drawn: a group that is not finished yet is only as wide as its marks so far
		float width = cols * groupW + (cols - 1) * GROUP_GAP;
		if (groups == 1 && round < 5) {
			width = (round - 1) * GAP + MARK_W;
		}
		float height = rows * MARK_H + (rows - 1) * ROW_GAP;
		boolean number = round > TALLY_MAX;
		net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
		String digits = Integer.toString(round);
		if (number) {
			width = font.width(digits) * NUM_SCALE;
			height = 7 * NUM_SCALE;
		}

		// Where it is drawn: the corner, or (round 1 intro) big in the middle sliding down to the corner
		float cornerX = 10, cornerY = g.guiHeight() - 10 - height;
		float scale = 1, ox = cornerX, oy = cornerY;
		boolean introPhase = intro && since < INTRO_HOLD + INTRO_MOVE;
		if (introPhase) {
			float bigX = g.guiWidth() / 2.0F - width * INTRO_SCALE / 2.0F;
			float bigY = g.guiHeight() / 2.0F - height * INTRO_SCALE / 2.0F - 20;
			float move = since <= INTRO_HOLD ? 0 : ease((since - INTRO_HOLD) / INTRO_MOVE);
			scale = Mth.lerp(move, INTRO_SCALE, 1);
			ox = Mth.lerp(move, bigX, cornerX);
			oy = Mth.lerp(move, bigY, cornerY);
		}

		// The new round: the tally fades back in with the new mark white, which turns red (after the intro, if any)
		float age = since - (intro ? INTRO_HOLD + INTRO_MOVE : 0);
		float fresh = ended ? 0 : introPhase ? 1 : Mth.clamp(1 - (age - WHITE_TIME * 0.5F) / (WHITE_TIME * 0.5F), 0, 1);
		int alpha = introPhase ? (since < 0.4F ? Math.round(255 * since / 0.4F) : 255)
			: ended || intro ? 255 : Math.round(255 * Mth.clamp(since / RETURN_FADE, 0, 1));

		if (number) {
			// A new round's number is white at first and turns red, like a new mark
			int rgb = previous < round ? mix(RED, WHITE, fresh) : RED;
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(ox, oy);
			pose.scale(scale * NUM_SCALE, scale * NUM_SCALE);
			g.text(font, digits, 1, 1, ((alpha * 0xB0 / 255) << 24) | DARK, false);
			g.text(font, digits, 0, 0, (alpha << 24) | rgb, false);
			pose.popMatrix();
			return;
		}
		for (int gi = 0; gi < groups; gi++) {
			int row = gi / GROUPS_PER_ROW, col = gi % GROUPS_PER_ROW;
			float top = (row) * (MARK_H + ROW_GAP);
			float gx = col * (groupW + GROUP_GAP);
			int inGroup = Math.min(5, round - gi * 5);
			for (int m = 0; m < Math.min(4, inGroup); m++) {
				int index = gi * 5 + m;
				boolean isNew = index >= previous && index < round;
				float w = WOBBLE[index % WOBBLE.length];
				float x = gx + m * GAP;
				float lean = (w - 0.5F) * 3;
				stroke(g, ox, oy, scale, x, top + w * 2, x + lean, top + MARK_H - WOBBLE[(index + 7) % WOBBLE.length] * 2,
					isNew ? mix(RED, WHITE, fresh) : RED, alpha);
			}
			if (inGroup == 5) {
				int index = gi * 5 + 4;
				boolean isNew = index >= previous && index < round;
				// The fifth: a slash across the four, bottom-left to top-right
				stroke(g, ox, oy, scale, gx - 3, top + MARK_H - 3, gx + groupW + 2, top + 3, isNew ? mix(RED, WHITE, fresh) : RED, alpha);
			}
		}
	}

	private static float ease(float t) {
		t = Mth.clamp(t, 0, 1);
		return t * t * (3 - 2 * t);
	}

	/** A stroke from (x0, y0) to (x1, y1) in tally units, placed at (ox, oy) and scaled, with a dark edge. */
	private static void stroke(GuiGraphicsExtractor g, float ox, float oy, float scale, float x0, float y0, float x1, float y1, int rgb,
		int alpha) {
		float sx0 = ox + x0 * scale, sy0 = oy + y0 * scale, sx1 = ox + x1 * scale, sy1 = oy + y1 * scale;
		int w = Math.max(1, Math.round(MARK_W * scale));
		int h = Math.max(1, Math.round(scale));
		int steps = Math.max(1, Math.round(Math.max(Math.abs(sx1 - sx0), Math.abs(sy1 - sy0)) / Math.max(1, h * 0.75F)));
		int edge = Math.max(1, Math.round(scale * 0.75F));
		for (int pass = 0; pass < 2; pass++) {
			int c = pass == 0 ? ((alpha * 0xB0 / 255) << 24) | DARK : (alpha << 24) | rgb;
			int o = pass == 0 ? edge : 0;
			for (int i = 0; i <= steps; i++) {
				float t = i / (float) steps;
				int x = Math.round(Mth.lerp(t, sx0, sx1)), y = Math.round(Mth.lerp(t, sy0, sy1));
				g.fill(x - o, y - o, x + w + o, y + h + o, c);
			}
		}
	}

	private static int mix(int a, int b, float t) {
		t = Mth.clamp(t, 0, 1);
		int r = Math.round(Mth.lerp(t, (a >> 16) & 0xFF, (b >> 16) & 0xFF));
		int gr = Math.round(Mth.lerp(t, (a >> 8) & 0xFF, (b >> 8) & 0xFF));
		int bl = Math.round(Mth.lerp(t, a & 0xFF, b & 0xFF));
		return (r << 16) | (gr << 8) | bl;
	}
}
