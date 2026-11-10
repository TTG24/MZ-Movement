package com.mzcod.tacticalmovement.client.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;

// Live pose editor / accurate-debugging tool for the tactical prone pose.
//
// WHY THIS EXISTS (the actual animation bug):
//   In the 26.3 player model, head, body, both arms and both legs are ALL direct
//   children of the model ROOT (only "hat" is a child of "head") -- see the
//   HumanoidModel constructor. So rotating the body does NOT rotate the limbs.
//   The old TacticalAnimations assumed limbs were children of the body, which is
//   why the prone pose rendered as a flat body with the limbs flailing straight up.
//
// This tool lets you sculpt each of the 6 parts' x/y/z + xRot/yRot/zRot LIVE (in
// the root frame), watch it render, and read the exact numbers back so you can
// bake them into TacticalAnimations instead of guessing coordinates.
//
// Commands (client):
//   /tacticaledbg on             enable the editor (player shows the edited pose)
//   /tacticaledbg off            disable it (back to the production animation)
//   /tacticaledbg prone          apply the tuned prone baseline (centered, head follows camera)
//   /tacticaledbg copy           snap the editor pose to the current VANILLA pose
//   /tacticaledbg reset          zero every part (start from scratch)
//   /tacticaledbg dump           print all 6 parts' channels to chat
//   /tacticaledbg headcam on     head rotation follows the live camera (default: on)
//   /tacticaledbg headcam off    head rotation uses the sculpted pose instead
//   /tacticaledbg set <part> x y z xRot yRot zRot
//   Parts: head body rArm lArm rLeg lLeg
//
// HEAD CAMERA-TRACKING: with headFollowsCamera on (the default), the head's ROTATION is
// taken from the live vanilla pose every frame (the player's yaw/pitch), so it billboards
// toward the camera with full freedom, while its POSITION (x/y/z) still comes from the
// sculpted pose. This is the "head always follows the camera" behavior you want in-game.
//
// NOTE: armor layers use separate model instances and won't follow this edit; for
// the cleanest read, unequip armor while sculpting.
public final class TacticalPoseDebug {
	public static final String[] PARTS = { "head", "body", "rArm", "lArm", "rLeg", "lLeg" };
	public static final int PART_COUNT = PARTS.length;
	// channel order: 0=x 1=y 2=z 3=xRot 4=yRot 5=zRot
	public static final String[] CHANNELS = { "x", "y", "z", "xRot", "yRot", "zRot" };
	public static final int CHANNEL_COUNT = CHANNELS.length;

	public static boolean enabled = false;
	
	// When true (default), the head's rotation follows the live camera (the vanilla yaw/pitch
	// captured every frame) instead of the sculpted pose -- the head billboards toward the
	// camera with full freedom while the rest of the body stays in the prone layout. The
	// head POSITION (x/y/z) still comes from the pose.
	public static boolean headFollowsCamera = true;

	// Live-tunable factor for the prone head's camera-PITCH (up/down) tracking. In the prone
	// pose the body is locked flat, so the vanilla head-relative pitch can't carry the player's
	// look-up/down -- the mixin adds cam.xRot() * this factor to the head's pitch each frame.
	// 1 = full tracking, 0 = off, -1 = inverted. Tune live with /tacticaledbg headpitch <f>.
	public static float headPitchFactor = 1.0F;

	// Live-tunable RESTING PITCH OFFSET (degrees) for the prone head. The vanilla billboard
	// rotation already points the prone head upward, so this shifts the head's base pitch
	// independently of the tracking factor above. Positive lowers the head; negative raises it.
	// 35 degrees was dialed in in-game as the good resting height (tune with /tacticaledbg headoffset).
	public static float headPitchOffset = 35.0F;

	// Prone SWORD SWING: outward->inward arc amplitude (radians) for the sword arm, driven by the
	// attack swingAnimation (0 -> 1 over the swing). The arm sweeps from out to the side (swing=0)
	// across the body (swing=1) via its zRot (the same channel the prone pose uses for outward
	// splay). Total sweep is 2*amp. 0.5 was dialed in in-game as the good arc (tune: swordamp <rad>).
	public static float proneSwordSwingAmp = 0.5F;

	// Prone SWORD SWING: mid-swing lift (radians) added to the sword arm's xRot so the swing reads
	// as an arc rather than a flat slide; sin(PI*swing) peaks mid-swing. 0 = pure flat sweep.
	// 1.0 was dialed in in-game as the good lift (tune: swordlift <rad>; negative inverts it).
	public static float proneSwordSwingLift = 1.0F;

