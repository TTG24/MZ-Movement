"""The arena zombie model, mz_zombie.bbmodel, for BOTH renderers: the client mod draws it for players with the mod, and
BetterModel (on the server) draws it for players without it. One file, so both see the same zombie.

Usage: python -I tools/models/zombie_body.py <out .bbmodel> [<out gore .png>]
       python -I tools/models/zombie_body.py --looks <dir>   -> mz_zombie_1..5.bbmodel (the five uniforms, zombie_skins.py)
  * Server: plugins/BetterModel/models/mz_zombie_1..5.bbmodel (TacticalMovementPlugin gives each arena zombie one, the
    same one the mod picks: ZombieModel.skin); mz_zombie.bbmodel is only used when those aren't there.
  * Server: plugins/BetterModel/models/mz_zombie.bbmodel (TacticalMovementPlugin puts it on every arena zombie).
  * Mod: src/main/resources/assets/tacticalmovement/models/mz_zombie.bbmodel, and the wound texture (also inside the
    .bbmodel, which BetterModel uses) as src/main/resources/assets/tacticalmovement/textures/entity/zombie_gore.png.
Open it in Blockbench to look at or tweak the model and every animation.

Body: the mod's zombie texture (src/main/resources/assets/minecraft/textures/entity/zombie/zombie.png) on a jointed body:
lower back and chest, head with a jaw, upper arm / forearm / hand, thigh / shin / foot, plus the glowing orange eyes
(full-bright plates on the face) and gore caps inside the thighs that show once the legs are blown off.

Animations (zombie_anims.py): idle_1..3, walk_1..4, sprint_1..4, attack, rise, death_back / death_forward /
death_headshot; crawlers: crawl_fall (legs blown off), crawl_idle_1..2, crawl_1..3, crawl_fast_1..3, crawl_attack,
crawl_death. Variant 1 of idle / walk / sprint is the original zombie (tools/models/reference).

Conventions: the poses are worked out in Blockbench angles (zombie_anims.py); BetterModel turns X and Y the other way
round, so those two are negated when written (what lands in the file is the vanilla ModelPart angle in degrees).
"""
import base64, importlib.util, json, math, os, struct, sys, uuid, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
TEX = os.path.join(ROOT, 'src/main/resources/assets/minecraft/textures/entity/zombie/zombie.png')
EYES_TEX = os.path.join(ROOT, 'src/main/resources/assets/tacticalmovement/textures/entity/zombie_eyes.png')
sys.path.insert(0, HERE)
import zombie_anims as A  # noqa: E402

# The rise keyframes, shared with the client mod (one source)
_spec = importlib.util.spec_from_file_location('zombie_rise', os.path.join(HERE, 'zombie_rise.py'))
zombie_rise = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(zombie_rise)


def uid():
    return str(uuid.uuid4())


# ------------------------------------------------------------------------------------------------------------ geometry

def box_faces(u, v, w, h, d, row0, row1, top, bottom, mirror=False, tex=0):
    """Vanilla box UVs (64x64) for the rows row0..row1 of a w x h x d box at (u, v); top/bottom caps optional."""
    f = {
        'north': [u + d, v + d + row0, u + d + w, v + d + row1],
        'east': [u, v + d + row0, u + d, v + d + row1],
        'south': [u + 2 * d + w, v + d + row0, u + 2 * d + 2 * w, v + d + row1],
        'west': [u + d + w, v + d + row0, u + 2 * d + w, v + d + row1],
        'up': [u + d + w, v + d, u + d, v],
        'down': [u + d + 2 * w, v, u + d + w, v + d],
    }
    if mirror:
        f['east'], f['west'] = f['west'], f['east']
        for k in f:
            a = f[k]
            f[k] = [a[2], a[1], a[0], a[3]]
    out = {}
    for k, a in f.items():
        on = (k not in ('up', 'down')) or (k == 'up' and top) or (k == 'down' and bottom)
        out[k] = {'uv': a, 'texture': tex if on else None}
    return out


def solid_faces(tex, uv=(0, 0, 16, 16)):
    return {k: {'uv': list(uv), 'texture': tex} for k in ('north', 'east', 'south', 'west', 'up', 'down')}


