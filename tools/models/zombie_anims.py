"""Zombie body language for mz_zombie.bbmodel: every pose and animation, sampled into keyframes by zombie_body.py.

A pose is {bone: {'rot': (x, y, z) Blockbench degrees, 'pos': (x, y, z) pixels, 'scale': (x, y, z)}}; bones or channels
left out stay at rest. Blockbench angles: X + swings an arm forward/up and tilts the head back, so X - on the torso leans
it forward. The front is north (-z), the zombie's right is +x.

Standing zombies: variant 1 of idle / walk / sprint is the original v1 animation (tools/models/reference), the others
are new styles made from the same pose function with different settings. Crawlers (legs blown off by a grenade) lie on
their front with thigh stumps and pull themselves along with their arms.
"""
import math

D = 180.0 / math.pi
TWO_PI = 2 * math.pi
SPRINT_FROM, SPRINT_TO = 0.55, 0.80  # the mod's ZombieAnim.sprintFrom / sprintTo
STRIDE = TWO_PI / 0.6662  # walkAnimationPos per step cycle
WALK_SPD, SPRINT_SPD = 0.45, 0.90  # walkAnimationSpeed the walk / sprint loops are made at
WALK_LEN = 2 * STRIDE / WALK_SPD / 20  # two steps (the hip twist runs at half the step rate)
SPRINT_LEN = 2 * STRIDE / SPRINT_SPD / 20
IDLE_LEN = 4.5

UPPER = ('torso', 'chest', 'hi_head', 'jaw', 'right_arm', 'right_forearm', 'right_hand', 'left_arm', 'left_forearm',
         'left_hand')
LEGS = ('right_leg', 'right_shin', 'right_foot', 'left_leg', 'left_shin', 'left_foot')


def smooth(x):
    x = min(1.0, max(0.0, x))
    return x * x * (3 - 2 * x)


def lerp(a, b, t):
    return a + (b - a) * t


def bb(vx, vy, vz):
    """The mod's model angles (vanilla ModelPart radians) as Blockbench degrees."""
    return (-vx * D, -vy * D, vz * D)


def add(a, b):
    return tuple(x + y for x, y in zip(a, b))


# --------------------------------------------------------------------------------------------------- standing zombies
# Settings of the standing pose. STYLE_1 is the original (v1) zombie; the variants override some of them.
STYLE_1 = {
    'lean': (0.22, 0.32, 0.62),            # idle, walk, sprint (radians forward)
    'roll': (0.05, 0.13, 0.06),            # side sway: idle, walk, sprint
    'twist': (0.10, 0.12),                 # hip twist: walk, sprint
    'idle_arms': (-0.30, -0.18),           # right, left (vanilla x: - = raised forward)
    'walk_arms': ((-1.40, 0.28), (-1.10, -0.34)),  # (base, swing * speed) right, left
    'sprint_arms': (-0.35, 1.55),          # base, pump amplitude (opposite each other)
    'arm_out': ((0.10, -0.14), (0.05, -0.05), (0.30, -0.30)),  # arm z: idle, walk, sprint (right, left)
    'walk_arm_in': (-0.15, 0.18),
    'legs': ((1.0, 0.62), 1.35),           # walk (right, left: the left drags), sprint
    'knee': ((0.28, 0.12), 0.45),          # knee lift: walk (right, left), sprint
    'head': (0.38, 0.18, -0.30),           # head drop: idle, walk, sprint (+ = looking down)
    'idle_head_roll': 0.16,
    'twitch': 0.25,
    'elbow': (0.1, 0.25),                  # elbow bend: base, + sprint
    'limp_arm': None,                      # 'right' / 'left': that arm hangs and swings loosely
    'head_loll': 0.0,                      # slow head roll side to side (radians)
    'stagger': 0.0,                        # extra side-to-side stagger while walking (radians of roll)
    'jaw': (0.12, 0.0),                    # jaw open (radians), chatter amplitude
    'flail': 0.0,                          # extra arm flailing at another rate (radians)
}


def style(**over):
    s = dict(STYLE_1)
    s.update(over)
    return s