	// Standing SWORD SWING: outward->inward arc amplitude (radians) for the sword arm, driven by
	// the attack swingAnimation (0 -> 1 over the swing). The arm sweeps from out to the side
	// (swing=0) across the body (swing=1) via its zRot. Total sweep is 2*amp.
	// 0.5 is a good default for a visible horizontal slash (tune: standingswordamp <rad>).
	public static float standingSwordSwingAmp = 0.5F;

	// Standing SWORD SWING: mid-swing forward thrust (radians) added to the sword arm's xRot so
	// the swing reads as a cutting arc rather than a flat side-to-side slide; sin(PI*swing) peaks
	// mid-swing. 0 = pure flat sweep. 0.2 is a good default (tune: standingswordthrust <rad>).
	public static float standingSwordSwingThrust = 0.2F;

	// FIRST-PERSON sword. fpArmShift moves the sword arm toward the screen center (view-space units,
	// 0 = old position). fpSword* place the sword relative to the arm's rest pose (view space, x mirrored
	// for left-handers): position, yaw/pitch/roll (deg) of the blade direction, and slide (blocks) along the
	// blade so the handle sits at the fist. Tune live: /tacticaledbg fparm <shift>, /tacticaledbg fpsword ...
	public static float fpArmX = -0.1F;
	public static float fpArmY = -0.3F;
	public static float fpArmZ = -0.5F;
	public static float fpArmXR = -25.0F;
	public static float fpArmYR = 0.0F;
	public static float fpArmZR = 15.0F;
	// FIRST-PERSON horizontal slash. All scaled by u (+1 at swing start -> -1 at end, faded at the ends):
	// slashX = sideways sweep of the arm (view units), slashYaw = arm yaw sweep (deg), slashRoll = blade
	// tilt sweep (deg), slashForward = forward push at mid-swing (view units). Tune: /tacticaledbg fpslash
	// slashLift = how far the arm/sword rise toward screen-center height during the swing (view units).
	public static float fpSlashLift = 0.1F;
	// slashYaw is now the arc (degrees) of the sweep around the camera's vertical axis: the arm and sword
	// rotate together from outside (u=+1) across the center (u=-1). slashX is an extra sideways slide.
	public static float fpSlashX = 0.0F;
	public static float fpSlashYaw = 60.0F;
	public static float fpSlashRoll = 0.0F;
	public static float fpSlashForward = -0.3F;

	// FIRST-PERSON walking sway (replaces vanilla hand bob while holding a sword): a small "held ready"
	// sway of the arm + sword that scales with walking speed. swayAmp 0 = no sway, 1 = default size;
	// swayRate = radians per second at full walking speed (higher = faster steps).
	// Tune: /tacticaledbg fpsway <amp> <rate>
	public static float fpSwayAmp = 2.0F;
	public static float fpSwayRate = 12.0F;
	// FIRST-PERSON sword walking/crawling animation (sword only, on top of the generic bob): walkRoll =
	// arm roll per step (deg), walkReach = forward/back reach per step (view units), crawlMult = multiplier
	// while prone/low-profile. Tune: /tacticaledbg fpwalk <roll> <reach> <crawlMult>
	public static float fpWalkRoll = 10.0F;
	public static float fpWalkReach = 0.05F;
	public static float fpCrawlMult = 1.8F;
	// Master switch for the custom walking bob (hand sway + camera bob) for EVERY item/hand. When off, vanilla
	// bobbing is used. Toggle: /tacticaledbg custombob on|off
	public static boolean customBob = true;
	// Custom camera (view) bob, same clock as the sway. 0 = no camera bob.
	public static float fpCameraBobAmp = 1.0F;

	// Sword offsets are relative to vanilla's own first-person item position (all 0 = vanilla, always visible).
	// Now relative to the 3p grip frame (the sword is attached to the arm's hand): all 0 = 3p placement.
	public static float fpSwordX = 0.15F;
	public static float fpSwordY = -0.2F;
	public static float fpSwordZ = 0.15F;
	public static float fpSwordYaw = 0.0F;
	public static float fpSwordPitch = 10.0F;
	public static float fpSwordRoll = -180.0F;
	public static float fpSwordSlide = 0.2F;