def cube(name, frm, to, faces, inflate=0.0, light=0):
    c = {'name': name, 'box_uv': False, 'rescale': False, 'locked': False, 'render_order': 'default',
         'allow_mirror_modeling': True, 'from': frm, 'to': to, 'autouv': 0, 'color': 0, 'origin': [0, 0, 0],
         'faces': faces, 'type': 'cube', 'uuid': uid()}
    if inflate:
        c['inflate'] = inflate
    if light:
        c['light_emission'] = light
    return c


# Bone tree: name -> (pivot, parent). Front is north (-z), the zombie's right is +x. The head is "hi_head" so BetterModel
# turns it (eyes included) with the zombie's look on top of the animation.
BONES = {
    'root': ((0, 0, 0), None),
    'torso': ((0, 12, 0), 'root'),
    'chest': ((0, 17.5, 0), 'torso'),
    'hi_head': ((0, 24, 0), 'chest'),
    'jaw': ((0, 26.5, 3.7), 'hi_head'),  # hinged at its top back: opening drops the front, nothing swings up
    'right_arm': ((5, 22, 0), 'chest'), 'right_forearm': ((5, 18, 0), 'right_arm'), 'right_hand': ((5, 13.5, 0), 'right_forearm'),
    'left_arm': ((-5, 22, 0), 'chest'), 'left_forearm': ((-5, 18, 0), 'left_arm'), 'left_hand': ((-5, 13.5, 0), 'left_forearm'),
    'right_leg': ((1.9, 12, 0), 'root'), 'right_shin': ((1.9, 6, 0), 'right_leg'), 'right_foot': ((1.9, 2, 0), 'right_shin'),
    'left_leg': ((-1.9, 12, 0), 'root'), 'left_shin': ((-1.9, 6, 0), 'left_leg'), 'left_foot': ((-1.9, 2, 0), 'left_shin'),
    # The thigh stumps' wounds: hidden (scale 0) in every animation but the crawler ones
    'right_wound': ((1.9, 6, 0), 'right_leg'), 'left_wound': ((-1.9, 6, 0), 'left_leg'),
}
WOUNDS = ('right_wound', 'left_wound')
GORE = 2  # texture index of the wound colour
JAW_OPEN = 10  # degrees the jaw always hangs open, on top of what the animations do