IDLES = {
    'idle_1': STYLE_1,
    # Swaying on its feet, head lolling round, both arms hanging dead
    'idle_2': style(roll=(0.11, 0.13, 0.06), idle_arms=(-0.05, -0.02), arm_out=((0.06, -0.06), (0.05, -0.05), (0.3, -0.3)),
                    head=(0.25, 0.18, -0.30), idle_head_roll=0.0, head_loll=0.32, twitch=0.0, jaw=(0.30, 0.0)),
    # Bent right over, twitching: jerky head snaps, jaw chattering, one arm raised and trembling
    'idle_3': style(lean=(0.48, 0.32, 0.62), idle_arms=(-0.95, -0.10), head=(0.15, 0.18, -0.30), twitch=0.55,
                    jaw=(0.20, 0.10), flail=0.08),
}
WALKS = {
    'walk_1': STYLE_1,
    # One arm reaching, the other hanging limp; the right leg drags instead of the left
    'walk_2': style(walk_arms=((-1.55, 0.20), (-0.10, -0.30)), limp_arm='left', legs=((0.6, 1.0), 1.35),
                    knee=((0.10, 0.30), 0.45), roll=(0.05, 0.17, 0.06), head=(0.38, 0.30, -0.30), jaw=(0.25, 0.0)),
    # Staggering drunkenly from side to side, arms low and swaying
    'walk_3': style(walk_arms=((-0.55, 0.45), (-0.45, -0.45)), stagger=0.16, roll=(0.05, 0.10, 0.06),
                    lean=(0.22, 0.20, 0.62), legs=((0.9, 0.9), 1.35), knee=((0.22, 0.22), 0.45), head_loll=0.18,
                    head=(0.38, 0.35, -0.30)),
    # Hunched low, lurching, arms swinging long and loose like an ape's
    'walk_4': style(lean=(0.22, 0.62, 0.62), walk_arms=((-0.35, 0.85), (-0.30, -0.85)), elbow=(0.05, 0.25),
                    head=(0.38, -0.25, -0.30), twist=(0.18, 0.12), legs=((1.1, 0.8), 1.35), knee=((0.35, 0.25), 0.45),
                    jaw=(0.35, 0.06)),
}
SPRINTS = {
    'sprint_1': STYLE_1,
    # Arms flung back behind it, head thrust forward
    'sprint_2': style(sprint_arms=(0.95, 0.45), arm_out=((0.10, -0.14), (0.05, -0.05), (0.18, -0.18)),
                      lean=(0.22, 0.32, 0.78), head=(0.38, 0.18, -0.55), elbow=(0.1, 0.05), jaw=(0.35, 0.0)),
    # Both arms clawing forward, reaching high, flailing
    'sprint_3': style(sprint_arms=(-1.65, 0.85), flail=0.35, lean=(0.22, 0.32, 0.50), head=(0.38, 0.18, -0.35),
                      jaw=(0.40, 0.12)),
    # Lopsided gallop: one arm clawing forward the whole time, the other pumping; uneven strides
    'sprint_4': style(sprint_arms=(-0.35, 1.55), limp_arm=None, legs=((1.0, 0.62), 1.15), knee=((0.28, 0.12), 0.60),
                      roll=(0.05, 0.13, 0.16), lean=(0.22, 0.32, 0.55), jaw=(0.30, 0.0), _claw='right'),
}