	// Standing SWORD TWIST: roll (radians) of the sword arm about its long axis (yRot), applied
	// with a ramp-in/hold/ramp-out envelope over the swing. Sign flips the roll direction.
	// (tune: swordtwist <rad>)
	public static float standingSwordTwist = -1.2F;

	// Standing SWORD SWING speed multiplier (debug slow-motion). 1 = normal vanilla speed,
	// 0.2 = five times slower. (tune: swordspeed <factor>)
	public static float swordSwingSpeed = 0.5F;

	// Standing SWORD ARM extra rotation (radians) added to the sword arm's xRot/yRot/zRot at full
	// swing envelope (same envelope as the twist). All-zero = none. (tune: swordarm <x> <y> <z>)
	public static float standingSwordArmX = -1.0F;
	public static float standingSwordArmY = 1.0F;
	public static float standingSwordArmZ = 2.0F;

	// SWORD HOLD (third-person): live-tunable offset for the item held in the player's hand. The
	// sword is drawn relative to the hand's pose stack AFTER the reverse-grip flip, so these move
	// it in the sword's local frame (x/y/z in model units; xRot/yRot/zRot in degrees). 0 0 0 0 0 0
	// = vanilla placement. Tune live with /tacticaledbg swordhold x y z xRot yRot zRot so the
	// handle can be nudged onto the arm without recompiling. y=0.2 was dialed in in-game so the
	// grip sits on the arm (read back with /tacticaledbg swordhold).
	public static float swordHoldX = 0.0F;
	public static float swordHoldY = 0.2F;
	public static float swordHoldZ = 0.0F;
	public static float swordHoldXR = 0.0F;
	public static float swordHoldYR = 0.0F;
	public static float swordHoldZR = 0.0F;

	// Prone transition SPEED: per-tick exponential ease rate for the proneBlend (0..1). Higher
	// = faster drop-in. ~0.3 gives a snappy ~0.5s transition (the old 0.14 was ~1.5s).
	// Tune the drop-in live with /tacticaledbg pronespeed <rate>.
	public static float proneBlendRate = 0.30F;
	// GET-UP SPEED: separate per-tick ease rate for rising out of prone (blend 1->0), so the
	// get-up can run at a different speed than the drop-in. ~0.24 (0.8x the drop) is the
	// default "tactical" feel. Tune live with /tacticaledbg getupspeed <rate>.
	public static float proneGetUpRate = 0.24F;

	// Prone transition CROUCH: the standing->prone blend is a straight line, which reads as a
	// rigid top-over topple. This bends the knees (and dips the body) mid-transition via
	// sin(PI*blend), so the player visibly crouches first (peak at blend=0.5) before dropping
	// into the prone pose. 0 = off (straight line), 1 = full crouch. Tune live with
	// /tacticaledbg crouch <amount>.
	public static float crouchAmount = 0.5F;
	// Knee bend (radians) applied at peak crouch, split between both legs (each leg gets this).
	public static float crouchKneeBend = 0.8F;
	// Body dip (model units) applied at peak crouch (lowers the hips for a deeper crouch).
	public static float crouchBodyDip = 1.2F;

	// Transition PHASE passed to apply() so it plays the RIGHT animation per direction:
	//   PHASE_DROP  = entering prone (stance PRONE):            plays the crouch.
	//   PHASE_GETUP = exiting prone (stance STANDING, blend>0): plays the pull-up get-up.
	//   PHASE_NONE  = editor / standing:                        no transition animation.
	public static final int PHASE_NONE = 0;
	public static final int PHASE_DROP = 1;
	public static final int PHASE_GETUP = 2;

	// GET-UP animation ("pull up with arms, stand on one leg"), played on top of the
	// prone->standing lerp while EXITING prone (blend eases 1->0). Keyed on up = 1-blend so it
	// peaks mid-rise and vanishes at both ends (fully prone / fully standing). 0 = off, 1 = full.
	// Tuned live with /tacticaledbg getup <amount>. The individual channels (getUpArmPush /
	// getUpLegLift) are signed: flip the sign live if a channel bends the wrong way.
	public static float getUpAmount = 1.0F;
	// Arms "bring in" (radians): at the START of the get-up the arms are still splayed out to
	// the sides (the swim pose look), so we fold them IN from the sides toward under the body.
	// This is what stops it reading as "swimming" and makes it read as a push-up. Holds strong
	// through the rise (fades only at the very end as the player settles standing).
	public static float getUpArmIn = 2.0F;
	// Arms "push up" (radians) added to both arms' xRot: the arms raise as the player pushes up.
	// Negative by default = the arms press DOWN/inward as the rise starts (tuned in-game).
	public static float getUpArmPush = -1.0F;
	// Standing-leg "lift" (radians) subtracted from the right leg's xRot: it bends and comes up
	// (the player stands up on that leg) while the other follows the base lerp.
	public static float getUpLegLift = 2.0F;
	// Body "rise" (model units) added to the body's y: extra hip lift that sells the "pull up".
	public static float getUpBodyRise = 1.0F;