def build_geometry():
    elements, groups = [], {}
    for name, (pivot, _) in BONES.items():
        groups[name] = {'name': name, 'origin': list(pivot), 'rotation': [0, 0, 0], 'color': 0, 'uuid': uid(),
                        'export': True, 'mirror_uv': False, 'isOpen': True, 'locked': False, 'visibility': True,
                        'autouv': 0, 'children': []}

    def add(bone, el):
        elements.append(el)
        groups[bone]['children'].append(el['uuid'])

    # Each body part is cut out of its vanilla box in rows; neighbours overlap by half a pixel so no gap opens at a bend.
    add('torso', cube('abdomen', [-4, 12, -2], [4, 18, 2], box_faces(16, 16, 8, 12, 4, 6, 12, False, True)))
    add('chest', cube('chest', [-4, 17, -2], [4, 24, 2], box_faces(16, 16, 8, 12, 4, 0, 7, True, False)))
    add('hi_head', cube('skull', [-4, 26, -4], [4, 32, 4], box_faces(0, 0, 8, 8, 8, 0, 6, True, False)))
    add('hi_head', cube('hat', [-4, 26, -4], [4, 32, 4], box_faces(32, 0, 8, 8, 8, 0, 6, True, False), inflate=0.5))
    # The mouth: the jaw is a little narrower than the skull and hinges at the back; inside, a dark palate under the skull
    # and a mouth floor on the jaw, with a row of teeth on each (hidden behind the face while the mouth is shut)
    add('jaw', cube('jaw', [-3.7, 24, -4.05], [3.7, 26.5, 3.7], box_faces(0, 0, 8, 8, 8, 5.5, 8, False, True)))
    add('jaw', cube('jaw_hat', [-3.7, 24, -4.05], [3.7, 26.5, 3.7], box_faces(32, 0, 8, 8, 8, 5.5, 8, False, True), inflate=0.5))
    add('jaw', cube('mouth_floor', [-3.2, 26.3, -3.5], [3.2, 26.4, 3.2], solid_faces(GORE, (0, 0, 3, 3))))
    add('hi_head', cube('palate', [-3.9, 25.95, -3.9], [3.9, 26.05, 0.5], solid_faces(GORE, (12, 0, 15, 3))))
    # Behind the mouth the skull is closed underneath with skin (seen from behind when the jaw drops)
    add('hi_head', cube('skull_under', [-4, 25.95, 0.5], [4, 26.05, 4], {k: {'uv': [17, 0, 23, 3], 'texture': 0} for k in ('north', 'east', 'south', 'west', 'up', 'down')}))
    # Single uneven teeth, a few gone, set back from the lips: (x, width, length) each
    for i, (x, w, ln) in enumerate(((-2.6, 0.8, 0.5), (-1.5, 0.7, 0.35), (-0.55, 0.9, 0.55), (0.6, 0.8, 0.45), (2.0, 0.7, 0.4))):
        add('hi_head', cube('tooth_u%d' % i, [x, 26.0 - ln, -3.6], [x + w, 26.0, -3.15], solid_faces(GORE, (6, 6, 9, 9))))
    for i, (x, w, ln) in enumerate(((-2.3, 0.8, 0.4), (-1.2, 0.7, 0.5), (0.1, 0.8, 0.3), (1.3, 0.9, 0.45))):
        add('jaw', cube('tooth_l%d' % i, [x, 26.4, -3.5], [x + w, 26.4 + ln, -3.1], solid_faces(GORE, (6, 6, 9, 9))))
    for side, x0, mirror in (('right', 4, False), ('left', -8, True)):
        add(side + '_arm', cube('arm', [x0, 17.5, -2], [x0 + 4, 24, 2], box_faces(40, 16, 4, 12, 4, 0, 6.5, True, False, mirror)))
        add(side + '_forearm', cube('forearm', [x0, 13, -2], [x0 + 4, 18.5, 2], box_faces(40, 16, 4, 12, 4, 5.5, 11, False, False, mirror)))
        add(side + '_hand', cube('hand', [x0, 12, -2], [x0 + 4, 14, 2], box_faces(40, 16, 4, 12, 4, 10, 12, False, True, mirror)))
    for side, x0, mirror in (('right', -0.1, False), ('left', -3.9, True)):
        add(side + '_leg', cube('thigh', [x0, 5.5, -2], [x0 + 4, 12, 2], box_faces(0, 16, 4, 12, 4, 0, 6.5, True, False, mirror)))
        # The wound at the end of the stump, once the leg is blown off
        add(side + '_wound', cube('stump', [x0 + 0.05, 5.5, -1.95], [x0 + 3.95, 6.0, 1.95], solid_faces(GORE)))
        add(side + '_shin', cube('shin', [x0, 1.5, -2], [x0 + 4, 6.5, 2], box_faces(0, 16, 4, 12, 4, 5.5, 10.5, False, False, mirror)))
        add(side + '_foot', cube('foot', [x0, 0, -2], [x0 + 4, 2, 2], box_faces(0, 16, 4, 12, 4, 10, 12, False, True, mirror)))
    # The glowing eyes (zombie_eyes.png has one lit pixel each, at (10,10) and (13,10) of the face): thin plates just in
    # front of the face, full-bright. From the front, the texture's left is the zombie's right (+x).
    for name, x, u in (('eye_r', 1, 10), ('eye_l', -2, 13)):
        add('hi_head', cube(name, [x, 29, -4.06], [x + 1, 30, -4.01], solid_faces(1, (u, 10, u + 1, 11)), light=15))

    def node(name):
        kids = [node(n) for n, (_, parent) in BONES.items() if parent == name]
        g = groups[name]
        return {'uuid': g['uuid'], 'isOpen': True, 'children': list(g['children']) + kids}

    return elements, groups, [node('root')]


# --------------------------------------------------------------------------------------------------------- animations

def sample(fn, length, step):
    """Samples fn(seconds) -> pose every `step` seconds over [0, length] into per-bone keyframe lists."""
    frames = {}
    n = max(1, round(length / step))
    for i in range(n + 1):
        s = length * i / n
        for bone, ch in fn(s).items():
            for k, v in ch.items():
                frames.setdefault((bone, k), []).append((round(s, 4), tuple(round(a, 3) for a in v)))
    return frames


def rest(bone, ch='rot'):
    return A.stand_pose(0, 0, 0).get(bone, {}).get(ch, (0, 0, 0))


