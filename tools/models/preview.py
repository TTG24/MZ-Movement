"""Renders a .bbmodel's animations to PNG contact sheets, to check poses without opening the game or Blockbench.

Usage: python -I tools/models/preview.py <model .bbmodel> <out dir> [animation names...]
One sheet per animation: frames left to right over its length, a side view on top and a three-quarter view below, with
the ground line and the entity's position marked. Reads the file the way BetterModel and the mod do (bone pivots, keyframes
with linear / catmullrom / step easing, X and Y rotation stored negated), so it also checks the file itself.
"""
import base64, json, math, os, struct, sys, zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from zombie_body import png  # noqa: E402

FRAMES = 8
CELL = 150
SCALE = 3.2


# ------------------------------------------------------------------------------------------------------- tiny PNG read

def read_png(data):
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    i, idat, w = 8, b'', 0
    while i < len(data):
        n, kind = struct.unpack('>I4s', data[i:i + 8])
        body = data[i + 8:i + 8 + n]
        if kind == b'IHDR':
            w, h, depth, ctype = struct.unpack('>IIBB', body[:10])
            assert depth == 8 and ctype in (2, 6), 'only 8-bit RGB/RGBA'
            bpp = 4 if ctype == 6 else 3
        elif kind == b'IDAT':
            idat += body
        i += 12 + n
    raw = zlib.decompress(idat)
    stride = w * bpp
    rows, prev = [], bytearray(stride)
    for y in range(h):
        f, line = raw[y * (stride + 1)], bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line)
        prev = line
    return w, h, [[tuple(r[x * bpp:x * bpp + 3]) + ((r[x * bpp + 3],) if bpp == 4 else (255,)) for x in range(w)] for r in rows]


# --------------------------------------------------------------------------------------------------------------- math

def mat_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def translate(x, y, z):
    return [[1, 0, 0, x], [0, 1, 0, y], [0, 0, 1, z], [0, 0, 0, 1]]


def scale(x, y, z):
    return [[x, 0, 0, 0], [0, y, 0, 0], [0, 0, z, 0], [0, 0, 0, 1]]