	// The pose actually applied while enabled (6 parts x 6 channels).
	public static final float[][] pose = new float[PART_COUNT][CHANNEL_COUNT];
	// Last-seen VANILLA part transforms, captured every frame, for copy/reference.
	public static final float[][] vanilla = new float[PART_COUNT][CHANNEL_COUNT];
	// Subtle idle animation applied ON TOP of the sculpted base pose (see apply()). Each
	// part oscillates one rotation channel -- axis 0=xRot, 1=yRot, 2=zRot -- by a sine of
	// amplitude animAmp[i] at animSpeed rad/sec, offset by animPhase[i]. Tune live with
	//   /tacticaledbg animset <part> <axis 0|1|2> <amplitude> <phase>
	//   /tacticaledbg animspeed <rad/sec>
	//   /tacticaledbg anim on|off   (off = pure sculpted static pose for clean sculpting)
	public static boolean animEnabled = true;
	public static float animSpeed = 1.5F;
	public static final float[] animAmp = { 0.0F, 0.0F, 0.2F, 0.2F, 0.0F, 0.0F };
	public static final float[] animPhase = { 0.0F, 0.0F, 0.0F, (float) Math.PI, 0.0F, 0.0F };
	// Crawl (movement) animation, applied while the player moves horizontally. It blends with
	// the idle animation via the live movement weight (0 = still -> idle sway, 1 = crawling ->
	// limb stroke). Each part oscillates one rotation channel (axis 0=xRot,1=yRot,2=zRot) by a
	// sine of amplitude moveAmp[i] at moveSpeed rad/sec, offset by movePhase[i]. Tune live:
	//   /tacticaledbg crawl preset|none  |  crawlspeed <rad/sec>  |  crawldump
	//   /tacticaledbg crawlset <part> <axis 0|1|2> <amplitude> <phase>
	public static final int[] moveAxis = { 0, 0, 0, 1, 1, 1 };
	public static final float[] moveAmp = { 0.0F, 0.0F, 0.35F, 0.50F, 0.20F, 0.20F };
	public static final float[] movePhase = { 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F };
	public static float moveSpeed = 2.0F;
	public static final int[] animAxis = { 0, 0, 0, 0, 0, 0 };
	// PARTS order is: head, body, rArm, lArm, rLeg, lLeg -- so the head is index 0.
	private static final int HEAD = 0;

	private TacticalPoseDebug() {
	}

	// Bake the tuned prone baseline as the editor's default pose at class load, so holding
	// C (prone) shows the sculpted layout out of the box -- even on a fresh start, before
	// any /tacticaledbg command is run. prone() also (re)applies the baked idle/crawl
	// defaults (already set as field initializers) and enables the live head-camera-follow.
	// The user can still override any of this with the editor or the commands.
	static {
		prone();
	}

	public static void reset() {
		for (float[] part : pose) {
			for (int c = 0; c < CHANNEL_COUNT; c++) {
				part[c] = 0.0F;
			}
		}
	}

	public static void copyVanilla() {
		for (int i = 0; i < PART_COUNT; i++) {
			System.arraycopy(vanilla[i], 0, pose[i], 0, CHANNEL_COUNT);
		}
	}
	