def stand_pose(t, pos, spd, st=STYLE_1, swing=0.0):
    """A standing zombie at one moment: t = ageInTicks, pos/spd = walkAnimationPos/Speed, swing = attack 0..1."""
    move = smooth(spd / 0.15)
    sprint = smooth((spd - SPRINT_FROM) / max(0.01, SPRINT_TO - SPRINT_FROM))
    walk_w = move * (1 - sprint)
    idle = 1 - move
    c, sn = math.cos(pos * 0.6662), math.sin(pos * 0.6662)
    half = math.sin(pos * 0.3331)  # once every two steps

    lean = lerp(lerp(st['lean'][0], st['lean'][1], walk_w), st['lean'][2], sprint)
    roll = (math.sin(t * 0.06) * st['roll'][0] * idle + sn * st['roll'][1] * walk_w + sn * st['roll'][2] * sprint
            + half * st['stagger'] * walk_w)
    twist = half * st['twist'][0] * walk_w + c * st['twist'][1] * sprint

    ia = st['idle_arms']
    (wr, wra), (wl, wla) = st['walk_arms']
    sa, samp = st['sprint_arms']
    r_arm = lerp(lerp(ia[0] + math.sin(t * 0.07) * 0.06, wr + c * wra * spd, move), sa - c * samp, sprint)
    l_arm = lerp(lerp(ia[1] + math.sin(t * 0.07 + 1.7) * 0.06, wl + c * wla * spd, move), sa + c * samp, sprint)
    if st.get('_claw') == 'right':
        r_arm = lerp(r_arm, -1.7 + c * 0.25, sprint)
    if st['flail']:
        r_arm += math.sin(t * 0.83) * st['flail']
        l_arm += math.sin(t * 0.71 + 2.0) * st['flail']
    ao = st['arm_out']
    r_z = lerp(lerp(ao[0][0], ao[1][0], move), ao[2][0], sprint)
    l_z = lerp(lerp(ao[0][1], ao[1][1], move), ao[2][1], sprint)
    r_y = lerp(0.0, st['walk_arm_in'][0], walk_w)
    l_y = lerp(0.0, st['walk_arm_in'][1], walk_w)
    bend_arms = st['elbow'][0] + st['elbow'][1] * sprint
    limp = st['limp_arm']
    if limp:
        # Hangs straight down, swinging loosely with the body
        loose = 0.05 + 0.25 * c * move
        if limp == 'right':
            r_arm, r_y, r_z = loose, 0.0, 0.08 - roll * 0.5
        else:
            l_arm, l_y, l_z = -loose, 0.0, -0.08 - roll * 0.5

    tw = st['twitch']
    twitch = math.sin(t * 0.9) * smooth((math.sin(t * 0.045) - 0.92) * 12) * tw
    head_drop = lerp(lerp(st['head'][0], st['head'][1], move), st['head'][2], sprint)
    head_roll = lerp(st['idle_head_roll'] + twitch, -roll * 0.7 + 0.10, move)
    head_roll += math.sin(t * 0.07) * st['head_loll']
    head_yaw = math.sin(t * 0.07 + 0.8) * st['head_loll'] * 0.6
    jaw = st['jaw'][0] + st['jaw'][1] * max(0.0, math.sin(t * 1.3))

    if swing > 0:
        w = math.sin(swing * math.pi)
        claw = lerp(-1.4, -2.8, swing / 0.35) if swing < 0.35 else lerp(-2.8, -0.45, smooth((swing - 0.35) / 0.65))
        k = min(1.0, w * 2)
        r_arm = lerp(r_arm, claw, k)
        l_arm = lerp(l_arm, claw + 0.25, k)
        r_y = lerp(r_y, 0.35, w)
        l_y = lerp(l_y, -0.35, w)
        lean += 0.28 * w
        head_drop -= 0.25 * w
        bend_arms += 0.2 * w
        jaw += 0.35 * w

    p = {}
    bob = -0.03 * walk_w * abs(sn) + 0.05 * sprint * (abs(c) - 0.5) + math.sin(t * 0.08) * 0.008 * idle
    p['root'] = {'pos': (sn * 0.04 * walk_w * 16 + half * st['stagger'] * 10 * walk_w, bob * 16, 0)}
    # The lean is shared between the lower back and the chest (a curved spine); together they turn as v1's torso did
    p['torso'] = {'rot': bb(lean * 0.6, twist * 0.5, roll * 0.6)}
    p['chest'] = {'rot': bb(lean * 0.4, twist * 0.5, roll * 0.4)}
    # The head rides the leaning torso: its own pitch is what the rig gives the world (drop, minus half the lean) less
    # the lean the torso already gave it.
    p['hi_head'] = {'rot': bb(head_drop - 1.5 * lean, head_yaw, head_roll)}
    p['jaw'] = {'rot': bb(-jaw, 0, 0)}

    # Elbows: the rig pulls the hand in by bend*0.3 of the 0.74 reach; the two 0.37 bones then fold by 2*acos(reach/0.74),
    # the upper arm swinging back half of that so the hand stays on the line.
    fold = 2 * math.acos(min(1.0, (0.74 - bend_arms * 0.3) / 0.74))
    for side, ax, ay, az in (('right', r_arm, r_y, r_z), ('left', l_arm, l_y, l_z)):
        f = 0.05 if limp == side else fold
        x, y, z = bb(ax, ay, az)
        p[side + '_arm'] = {'rot': (x - f / 2 * D, y, z)}
        p[side + '_forearm'] = {'rot': (f * D, 0, 0)}
        p[side + '_hand'] = {'rot': (12 + 10 * sprint, 0, 0)}  # fingers curled in a little

    # Legs: the limp, sprint strides; the leg swinging forward lifts its knee; the foot stays roughly flat
    leg_swing = 1.4 * spd
    (lr, ll), ls = st['legs']
    (kr, kl), ks = st['knee']
    for side, right in (('right', True), ('left', False)):
        x = (c if right else -c) * lerp(lr if right else ll, ls, sprint) * leg_swing
        x = max(-1.1, min(1.1, x))
        forward = max(0.0, -math.sin(x))
        lift = forward * (kr if right else kl) * walk_w + forward * ks * sprint
        knee = min(1.6, lift / 0.74 * 2.2) + 0.08 + 0.15 * sprint  # a slight bend always (it's hunched)
        thigh = -x * D + knee / 2 * D
        p[side + '_leg'] = {'rot': (thigh, 0, 0)}
        p[side + '_shin'] = {'rot': (-knee * D, 0, 0)}
        p[side + '_foot'] = {'rot': (-(thigh - knee * D) * 0.6, 0, 0)}
    return p


