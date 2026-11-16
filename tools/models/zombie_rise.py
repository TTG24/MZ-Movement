"""The zombie "dig up from the ground" spawn animation, from one set of keyframes, for both renderers.

Usage: python -I tools/models/zombie_rise.py <out .bbmodel> <out ZombieRiseKeys.java>

  * <out .bbmodel>: a BetterModel model (zombie body with the vanilla zombie texture, one "rise" animation) for players
    WITHOUT the client mod. Goes in plugins/BetterModel/models/zombie_rise.bbmodel on the server.
  * <out .java>: the same keyframes as Java arrays, which the client mod plays on the real zombie model
    (src/client/java/com/mzcod/tacticalmovement/client/animation/ZombieRiseKeys.java).

Conventions (Blockbench's): angles in degrees; X rotation + swings an arm forward/up and tilts the head back (looking up),
so - on the torso leans it forward. Position in pixels, y up. The body starts buried (root y -40) and climbs out: one hand
claws up through the dirt, then the other, it hauls itself up on both arms, steps out one leg at a time and ends in the
hunched idle pose the zombie walks off with.
"""
import base64, json, os, sys, uuid

LENGTH = 2.5  # seconds; the server keeps the zombie frozen for exactly this long (RISE_TICKS = 50)

# channel -> list of (time s, value). Rotations are (x, y, z) degrees, positions (x, y, z) pixels.
KEYS = {
    'root.pos': [(0, (0, -40, 0)), (0.3, (0, -31, 0)), (0.6, (0, -27, 0)), (0.9, (0, -24, 0)), (1.3, (0, -14, 0)),
                 (1.7, (0, -6, 0)), (2.1, (0, -1, 0)), (2.5, (0, 0, 0))],
    'torso.rot': [(0, (0, 0, 0)), (0.6, (-5, 8, 0)), (0.9, (-10, -6, 4)), (1.3, (-38, 4, -4)), (1.7, (-30, 0, 3)),
                  (2.1, (-16, 0, 0)), (2.5, (-12.6, 0, 0))],
    'head.rot': [(0, (30, 0, 0)), (0.3, (35, 10, 12)), (0.6, (20, -8, -10)), (1.0, (-20, 0, 8)), (1.3, (-30, 6, 0)),
                 (1.7, (8, -10, -6)), (2.1, (-25, 0, 9)), (2.5, (-21.8, 0, 9))],
    'right_arm.rot': [(0, (178, 0, -6)), (0.15, (165, 0, 8)), (0.3, (186, 0, -10)), (0.45, (162, 0, 6)), (0.6, (174, 0, -4)),
                      (0.9, (100, -10, 0)), (1.1, (62, -8, -12)), (1.5, (42, 0, -10)), (1.9, (34, 0, -6)), (2.5, (17, 0, -5.7))],
    'left_arm.rot': [(0, (0, 0, 0)), (0.3, (25, 0, 0)), (0.55, (176, 0, 8)), (0.75, (160, 0, -6)), (0.95, (108, 10, 0)),
                     (1.15, (66, 8, 12)), (1.5, (44, 0, 10)), (1.9, (30, 0, 8)), (2.5, (10.3, 0, 8))],
    'right_leg.rot': [(0, (0, 0, 0)), (1.3, (0, 0, 0)), (1.6, (72, 0, 4)), (1.9, (22, 0, 0)), (2.2, (0, 0, 0)), (2.5, (0, 0, 0))],
    'left_leg.rot': [(0, (0, 0, 0)), (1.7, (0, 0, 0)), (1.95, (58, 0, -4)), (2.25, (0, 0, 0)), (2.5, (0, 0, 0))],
}

# Minecraft's own zombie texture, copied out of the game jar (assets/minecraft/textures/entity/zombie/zombie.png) next
# to this script. It is not in the repository (it is Mojang's), so copy it here before running.
TEX = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'zombie.png')


def uid():
    return str(uuid.uuid4())


def faces(u, v, w, h, d, mirror=False):
    """Vanilla box UVs (64x64) for a w x h x d box at texture offset (u, v); the front is north."""
    f = {
        'north': [u + d, v + d, u + d + w, v + d + h],
        'east': [u, v + d, u + d, v + d + h],
        'south': [u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h],
        'west': [u + d + w, v + d, u + 2 * d + w, v + d + h],
        'up': [u + d + w, v + d, u + d, v],
        'down': [u + d + 2 * w, v, u + d + w, v + d],
    }
    if mirror:
        f['east'], f['west'] = f['west'], f['east']
        for k in f:
            a = f[k]
            f[k] = [a[2], a[1], a[0], a[3]]
    return {k: {'uv': a, 'texture': 0} for k, a in f.items()}


def cube(name, frm, to, uv, mirror=False):
    w, h, d = to[0] - frm[0], to[1] - frm[1], to[2] - frm[2]
    return {'name': name, 'box_uv': False, 'rescale': False, 'locked': False, 'render_order': 'default',
            'allow_mirror_modeling': True, 'from': frm, 'to': to, 'autouv': 0, 'color': 0, 'origin': [0, 0, 0],
            'faces': faces(uv[0], uv[1], w, h, d, mirror), 'type': 'cube', 'uuid': uid()}