	// Tuned prone baseline (all values in the ROOT frame -- the 26.3 parts are direct children
	// of root). Body flat along front-back and centered on the entity origin so the torso sits
	// over the ground shadow; head forward, legs trailing behind, arms at the sides. The head
	// rotation is live (follows the camera) via headFollowsCamera. Fine-tune the two arms'
	// yRot/zRot with /tacticaledbg set until the held sword points outward instead of up.
	public static void prone() {
		// Tuned prone baseline, shifted back so the torso sits best over the ground shadow
		// (body/head at z=-12 instead of the old z=0; arms -10, legs 0 -- the whole layout
		// moved by -12). Head rotation is live (follows the camera) via headFollowsCamera;
		// the rest is the sculpted static pose.
		setPart("head", 0.0F, 24.0F, -12.0F, 1.5708F, 0.0F, 0.0F);
		setPart("body", 0.0F, 24.0F, -12.0F, 1.5708F, 0.0F, 0.0F);
		setPart("rArm", -5.0F, 24.0F, -10.0F, 2.0F, -3.2F, -1.7F);
		setPart("lArm", 5.0F, 24.0F, -10.0F, 1.5708F, 3.3F, 0.0F);
		setPart("rLeg", -1.9F, 24.0F, 0.0F, 1.5708F, -0.3F, 0.0F);
		setPart("lLeg", 1.9F, 24.0F, 0.0F, 1.5708F, 0.0F, 0.0F);
		headFollowsCamera = true;
		animIdle();
	}
	
	// Subtle tactical idle: arms and legs gently rock (xRot), left and right alternating,
	// so the prone pose feels alive without a big crawl. The head stays live (camera-follow)
	// and the body stays fixed. This is the default animation on top of the sculpted pose.
	public static void animIdle() {
		animEnabled = true;
		animSpeed = 1.5F;
		animAmp[0] = 0.0F; animAxis[0] = 0; animPhase[0] = 0.0F; // head: none (camera)
		animAmp[1] = 0.0F; animAxis[1] = 0; animPhase[1] = 0.0F; // body: none
		animAmp[2] = 0.2F; animAxis[2] = 0; animPhase[2] = 0.0F; // rArm: gentle rock
		animAmp[3] = 0.2F; animAxis[3] = 0; animPhase[3] = (float) Math.PI; // lArm: opposite
		animAmp[4] = 0.0F; animAxis[4] = 0; animPhase[4] = 0.0F; // rLeg: none
		animAmp[5] = 0.0F; animAxis[5] = 0; animPhase[5] = 0.0F; // lLeg: none
	}
	
	// Turn off the idle animation (pure sculpted static pose, for clean sculpting).
	public static void animNone() {
		animEnabled = false;
		for (int i = 0; i < PART_COUNT; i++) {
			animAmp[i] = 0.0F;
		}
	}
	
	private static void setPart(String name, float x, float y, float z, float xr, float yr, float zr) {
		int i = findPart(name);
		if (i < 0) {
			return;
		}
		pose[i][0] = x;
		pose[i][1] = y;
		pose[i][2] = z;
		pose[i][3] = xr;
		pose[i][4] = yr;
		pose[i][5] = zr;
	}

	public static int findPart(String name) {
		for (int i = 0; i < PART_COUNT; i++) {
			if (PARTS[i].equalsIgnoreCase(name)) {
				return i;
			}
		}
		return -1;
	}

	// Capture the vanilla (pre-debug) transforms of the 6 parts, called every frame.
	public static void captureVanilla(PlayerModel model) {
		ModelPart[] parts = parts(model);
		for (int i = 0; i < PART_COUNT; i++) {
			ModelPart p = parts[i];
			vanilla[i][0] = p.x;
			vanilla[i][1] = p.y;
			vanilla[i][2] = p.z;
			vanilla[i][3] = p.xRot;
			vanilla[i][4] = p.yRot;
			vanilla[i][5] = p.zRot;
		}
	}

	// Capture ONLY the head's rotation (live), leaving its position and the other parts'
	// transforms untouched. Used while actually prone so the head still free-roams (yaw) toward
	// the camera even though the body's blend start is pinned at the last standing pose.
	public static void captureHeadRotation(PlayerModel model) {
		ModelPart p = parts(model)[HEAD];
		vanilla[HEAD][3] = p.xRot;
		vanilla[HEAD][4] = p.yRot;
		vanilla[HEAD][5] = p.zRot;
	}