def keys_death(kind):
    """Standing deaths: hand-made keyframes (the mod uses physics ragdolls, which can't run on the server)."""
    k = {}

    def key(bone, ch, t, v):
        k.setdefault((bone, ch), []).append((t, v))

    for b in ('torso', 'chest', 'hi_head', 'right_arm', 'left_arm', 'right_forearm', 'left_forearm', 'right_leg',
              'left_leg', 'right_shin', 'left_shin'):
        key(b, 'rot', 0, rest(b))
    key('root', 'pos', 0, (0, 0, 0))
    key('root', 'rot', 0, (0, 0, 0))
    key('chest', 'rot', 0.3, (0, 0, 0))
    key('jaw', 'rot', 0, rest('jaw'))
    key('jaw', 'rot', 0.5, (30, 0, 0))
    if kind in ('back', 'headshot'):
        snap = 0.08 if kind == 'headshot' else 0.14
        # Thrown back by the hit: chest and head snap back, arms fling up and out
        key('torso', 'rot', snap, (14, 0, 4))
        key('hi_head', 'rot', snap, (40, 8, 0))
        key('right_arm', 'rot', snap, (75, 0, 30))
        key('left_arm', 'rot', snap, (65, 0, -30))
        key('right_forearm', 'rot', snap, (25, 0, 0))
        key('left_forearm', 'rot', snap, (25, 0, 0))
        # The knees go
        key('root', 'pos', 0.45, (0, -3, 1))
        key('root', 'rot', 0.45, (14, 0, 3))
        key('right_leg', 'rot', 0.45, (35, 0, 4))
        key('left_leg', 'rot', 0.45, (20, 0, -4))
        key('right_shin', 'rot', 0.45, (-55, 0, 0))
        key('left_shin', 'rot', 0.45, (-35, 0, 0))
        key('torso', 'rot', 0.45, (6, 0, 6))
        # Down on its back, limbs splayed
        key('root', 'rot', 0.9, (90, 0, 4))
        key('root', 'pos', 0.9, (0, 2.5, 2))
        key('torso', 'rot', 0.9, (0, 0, 0))
        key('hi_head', 'rot', 0.9, (12, 28, 6))
        key('right_arm', 'rot', 0.9, (35, 0, 55))
        key('left_arm', 'rot', 0.9, (20, 0, -45))
        key('right_forearm', 'rot', 0.9, (18, 0, 0))
        key('left_forearm', 'rot', 0.9, (30, 0, 0))
        key('right_leg', 'rot', 0.9, (8, 0, 10))
        key('left_leg', 'rot', 0.9, (2, 0, -8))
        key('right_shin', 'rot', 0.9, (-10, 0, 0))
        key('left_shin', 'rot', 0.9, (-4, 0, 0))
        # A small bounce as it lands
        key('root', 'rot', 1.0, (86, 0, 4))
        key('root', 'rot', 1.15, (90, 0, 4))
        if kind == 'headshot':
            k[('hi_head', 'scale')] = [(0, (1, 1, 1)), (0.04, (0, 0, 0))]
    else:  # forward: crumples onto its knees, then falls on its face
        key('torso', 'rot', 0.18, (-38, 0, -4))
        key('hi_head', 'rot', 0.18, (-25, -6, 0))
        key('right_arm', 'rot', 0.18, (10, 0, 6))
        key('left_arm', 'rot', 0.18, (18, 0, -6))
        key('root', 'pos', 0.5, (0, -6, -3))
        key('right_leg', 'rot', 0.5, (85, 0, 3))
        key('left_leg', 'rot', 0.5, (75, 0, -3))
        key('right_shin', 'rot', 0.5, (-95, 0, 0))
        key('left_shin', 'rot', 0.5, (-90, 0, 0))
        key('torso', 'rot', 0.5, (-20, 0, 3))
        key('hi_head', 'rot', 0.5, (-35, 0, 6))
        key('right_arm', 'rot', 0.5, (25, 0, 8))
        key('left_arm', 'rot', 0.5, (30, 0, -8))
        key('root', 'rot', 1.0, (-90, 0, -3))
        key('root', 'pos', 1.0, (0, 2.5, -2))
        key('right_leg', 'rot', 1.0, (0, 0, 6))
        key('left_leg', 'rot', 1.0, (-4, 0, -4))
        key('right_shin', 'rot', 1.0, (-12, 0, 0))
        key('left_shin', 'rot', 1.0, (-6, 0, 0))
        key('torso', 'rot', 1.0, (0, 0, 0))
        key('hi_head', 'rot', 1.0, (0, 30, 0))
        key('right_arm', 'rot', 1.0, (12, 0, 25))
        key('left_arm', 'rot', 1.0, (160, 0, -20))
        key('left_forearm', 'rot', 1.0, (35, 0, 0))
        key('root', 'rot', 1.1, (-86, 0, -3))
        key('root', 'rot', 1.25, (-90, 0, -3))
    return k