def idle_fn(st):
    def f(s):
        # one cycle of the 0.07 sway per loop so it repeats seamlessly (the others are close enough)
        t = s * 20
        return stand_pose(t * (TWO_PI / (IDLE_LEN * 20)) / 0.07, 0, 0, st)
    return f


def walk_fn(st):
    return lambda s: stand_pose(s * 20, s * 20 * WALK_SPD, WALK_SPD, st)


def sprint_fn(st):
    return lambda s: stand_pose(s * 20, s * 20 * SPRINT_SPD, SPRINT_SPD, st)


ATTACK_LEN = 0.4


def attack_fn(s):
    # Only the upper body: the legs keep whatever walk/idle is playing underneath
    p = stand_pose(0, 0, WALK_SPD, swing=min(0.999, s / ATTACK_LEN))
    return {b: v for b, v in p.items() if b in UPPER}


# ----------------------------------------------------------------------------------------------------------- crawlers
# Lying on its front: the root is tipped forward PRONE degrees about the feet and moved back so the body lies centred
# over where the zombie stands. The legs are blown off at the thigh: the thighs shrink to stumps, the rest is gone.
PRONE = -84.0
PRONE_POS = (0, 1.0, 17.0)
STUMP = (1, 0.45, 1)
GONE = (0, 0, 0)


ARM_UPPER, ARM_LOWER = 4.0, 4.5  # shoulder -> elbow, elbow -> wrist (pixels)
WRIST_Y = 1.6                     # wrist height with the hand flat on the ground


def turn(y, z, deg):
    """Turns the point (y, z) about the X axis by a Blockbench X angle."""
    a = math.radians(deg)
    return y * math.cos(a) - z * math.sin(a), y * math.sin(a) + z * math.cos(a)


def shoulder(p):
    """Where the shoulders are in the side view (y up, z back), and the X angle the arms hang from (root+torso+chest)."""
    rx, tx, cx = p['root']['rot'][0], p['torso']['rot'][0], p['chest']['rot'][0]
    y, z = turn(22 - 17.5, 0, cx)
    y, z = turn(y + 17.5 - 12, z, tx)
    y, z = turn(y + 12, z, rx)
    pos = p['root']['pos']
    return (y + pos[1], z + pos[2]), rx + tx + cx