	// Capture the vanilla pose for the get-up transition: live positions for all parts,
	// live head rotation (camera tracking), live body rotation (close to 0 for standing),
	// but NEUTRAL (0.0) arm/leg rotations to remove the swimming stroke look. The vanilla
	// animation during the get-up is the swimming animation (swimAmount is restored before
	// setupAnim runs), so capturing raw rotations would give arms-up/legs-kicking poses.
	// Zeroing them gives a natural "limbs hanging" standing target for the lerp.
	public static void captureVanillaForTransition(PlayerModel model) {
		ModelPart[] ps = parts(model);
		for (int i = 0; i < PART_COUNT; i++) {
			ModelPart p = ps[i];
			vanilla[i][0] = p.x;
			vanilla[i][1] = p.y;
			vanilla[i][2] = p.z;
		}
		// Head: live rotation (camera tracking)
		ModelPart head = ps[HEAD];
		vanilla[HEAD][3] = head.xRot;
		vanilla[HEAD][4] = head.yRot;
		vanilla[HEAD][5] = head.zRot;
		// Body: live rotation (close to 0.0 for a standing entity)
		ModelPart body = ps[1];
		vanilla[1][3] = body.xRot;
		vanilla[1][4] = body.yRot;
		vanilla[1][5] = body.zRot;
		// Arms/legs: neutral standing (0.0) to remove the swimming stroke
		for (int i = 2; i < PART_COUNT; i++) {
			vanilla[i][3] = 0.0F;
			vanilla[i][4] = 0.0F;
			vanilla[i][5] = 0.0F;
		}
	}

	// Apply the edited pose (sculpted base + optional idle animation), called every frame
	// while enabled. The head billboards toward the camera when headFollowsCamera is on.
	// Apply the edited pose. Convenience overload with no movement weight (idle-only).
	public static void apply(PlayerModel model) {
		apply(model, 0.0F, false, 1.0F, PHASE_NONE);
	}

	// 4-arg convenience: no transition phase (editor / standing). Production passes an explicit
	// phase (DROP or GETUP) so the right animation plays per direction.
	public static void apply(PlayerModel model, float move, boolean forceHeadFollow, float blend) {
		apply(model, move, forceHeadFollow, blend, PHASE_NONE);
	}