def keys_rise():
    """zombie_rise.py's keys on this model's bones (the forearms and shins stay straight)."""
    names = {'root': 'root', 'torso': 'torso', 'head': 'hi_head', 'right_arm': 'right_arm', 'left_arm': 'left_arm',
             'right_leg': 'right_leg', 'left_leg': 'left_leg'}
    k = {}
    for key, frames in zombie_rise.KEYS.items():
        bone, ch = key.split('.')
        k[(names[bone], 'pos' if ch == 'pos' else 'rot')] = list(frames)
    return k


def all_animations():
    """(name, length, loop, keys, interpolation) for every animation in the model."""
    out = []
    for name, st in A.IDLES.items():
        out.append((name, A.IDLE_LEN, 'loop', sample(A.idle_fn(st), A.IDLE_LEN, 0.25), 'catmullrom'))
    for name, st in A.WALKS.items():
        out.append((name, A.WALK_LEN, 'loop', sample(A.walk_fn(st), A.WALK_LEN, A.WALK_LEN / 16), 'catmullrom'))
    for name, st in A.SPRINTS.items():
        out.append((name, A.SPRINT_LEN, 'loop', sample(A.sprint_fn(st), A.SPRINT_LEN, A.SPRINT_LEN / 16), 'catmullrom'))
    out.append(('attack', A.ATTACK_LEN, 'once', sample(A.attack_fn, A.ATTACK_LEN, A.ATTACK_LEN / 8), 'catmullrom'))
    out.append(('rise', zombie_rise.LENGTH, 'hold', keys_rise(), 'catmullrom'))
    out.append(('death_back', 1.15, 'hold', keys_death('back'), 'linear'))
    out.append(('death_forward', 1.25, 'hold', keys_death('forward'), 'linear'))
    out.append(('death_headshot', 1.15, 'hold', keys_death('headshot'), 'linear'))
    # Crawlers
    out.append(('crawl_fall', 1.1, 'hold', A.keys_crawl_fall(), 'linear'))
    for i in (1, 2):
        out.append(('crawl_idle_%d' % i, A.CRAWL_IDLE_LEN, 'loop', sample(A.crawl_idle_fn(i), A.CRAWL_IDLE_LEN, 0.2),
                    'catmullrom'))
    for name, (length, kind, power) in A.CRAWLS.items():
        out.append((name, length, 'loop', sample(A.crawl_fn(length, kind, power), length, length / 16), 'catmullrom'))
    out.append(('crawl_attack', A.CRAWL_ATTACK_LEN, 'once',
                sample(A.crawl_attack_fn, A.CRAWL_ATTACK_LEN, A.CRAWL_ATTACK_LEN / 8), 'catmullrom'))
    out.append(('crawl_death', 0.8, 'hold', A.keys_crawl_death(), 'linear'))
    # The mouth always hangs open: every animation's jaw opened a little more (one with no jaw keys gets just that)
    for name, _, _, keys, _ in out:
        frames = keys.get(('jaw', 'rot')) or [(0, (0, 0, 0))]
        # (the animations' jaw angles open it upwards on this rig, where it hinges behind the mouth: turned the other way)
        keys[('jaw', 'rot')] = [(t, (-(x + JAW_OPEN), y, z)) for t, (x, y, z) in frames]
    # Legs on: no wounds. Legs off: wounds shown.
    for name, _, _, keys, _ in out:
        for w in WOUNDS:
            keys[(w, 'scale')] = [(0, (1, 1, 1) if name.startswith('crawl') else (0, 0, 0))]
    return out


# ------------------------------------------------------------------------------------------------------------- output