def build_bbmodel(path):
    elements, groups = [], {}

    def group(name, origin):
        g = {'name': name, 'origin': origin, 'rotation': [0, 0, 0], 'color': 0, 'uuid': uid(), 'export': True,
             'mirror_uv': False, 'isOpen': True, 'locked': False, 'visibility': True, 'autouv': 0, 'children': []}
        groups[name] = g
        return g

    def add(g, el):
        elements.append(el)
        g['children'].append(el['uuid'])

    # Front is north (-z), the zombie's right is +x. Names avoid BetterModel's "h_" head prefix, so the animation alone
    # moves the head (the model is frozen while it plays anyway).
    root = group('root', [0, 0, 0])
    torso = group('torso', [0, 12, 0])
    head = group('skull', [0, 24, 0])
    rarm = group('right_arm', [5, 22, 0])
    larm = group('left_arm', [-5, 22, 0])
    rleg = group('right_leg', [1.9, 12, 0])
    lleg = group('left_leg', [-1.9, 12, 0])
    add(torso, cube('body', [-4, 12, -2], [4, 24, 2], (16, 16)))
    add(head, cube('head', [-4, 24, -4], [4, 32, 4], (0, 0)))
    add(rarm, cube('arm', [4, 12, -2], [8, 24, 2], (40, 16)))
    add(larm, cube('arm', [-8, 12, -2], [-4, 24, 2], (40, 16), mirror=True))
    add(rleg, cube('leg', [-0.1, 0, -2], [3.9, 12, 2], (0, 16)))
    add(lleg, cube('leg', [-3.9, 0, -2], [0.1, 12, 2], (0, 16), mirror=True))

    def node(g, kids):
        return {'uuid': g['uuid'], 'isOpen': True, 'children': list(g['children']) + kids}

    outliner = [node(root, [node(torso, [node(head, []), node(rarm, []), node(larm, [])]), node(rleg, []), node(lleg, [])])]

    animators = {}
    for key, frames in KEYS.items():
        bone, ch = key.split('.')
        g = groups['skull' if bone == 'head' else bone]
        a = animators.setdefault(g['uuid'], {'name': g['name'], 'type': 'bone', 'keyframes': []})
        for t, (x, y, z) in frames:
            if ch == 'rot':
                # BetterModel turns animation rotations the other way round in X and Y (seen in game: the model leaned
                # back where it should lean forward), so they are flipped here; the client mod uses KEYS as written.
                x, y = -x, -y
            a['keyframes'].append({'channel': 'position' if ch == 'pos' else 'rotation',
                                   'data_points': [{'x': str(x), 'y': str(y), 'z': str(z)}], 'uuid': uid(), 'time': t,
                                   'color': -1, 'interpolation': 'catmullrom'})
    anim = {'uuid': uid(), 'name': 'rise', 'loop': 'hold', 'override': False, 'length': LENGTH, 'snapping': 24,
            'selected': False, 'anim_time_update': '', 'blend_weight': '', 'start_delay': '', 'loop_delay': '',
            'animators': animators}

    png = base64.b64encode(open(TEX, 'rb').read()).decode()
    texture = {'name': 'zombie.png', 'path': '', 'folder': '', 'namespace': '', 'id': '0', 'width': 64, 'height': 64,
               'uv_width': 64, 'uv_height': 64, 'particle': False, 'render_mode': 'default', 'render_sides': 'auto',
               'frame_time': 1, 'frame_order_type': 'loop', 'frame_order': '', 'frame_interpolate': False,
               'visible': True, 'internal': True, 'saved': True, 'uuid': uid(), 'source': 'data:image/png;base64,' + png}
    model = {'meta': {'format_version': '4.10', 'model_format': 'free', 'box_uv': False}, 'name': 'zombie_rise',
             'model_identifier': '', 'visible_box': [1, 2, 0], 'variable_placeholders': '',
             'variable_placeholder_buttons': [], 'timeline_setups': [], 'unhandled_root_fields': {},
             'resolution': {'width': 64, 'height': 64}, 'elements': elements, 'groups': list(groups.values()),
             'outliner': outliner, 'textures': [texture], 'animations': [anim]}
    with open(path, 'w') as f:
        json.dump(model, f)


def build_java(path):
    lines = ['package com.mzcod.tacticalmovement.client.animation;', '',
             '// GENERATED by tools/models/zombie_rise.py: do not edit, change the keyframes there and re-run it.',
             '// The zombie spawn "dig up" keyframes, shared with the server\'s BetterModel zombie_rise model. Each row is',
             '// {time s, x, y, z}; rotations in degrees (Blockbench: +X = arm forward/up, head back), positions in pixels (y up).',
             'public final class ZombieRiseKeys {',
             '\tpublic static final float LENGTH = %sF;' % LENGTH, '']
    for key, frames in KEYS.items():
        name = key.replace('.', '_').upper()
        rows = ', '.join('{%sF, %sF, %sF, %sF}' % (t, x, y, z) for t, (x, y, z) in frames)
        lines.append('\tpublic static final float[][] %s = {%s};' % (name, rows))
    lines += ['', '\tprivate ZombieRiseKeys() {', '\t}', '}', '']
    with open(path, 'w', newline='\n') as f:
        f.write('\n'.join(lines))


if __name__ == '__main__':
    build_bbmodel(sys.argv[1])
    build_java(sys.argv[2])
    print('wrote', sys.argv[1], 'and', sys.argv[2])