	// Apply the edited pose with the idle animation cross-fading into the crawl (movement)
	// animation as `move` ramps from 0 (still -> idle sway) to 1 (crawling -> limb stroke).
	// `move` is the live horizontal-movement weight (0..1), normalized upstream (proneMove).
	// The head billboards toward the camera when headFollowsCamera is on, or when the caller
	// forces it via forceHeadFollow (the production C-key path always forces it on, so the
	// editor's headFollowsCamera toggle -- which /tacticaledbg on flips off -- can never
	// disable the in-game head camera-follow).
	// `phase` selects the transition animation: PHASE_DROP plays the crouch, PHASE_GETUP plays
	// the pull-up get-up, PHASE_NONE plays neither (editor / standing).
	public static void apply(PlayerModel model, float move, boolean forceHeadFollow, float blend, int phase) {
		float m = move < 0.0F ? 0.0F : (move > 1.0F ? 1.0F : move);
		// 0 = vanilla standing pose, 1 = full sculpted prone pose; in-between values lerp every
		// part's position/rotation between them so entering/exiting prone animates smoothly. The
		// eased proneBlend (TacticalMovementClient.tickStanceVisual) drives this in production.
		float b = blend < 0.0F ? 0.0F : (blend > 1.0F ? 1.0F : blend);
		ModelPart[] parts = parts(model);
		float time = animationTime();
		for (int i = 0; i < PART_COUNT; i++) {
			ModelPart p = parts[i];
			// Position: lerp vanilla (standing) -> sculpted (prone).
			p.x = vanilla[i][0] + (pose[i][0] - vanilla[i][0]) * b;
			p.y = vanilla[i][1] + (pose[i][1] - vanilla[i][1]) * b;
			p.z = vanilla[i][2] + (pose[i][2] - vanilla[i][2]) * b;
			if (i == HEAD && (headFollowsCamera || forceHeadFollow)) {
				// Head billboards toward the camera: the live vanilla rotation in BOTH standing and
				// prone (so it tracks the camera smoothly through the transition); position lerps.
				p.xRot = vanilla[i][3];
				p.yRot = vanilla[i][4];
				p.zRot = vanilla[i][5];
			} else {
				// r[0]=xRot, r[1]=yRot, r[2]=zRot. Idle fades out and crawl fades in as `m`
				// grows; each contributes on its own axis. Then lerp vanilla -> sculpted by b,
				// which also fades the idle/crawl oscillations in only as the player actually
				// prones (they vanish at b=0, i.e. standing).
				float[] r = R_SCRATCH;
				r[0] = pose[i][3];
				r[1] = pose[i][4];
				r[2] = pose[i][5];
				if (animEnabled) {
					float idleAmp = animAmp[i] * (1.0F - m);
					if (idleAmp != 0.0F) {
						addOsc(r, animAxis[i], (float) Math.sin(time * animSpeed + animPhase[i]) * idleAmp);
					}
				}
				if (moveAmp[i] != 0.0F) {
					float crawlAmp = moveAmp[i] * m;
					if (crawlAmp != 0.0F) {
						addOsc(r, moveAxis[i], (float) Math.sin(time * moveSpeed + movePhase[i]) * crawlAmp);
					}
				}
				p.xRot = vanilla[i][3] + (r[0] - vanilla[i][3]) * b;
				p.yRot = vanilla[i][4] + (r[1] - vanilla[i][4]) * b;
				p.zRot = vanilla[i][5] + (r[2] - vanilla[i][5]) * b;
			}
		}

		// --- DROP (entering prone): "crouch first, then drop." In the middle of the transition
		// (blend=0.5) the knees bend and the body dips, so the player visibly crouches before
		// settling into the prone pose. Vanishes at blend=0 (standing) and blend=1 (fully prone).
		// NOTE: the prone legs already sit at ~90deg xRot (flat), so we SUBTRACT to bend the
		// knees forward (a real crouch) rather than adding, which would fold them backward.
		if (phase == PHASE_DROP && crouchAmount != 0.0F && b > 0.001F && b < 0.999F) {
			float crouch = (float) Math.sin(b * Math.PI) * crouchAmount;
			parts[4].xRot -= crouch * crouchKneeBend;  // rLeg: bend the knee forward
			parts[5].xRot -= crouch * crouchKneeBend;  // lLeg: bend the knee forward
			parts[1].y -= crouch * crouchBodyDip;      // body: dip (lower the hips)
		}

		// --- GET-UP (exiting prone): "pull up with arms, stand on one leg." Keyed on up = 1-b
		// (0 at prone, 1 at standing) so it peaks mid-rise and vanishes at both ends.
		//   1) "bring in": fold the arms IN from the sides (they start splayed like the swim pose)
		//      toward under the body -- peaks at the start, fades to 0. This is what stops it
		//      reading as "swimming" and makes it read as a push-up.
		//   2) "push up": raise the arms as the player pushes up -- peaks mid-rise.
		//   3) standing leg (right) bends up (stand on one leg) + body lifts.
		if (phase == PHASE_GETUP && getUpAmount != 0.0F && b > 0.001F && b < 0.999F) {
			float up = 1.0F - b;  // 0 at prone, 1 at standing
			// Arms: fold in from the sides (peaks at start) then push up (peaks mid).
			float bringIn = (float) Math.cos(up * Math.PI * 0.5F) * getUpArmIn * getUpAmount;
			float pushUp = (float) Math.sin(up * Math.PI) * getUpArmPush * getUpAmount;
			parts[2].yRot += bringIn;   // rArm: fold in (its yRot is negative, so + reduces the splay)
			parts[3].yRot -= bringIn;   // lArm: fold in (its yRot is positive, so - reduces the splay)
			parts[2].xRot += pushUp;    // rArm: raise (push up)
			parts[3].xRot += pushUp;    // lArm: raise (push up)
			// Standing leg (right) bends up (stand on one leg) + body lifts (extra hip rise).
			float legLift = (float) Math.sin(up * Math.PI) * getUpLegLift * getUpAmount;
			parts[4].xRot -= legLift;  // rLeg (standing leg): bend and come up
			parts[1].y += (float) Math.sin(up * Math.PI) * getUpBodyRise * getUpAmount;  // body: extra hip lift
		}
	}
	
	// Smooth animation clock in seconds, from the local player's age. Using the age (not a
	// wall clock) keeps it frame-stable and in sync with the game; the sine is periodic so
	// the absolute phase doesn't matter.
	private static float animationTime() {
		// Wall clock, not level game time: arena/instance worlds on a server can freeze or step the level clock,
		// which stalled the prone idle/crawl loops. Seconds since start, wrapped to keep float precision.
		return (float) ((System.nanoTime() / 1.0E9) % 100000.0);
	}

	// Add a sine offset to one rotation channel (axis 0=xRot, 1=yRot, else zRot). Mirrors the
	// original OOB-safe axis handling (any non-0/1 axis falls through to zRot).
	private static void addOsc(float[] r, int axis, float osc) {
		if (axis == 0) {
			r[0] += osc;
		} else if (axis == 1) {
			r[1] += osc;
		} else {
			r[2] += osc;
		}
	}