def place_arm(p, side, sx, target, flare=10.0, curl=0.0):
    """Two-bone IK in the side view: shoulder and elbow angles that put the wrist on `target` (y, z), the elbow bending
    down (propped on the forearm, like a soldier crawling), the hand turned flat along the ground."""
    (sy, sz), base = shoulder(p)
    dy, dz = target[0] - sy, target[1] - sz
    r = min(ARM_UPPER + ARM_LOWER - 0.01, max(abs(ARM_UPPER - ARM_LOWER) + 0.01, math.hypot(dy, dz)))
    line = math.degrees(math.atan2(-dz, -dy))  # 0 = straight down, 90 = straight ahead
    a = math.degrees(math.acos((ARM_UPPER ** 2 + r * r - ARM_LOWER ** 2) / (2 * ARM_UPPER * r)))
    b = math.degrees(math.acos((ARM_LOWER ** 2 + r * r - ARM_UPPER ** 2) / (2 * ARM_LOWER * r)))
    upper, lower = line - a, line + b
    p[side + '_arm'] = {'rot': (upper - base, 0, flare * sx)}
    p[side + '_forearm'] = {'rot': (lower - upper, 0, 0)}
    p[side + '_hand'] = {'rot': (90 - lower + curl, 0, 0)}


def crawl_body():
    """Lying flat, stumps trailing, head up (arms not placed yet)."""
    p = {'root': {'rot': (PRONE, 0, 0), 'pos': PRONE_POS},
         'torso': {'rot': (0, 0, 0)}, 'chest': {'rot': (10, 0, 0)},
         'hi_head': {'rot': (60, 0, 0)}, 'jaw': {'rot': (14, 0, 0)}}
    for side, sx in (('right', 1), ('left', -1)):
        p[side + '_leg'] = {'rot': (8, 0, 6 * sx), 'scale': STUMP}
        p[side + '_shin'] = {'scale': GONE}
        p[side + '_foot'] = {'scale': GONE}
    return p


ANCHOR = shoulder(crawl_body())[0]  # the shoulders at rest: hand paths are measured from here, fixed in the world


def ahead(dz, h=0.0):
    """A wrist target `dz` pixels ahead of the resting shoulders, `h` above the ground."""
    return WRIST_Y + h, ANCHOR[1] - dz


def crawl_base():
    """The pose every crawler animation starts from: lying flat, head up, both hands planted ahead."""
    p = crawl_body()
    for side, sx in (('right', 1), ('left', -1)):
        place_arm(p, side, sx, ahead(4.5))
    return p


def hand_path(phase, reach_part, near=1.5, far=7.0, lift=3.0):
    """One arm's pull cycle, phase 0..1: lift and reach forward, plant, then drag the body up to the planted hand (it
    slides back relative to the body). Returns (distance ahead, height, how far into the reach 0..1)."""
    phase %= 1.0
    if phase < reach_part:
        k = smooth(phase / reach_part)
        return lerp(near, far, k), lift * math.sin(k * math.pi), math.sin(k * math.pi)
    k = smooth((phase - reach_part) / (1 - reach_part))
    return lerp(far, near, k), 0.0, 0.0


CRAWLS = {
    # name: (loop seconds, kind, intensity)
    'crawl_1': (1.6, 'alternate', 1.0),
    'crawl_2': (1.4, 'together', 1.0),
    'crawl_3': (1.5, 'one_arm', 1.0),
    'crawl_fast_1': (0.7, 'alternate', 1.6),
    'crawl_fast_2': (0.55, 'scramble', 1.8),
    'crawl_fast_3': (0.8, 'lunge', 1.8),
}