def rot(x, y, z):
    """Blockbench bone rotation (degrees), applied X first, then Y, then Z."""
    x, y, z = (math.radians(a) for a in (x, y, z))
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    rx = [[1, 0, 0, 0], [0, cx, -sx, 0], [0, sx, cx, 0], [0, 0, 0, 1]]
    ry = [[cy, 0, sy, 0], [0, 1, 0, 0], [-sy, 0, cy, 0], [0, 0, 0, 1]]
    rz = [[cz, -sz, 0, 0], [sz, cz, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]
    return mat_mul(rz, mat_mul(ry, rx))


def apply(m, p):
    return tuple(m[i][0] * p[0] + m[i][1] * p[1] + m[i][2] * p[2] + m[i][3] for i in range(3))


# ---------------------------------------------------------------------------------------------------------- the model

class Model:
    def __init__(self, path):
        d = json.load(open(path))
        self.elements = {e['uuid']: e for e in d['elements']}
        self.groups = {g['uuid']: g for g in d['groups']}
        self.parent, self.cubes = {}, {}

        def walk(node, parent):
            self.parent[node['uuid']] = parent
            self.cubes[node['uuid']] = []
            for c in node['children']:
                if isinstance(c, str):
                    self.cubes[node['uuid']].append(self.elements[c])
                else:
                    walk(c, node['uuid'])
        for n in d['outliner']:
            walk(n, None)
        self.textures = []
        for t in d['textures']:
            self.textures.append(read_png(base64.b64decode(t['source'].split(',', 1)[1])) + (t.get('uv_width', 64),))
        self.animations = {a['name']: a for a in d['animations']}

    def channel(self, anim, group_uuid, channel, t, default):
        a = anim['animators'].get(group_uuid) if anim else None
        keys = sorted((k for k in (a['keyframes'] if a else []) if k['channel'] == channel), key=lambda k: k['time'])
        if not keys:
            return default
        vals = [(k['time'], tuple(float(k['data_points'][0][c]) for c in 'xyz'), k['interpolation']) for k in keys]
        if t <= vals[0][0]:
            return vals[0][1]
        if t >= vals[-1][0]:
            return vals[-1][1]
        for i in range(len(vals) - 1):
            t0, v0, interp = vals[i]
            t1, v1, _ = vals[i + 1]
            if t0 <= t <= t1:
                f = (t - t0) / (t1 - t0) if t1 > t0 else 0
                if interp == 'step':
                    return v0
                if interp == 'catmullrom':
                    vp = vals[i - 1][1] if i > 0 else v0
                    vn = vals[i + 2][1] if i + 2 < len(vals) else v1
                    return tuple(0.5 * (2 * b + (-a + c) * f + (2 * a - 5 * b + 4 * c - d) * f * f
                                        + (-a + 3 * b - 3 * c + d) * f ** 3) for a, b, c, d in zip(vp, v0, v1, vn))
                return tuple(a + (b - a) * f for a, b in zip(v0, v1))
        return vals[-1][1]

    def matrices(self, anim, t):
        out = {}

        def m(g):
            if g in out:
                return out[g]
            grp = self.groups[g]
            px, py, pz = grp['origin']
            rx, ry, rz = self.channel(anim, g, 'rotation', t, (0, 0, 0))
            ox, oy, oz = self.channel(anim, g, 'position', t, (0, 0, 0))
            sx, sy, sz = self.channel(anim, g, 'scale', t, (1, 1, 1))
            local = mat_mul(translate(px + ox, py + oy, pz + oz),
                            mat_mul(rot(-rx, -ry, rz), mat_mul(scale(sx, sy, sz), translate(-px, -py, -pz))))
            parent = self.parent[g]
            out[g] = mat_mul(m(parent), local) if parent else local
            return out[g]
        for g in self.groups:
            m(g)
        return out

    def face_colour(self, face):
        w, h, pix, uvw = self.textures[face['texture']]
        k = w / uvw
        u0, v0, u1, v1 = face['uv']
        xs = range(int(min(u0, u1) * k), max(int(min(u0, u1) * k) + 1, int(max(u0, u1) * k)))
        ys = range(int(min(v0, v1) * k), max(int(min(v0, v1) * k) + 1, int(max(v0, v1) * k)))
        cs = [pix[y][x] for y in ys for x in xs if 0 <= y < h and 0 <= x < w and pix[y][x][3] > 0]
        if not cs:
            return None
        return tuple(sum(c[i] for c in cs) // len(cs) for i in range(3))

    def quads(self, anim, t):
        mats = self.matrices(anim, t)
        out = []
        for g, cubes in self.cubes.items():
            for e in cubes:
                inf = e.get('inflate', 0)
                (x0, y0, z0), (x1, y1, z1) = [a - inf for a in e['from']], [a + inf for a in e['to']]
                corners = {
                    'north': [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)],
                    'south': [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)],
                    'east': [(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)],
                    'west': [(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)],
                    'up': [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
                    'down': [(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)],
                }
                for side, face in e['faces'].items():
                    if face.get('texture') is None:
                        continue
                    colour = self.face_colour(face)
                    if colour is None:
                        continue
                    pts = [apply(mats[g], p) for p in corners[side]]
                    if e.get('light_emission'):
                        colour = (255, 150, 30)
                    out.append((pts, colour))
        return out


# ------------------------------------------------------------------------------------------------------------ drawing

def view_side(p):  # seen from the zombie's right (+x): its front (-z) on the left
    return (p[2], p[1], p[0])


def view_three_quarter(p):  # from the front-right, a little above
    a = math.radians(-35)
    x = p[0] * math.cos(a) - p[2] * math.sin(a)
    z = p[0] * math.sin(a) + p[2] * math.cos(a)
    e = math.radians(15)
    y = p[1] * math.cos(e) + z * math.sin(e)
    d = -p[1] * math.sin(e) + z * math.cos(e)
    return (x, y, d)


def draw(img, quads, view, ox, oy):
    """Painter's algorithm: far faces first. Screen: x right, y down; view y is up."""
    projected = []
    for pts, colour in quads:
        v = [view(p) for p in pts]
        # shade by facing (normal from the view's 2D winding area isn't enough; use depth slope + fixed light)
        e1 = [v[1][i] - v[0][i] for i in range(3)]
        e2 = [v[3][i] - v[0][i] for i in range(3)]
        n = (e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
        ln = math.sqrt(sum(a * a for a in n)) or 1
        light = 0.55 + 0.45 * abs(n[1] * 0.6 + n[2] * 0.8) / ln
        c = tuple(min(255, int(ch * light)) for ch in colour)
        projected.append((sum(p[2] for p in v) / 4, [(ox + p[0] * SCALE, oy - p[1] * SCALE) for p in v], c))
    projected.sort(key=lambda q: -q[0])
    for _, poly, c in projected:
        fill(img, poly, c)


def fill(img, poly, c):
    h, w = len(img), len(img[0])
    ys = [p[1] for p in poly]
    for y in range(max(0, int(min(ys))), min(h - 1, int(max(ys)) + 1) + 1):
        xs = []
        for i in range(len(poly)):
            (x0, y0), (x1, y1) = poly[i], poly[(i + 1) % len(poly)]
            if (y0 <= y + 0.5 < y1) or (y1 <= y + 0.5 < y0):
                xs.append(x0 + (y + 0.5 - y0) * (x1 - x0) / (y1 - y0))
        xs.sort()
        for i in range(0, len(xs) - 1, 2):
            for x in range(max(0, int(xs[i] + 0.5)), min(w, int(xs[i + 1] + 0.5))):
                img[y][x] = c


def sheet(model, name, out_dir):
    anim = model.animations[name]
    length = anim['length']
    loop = anim['loop'] == 'loop'
    n = FRAMES
    W, H = CELL * n, CELL * 2
    img = [[(46, 46, 52)] * W for _ in range(H)]
    for i in range(n):
        t = length * i / (n if loop else n - 1)
        quads = model.quads(anim, t)
        for row, view in enumerate((view_side, view_three_quarter)):
            ox, oy = CELL * i + CELL // 2, CELL * row + CELL - 22
            for x in range(CELL * i + 6, CELL * (i + 1) - 6):  # ground
                img[oy][x] = (110, 100, 80)
            for y in range(oy - 3, oy + 4):  # the entity's position
                img[y][ox] = (200, 60, 60)
            draw(img, quads, view, ox, oy)
        for x in range(W):
            img[0][x] = (20, 20, 20)
        for y in range(H):
            img[y][CELL * i] = (20, 20, 20)
    path = os.path.join(out_dir, name + '.png')
    with open(path, 'wb') as f:
        f.write(png(W, H, lambda x, y: img[y][x] + (255,)))
    return path


if __name__ == '__main__':
    model = Model(sys.argv[1])
    os.makedirs(sys.argv[2], exist_ok=True)
    names = sys.argv[3:] or list(model.animations)
    for nm in names:
        print(sheet(model, nm, sys.argv[2]))