	// Reused every frame (render thread only) to avoid a per-call allocation.
	private static final ModelPart[] PARTS_SCRATCH = new ModelPart[PART_COUNT];
	private static final float[] R_SCRATCH = new float[3];

	private static ModelPart[] parts(PlayerModel model) {
		ModelPart[] a = PARTS_SCRATCH;
		a[0] = model.head;
		a[1] = model.body;
		a[2] = model.rightArm;
		a[3] = model.leftArm;
		a[4] = model.rightLeg;
		a[5] = model.leftLeg;
		return a;
	}

	public static String describe(float[] ch) {
		return String.format("x=%.2f y=%.2f z=%.2f | xRot=%.3f yRot=%.3f zRot=%.3f",
				ch[0], ch[1], ch[2], ch[3], ch[4], ch[5]);
	}

	public static String dump() {
		StringBuilder sb = new StringBuilder();
		if (headFollowsCamera) {
			sb.append("[head rotation follows the camera]\n");
		}
		for (int i = 0; i < PART_COUNT; i++) {
			if (i > 0) {
				sb.append("\n");
			}
			sb.append(PARTS[i]).append("  ").append(describe(pose[i]));
			if (i == HEAD && headFollowsCamera) {
				sb.append("  (rot: live camera)");
			}
		}
		sb.append("\n\n").append(dumpAnim());
		return sb.toString();
	}
	
	public static String dumpAnim() {
		StringBuilder sb = new StringBuilder();
		sb.append("animation: ").append(animEnabled ? "ON" : "OFF");
		sb.append("  speed=").append(String.format(java.util.Locale.ROOT, "%.2f", animSpeed));
		for (int i = 0; i < PART_COUNT; i++) {
			if (animAmp[i] != 0.0F) {
				String axis = animAxis[i] == 0 ? "xRot" : (animAxis[i] == 1 ? "yRot" : "zRot");
				sb.append("\n  ").append(PARTS[i]).append("  ").append(axis);
				sb.append(" amp=").append(String.format(java.util.Locale.ROOT, "%.2f", animAmp[i]));
				sb.append(" phase=").append(String.format(java.util.Locale.ROOT, "%.2f", animPhase[i]));
			}
		}
		return sb.toString() + dumpCrawl();
	}

	// Crawl (movement) animation preset, baked as the default: rArm rocks on xRot while
	// the left arm and both legs swing on yRot, out of phase for a reaching gait.
	// /tacticaledbg crawl preset.
	public static void movePreset() {
		moveAmp[0] = 0.0F; moveAxis[0] = 0; movePhase[0] = 0.0F;                  // head
		moveAmp[1] = 0.0F; moveAxis[1] = 0; movePhase[1] = 0.0F;                  // body
		moveAmp[2] = 0.35F; moveAxis[2] = 0; movePhase[2] = 0.0F;                 // rArm: xRot
		moveAmp[3] = 0.50F; moveAxis[3] = 1; movePhase[3] = 0.0F;                 // lArm: yRot
		moveAmp[4] = 0.20F; moveAxis[4] = 1; movePhase[4] = 1.0F;                 // rLeg: yRot
		moveAmp[5] = 0.20F; moveAxis[5] = 1; movePhase[5] = 0.0F;                 // lLeg: yRot
		moveSpeed = 2.0F;
	}

	// Turn off the crawl animation (no extra motion while moving). /tacticaledbg crawl none.
	public static void moveNone() {
		for (int i = 0; i < PART_COUNT; i++) {
			moveAmp[i] = 0.0F;
		}
	}

	public static String dumpCrawl() {
		StringBuilder sb = new StringBuilder();
		boolean any = false;
		for (int i = 0; i < PART_COUNT; i++) {
			if (moveAmp[i] != 0.0F) {
				if (!any) {
					sb.append("\ncrawl (movement) speed=").append(String.format(java.util.Locale.ROOT, "%.2f", moveSpeed));
					any = true;
				}
				String axis = moveAxis[i] == 0 ? "xRot" : (moveAxis[i] == 1 ? "yRot" : "zRot");
				sb.append("\n  ").append(PARTS[i]).append("  ").append(axis);
				sb.append(" amp=").append(String.format(java.util.Locale.ROOT, "%.2f", moveAmp[i]));
				sb.append(" phase=").append(String.format(java.util.Locale.ROOT, "%.2f", movePhase[i]));
			}
		}
		if (!any) {
			sb.append("\ncrawl (movement): OFF");
		}
		return sb.toString();
	}
}