def crawl_fn(length, kind, power):
    both = kind in ('together', 'lunge')
    reach_part = 0.3 if kind in ('lunge', 'scramble') else 0.35

    def f(s):
        ph = s / length
        w = ph * TWO_PI
        p = crawl_body()
        phases = (ph, ph) if both else (ph, None) if kind == 'one_arm' else (ph, ph + 0.5)
        reaching = [hand_path(x, reach_part)[2] if x is not None else 0.0 for x in phases]
        # The body heaves up on each pull and rolls away from the reaching arm; the stumps twitch
        heave = 0.5 - 0.5 * math.cos(w if both or kind == 'one_arm' else w * 2)
        roll = (reaching[1] - reaching[0]) * (14 if kind == 'scramble' else 8)
        surge = (0.9 if kind == 'lunge' else 0.4) * power
        p['root']['pos'] = (PRONE_POS[0], PRONE_POS[1] + 0.4 * heave * power, PRONE_POS[2] - surge * math.sin(w))
        p['torso']['rot'] = (0, 0, roll * 0.5)
        chest = 10 + (16 if kind == 'lunge' else 6) * power * heave
        p['chest']['rot'] = (chest, 0, roll * 0.5)
        p['hi_head']['rot'] = (60 - (chest - 10) * 0.6 + 5 * math.sin(w * 2), -roll * 0.4, 0)
        p['jaw']['rot'] = (14 + 12 * heave * power / 1.8, 0, 0)
        far = 7.0 + 0.5 * (power - 1)
        flare = 22.0 if kind == 'scramble' else 10.0
        for (side, sx), phase in zip((('right', 1), ('left', -1)), phases):
            if phase is None:
                # Dragged along its side, flopping (straight back from the shoulder, a little down)
                base = shoulder(p)[1]
                p[side + '_arm'] = {'rot': (-75 - base + 5 * math.sin(w), 0, -14 * sx + 5 * math.sin(w * 2))}
                p[side + '_forearm'] = {'rot': (8, 0, 0)}
                p[side + '_hand'] = {'rot': (10, 0, 0)}
                continue
            dz, h, up = hand_path(phase, reach_part, far=far)
            place_arm(p, side, sx, ahead(dz, h), flare + 8 * up, curl=25 * up)
        for side, sx in (('right', 1), ('left', -1)):
            p[side + '_leg']['rot'] = (8 + 10 * math.sin(w + (0 if sx > 0 else math.pi)), 0, 6 * sx)
        return p
    return f


CRAWL_IDLE_LEN = 4.0


def crawl_idle_fn(variant):
    def f(s):
        w = s / CRAWL_IDLE_LEN * TWO_PI
        p = crawl_body()
        p['root']['pos'] = (0, PRONE_POS[1] + 0.25 * math.sin(w * 2), PRONE_POS[2])  # breathing
        if variant == 1:
            # Propped up on its forearms, head slowly scanning round, jaw working
            p['chest']['rot'] = (22 + 3 * math.sin(w), 0, 0)
            p['hi_head']['rot'] = (50 + 4 * math.sin(w * 2), 28 * math.sin(w), 6 * math.sin(w))
            p['jaw']['rot'] = (14 + 10 * max(0.0, math.sin(w * 6)), 0, 0)
            for side, sx in (('right', 1), ('left', -1)):
                place_arm(p, side, sx, ahead(5.0), 14)
        else:
            # Flat on the ground, one hand clawing at the dirt, head twitching
            p['chest']['rot'] = (6, 0, -4)
            twitch = math.sin(w * 9) * smooth((math.sin(w) - 0.6) * 4) * 14
            p['hi_head']['rot'] = (50, 10 + twitch, 10 + twitch * 0.5)
            claw = 0.5 + 0.5 * math.sin(w * 2)
            place_arm(p, 'right', 1, ahead(lerp(5.5, 7.5, claw), 1.2 * math.sin(w * 2) ** 2), 14, curl=40 * claw)
            place_arm(p, 'left', -1, ahead(3.0), 26)
        return p
    return f