def animation(name, length, loop, keys, groups, interp):
    animators = {}
    for (bone, ch), frames in sorted(keys.items()):
        g = groups[bone]
        a = animators.setdefault(g['uuid'], {'name': g['name'], 'type': 'bone', 'keyframes': []})
        channel = {'rot': 'rotation', 'pos': 'position', 'scale': 'scale'}[ch]
        for t, (x, y, z) in frames:
            if ch == 'rot':
                x, y = -x, -y  # BetterModel turns X and Y the other way round (see zombie_rise.py)
            # Scale snaps (a limb is there or it isn't); the rest eases
            a['keyframes'].append({'channel': channel, 'data_points': [{'x': str(x), 'y': str(y), 'z': str(z)}],
                                   'uuid': uid(), 'time': t, 'color': -1,
                                   'interpolation': 'step' if ch == 'scale' else interp})
    return {'uuid': uid(), 'name': name, 'loop': loop, 'override': False, 'length': round(length, 4), 'snapping': 20,
            'selected': False, 'anim_time_update': '', 'blend_weight': '', 'start_delay': '', 'loop_delay': '',
            'animators': animators}


def png(w, h, pixel):
    """A small RGBA PNG; pixel(x, y) -> (r, g, b, a)."""
    raw = b''.join(b'\0' + b''.join(bytes(pixel(x, y)) for x in range(w)) for y in range(h))

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def gore_png():
    """16x16 raw wound: dark blood red with brighter flesh and a pale bone in the middle."""
    def px(x, y):
        n = ((x * 73 + y * 151) ^ (x * y * 31)) % 23 / 22.0
        if y < 3 and (x < 3 or x >= 12):
            # The inside of the mouth (mouth floor, palate): dark, wet
            return (int(48 + 22 * n), int(8 + 6 * n), int(12 + 6 * n), 255)
        if 6 <= x <= 9 and 6 <= y <= 9:
            return (214, 200, 170, 255) if (x + y) % 3 else (180, 160, 130, 255)
        return (int(90 + 70 * n), int(8 + 14 * n), int(10 + 12 * n), 255)
    return png(16, 16, px)


def texture(data, name, idx, size):
    return {'name': name, 'path': '', 'folder': '', 'namespace': '', 'id': str(idx), 'width': size, 'height': size,
            'uv_width': size, 'uv_height': size, 'particle': False, 'render_mode': 'default', 'render_sides': 'auto',
            'frame_time': 1, 'frame_order_type': 'loop', 'frame_order': '', 'frame_interpolate': False,
            'visible': True, 'internal': True, 'saved': True, 'uuid': uid(),
            'source': 'data:image/png;base64,' + base64.b64encode(data).decode()}


def build(path, skin=None):
    """Writes the model; skin = the PNG bytes of its texture (zombie_skins.py), default the mod's zombie.png."""
    elements, groups, outliner = build_geometry()
    anims = [animation(n, l, loop, k, groups, i) for n, l, loop, k, i in all_animations()]
    textures = [texture(skin or open(TEX, 'rb').read(), 'zombie.png', 0, 64),
                texture(open(EYES_TEX, 'rb').read(), 'zombie_eyes.png', 1, 64),
                texture(gore_png(), 'gore.png', 2, 16)]
    model = {'meta': {'format_version': '4.10', 'model_format': 'free', 'box_uv': False}, 'name': 'mz_zombie',
             'model_identifier': '', 'visible_box': [1, 2, 0], 'variable_placeholders': '',
             'variable_placeholder_buttons': [], 'timeline_setups': [], 'unhandled_root_fields': {},
             'resolution': {'width': 64, 'height': 64}, 'elements': elements, 'groups': list(groups.values()),
             'outliner': outliner, 'textures': textures, 'animations': anims}
    with open(path, 'w') as f:
        json.dump(model, f)
    print('wrote', path, '-', len(anims), 'animations')


if __name__ == '__main__':
    if sys.argv[1] == '--looks':
        # The server's five looks: mz_zombie_1..5.bbmodel, each with its skin from zombie_skins.py
        import zombie_skins
        for i, (name, data) in enumerate(zombie_skins.skins(), 1):
            out = os.path.join(sys.argv[2], 'mz_zombie_%d.bbmodel' % i)
            build(out, data)
            # BetterModel names the model after the file; inside, it is named the same
            m = json.load(open(out))
            m['name'] = 'mz_zombie_%d' % i
            json.dump(m, open(out, 'w'))
        sys.exit(0)
    build(sys.argv[1])
    if len(sys.argv) > 2:
        with open(sys.argv[2], 'wb') as f:
            f.write(gore_png())
        print('wrote', sys.argv[2])