def keys_crawl_fall():
    """Grenade blast: the legs blow off, the body drops and tips forward onto its hands, then lies in the crawl pose."""
    k = {}

    def key(bone, ch, t, v):
        k.setdefault((bone, ch), []).append((t, v))

    stand = stand_pose(0, 0, 0)
    end = crawl_base()
    for b in UPPER:
        key(b, 'rot', 0, stand.get(b, {}).get('rot', (0, 0, 0)))
    key('root', 'rot', 0, (0, 0, 0))
    key('root', 'pos', 0, (0, 0, 0))
    for side, sx in (('right', 1), ('left', -1)):
        # Blown off at once: the stumps stay, kicked out by the blast
        key(side + '_leg', 'scale', 0, STUMP)
        key(side + '_shin', 'scale', 0, GONE)
        key(side + '_foot', 'scale', 0, GONE)
        key(side + '_leg', 'rot', 0, (-35, 0, 25 * sx))
        key(side + '_leg', 'rot', 0.5, end[side + '_leg']['rot'])
    # Thrown up and back by the blast, arms flung out
    key('torso', 'rot', 0.12, (18, 0, 0))
    key('hi_head', 'rot', 0.12, (35, 0, 8))
    key('right_arm', 'rot', 0.12, (70, 0, 45))
    key('left_arm', 'rot', 0.12, (80, 0, -45))
    key('root', 'pos', 0.12, (0, -4, 0))
    # Falls (nothing under the hips any more) and tips forward, throwing the arms out to catch itself
    key('root', 'pos', 0.3, (0, -7, 4))
    key('root', 'rot', 0.3, (-30, 0, 4))
    key('torso', 'rot', 0.3, (-10, 0, 0))
    key('right_arm', 'rot', 0.3, (120, 0, 20))
    key('left_arm', 'rot', 0.3, (125, 0, -20))
    key('root', 'pos', 0.5, (0, 1.5, 14))
    key('root', 'rot', 0.5, (PRONE - 4, 0, 2))
    # Slams down, bounces once, then settles with its head coming up
    key('root', 'rot', 0.62, (PRONE + 5, 0, 1))
    key('root', 'pos', 0.62, add(PRONE_POS, (0, 1.5, 0)))
    key('hi_head', 'rot', 0.5, (10, 0, 0))
    key('root', 'rot', 0.75, (PRONE, 0, 0))
    key('root', 'pos', 0.75, PRONE_POS)
    for b in UPPER:
        key(b, 'rot', 1.1, end[b]['rot'])
    return k


CRAWL_ATTACK_LEN = 0.45


def crawl_attack_fn(s):
    """From the ground: rears up on the left arm and claws the right one down at the target's legs."""
    sw = min(0.999, s / CRAWL_ATTACK_LEN)
    w = math.sin(sw * math.pi)
    p = crawl_base()
    claw = lerp(150, 205, sw / 0.35) if sw < 0.35 else lerp(205, 120, smooth((sw - 0.35) / 0.65))
    p['right_arm'] = {'rot': (lerp(150, claw, min(1.0, w * 2)), 0, 18)}
    p['right_forearm'] = {'rot': (lerp(35, 10, w), 0, 0)}
    p['chest']['rot'] = (10 + 22 * w, 0, -6 * w)
    place_arm(p, 'left', -1, ahead(4.0), 14)
    p['hi_head']['rot'] = (62 - 15 * w, 0, 0)
    p['jaw']['rot'] = (14 + 25 * w, 0, 0)
    return {b: v for b, v in p.items() if b in UPPER}


def keys_crawl_death():
    """A crawler dies: one last heave up, then it collapses face down, arms spread."""
    k = {}
    base = crawl_base()

    def key(bone, ch, t, v):
        k.setdefault((bone, ch), []).append((t, v))

    for b in UPPER:
        key(b, 'rot', 0, base[b]['rot'])
    key('root', 'rot', 0, (PRONE, 0, 0))
    key('root', 'pos', 0, PRONE_POS)
    key('chest', 'rot', 0.2, (24, 0, 0))
    key('hi_head', 'rot', 0.2, (70, 0, 10))
    key('chest', 'rot', 0.55, (-4, 0, 0))
    key('hi_head', 'rot', 0.55, (-10, 25, 0))
    key('jaw', 'rot', 0.55, (30, 0, 0))
    key('root', 'rot', 0.55, (PRONE - 6, 0, 0))
    key('root', 'pos', 0.55, add(PRONE_POS, (0, -1, 0)))
    for side, sx in (('right', 1), ('left', -1)):
        key(side + '_arm', 'rot', 0.55, (165, 0, 45 * sx))
        key(side + '_forearm', 'rot', 0.55, (5, 0, 0))
        for b in ('_leg', '_shin', '_foot'):
            key(side + b, 'scale', 0, base[side + b]['scale'])
    return k
