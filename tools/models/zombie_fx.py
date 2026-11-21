"""
Weather effects for the zombies: three overlay textures in the vanilla zombie layout (64x64), laid over the skin.

  snow  - snow settled on the head, shoulders and arms, patchy down the front, none on the lower legs
  wet   - rain: a darkening, glossy droplets and long streaks running down
  dust  - sand / dust from a desert or badlands, heavier towards the feet

    python -I tools/models/zombie_fx.py <textures dir>     -> zombie_fx_snow.png, zombie_fx_wet.png, zombie_fx_dust.png

The client mod draws one over the zombie, fading it in and out (ZombieFx); for players without the mod, the server's models
are baked with the same overlay (zombie_body.py --looks: mz_zombie_N_snow / _wet / _dust).
"""
import os, random, struct, sys, zlib

W = H = 64
EFFECTS = ('snow', 'wet', 'dust')
# The boxes of the vanilla zombie texture: (u, v, width, height, depth); the arms and legs of both sides share a box
BOXES = ((0, 0, 8, 8, 8), (16, 16, 8, 12, 4), (40, 16, 4, 12, 4), (0, 16, 4, 12, 4))
HEAD, BODY, ARM, LEG = range(4)


def faces(box):
    """(name, x, y, w, h) of the six faces of a box in the texture."""
    u, v, w, h, d = box
    return (('north', u + d, v + d, w, h), ('east', u, v + d, d, h), ('south', u + 2 * d + w, v + d, w, h),
            ('west', u + d + w, v + d, d, h), ('up', u + d, v, w, d), ('down', u + d + w, v, w, d))


def noise(x, y, s):
    n = (x * 374761393 + y * 668265263 + s * 2147483647) & 0xffffffff
    n = ((n ^ (n >> 13)) * 1274126177) & 0xffffffff
    return ((n ^ (n >> 16)) & 0xffff) / 65535.0


def overlay(kind):
    """The overlay as rows of (r, g, b, a)."""
    px = [[(0, 0, 0, 0)] * W for _ in range(H)]
    seed = EFFECTS.index(kind) + 1
    for part, box in enumerate(BOXES):
        for name, fx, fy, fw, fh in faces(box):
            for j in range(fh):
                for i in range(fw):
                    x, y = fx + i, fy + j
                    r = noise(x, y, seed)
                    r2 = noise(x, y, seed + 10)
                    row = j / max(1, fh - 1)  # 0 at the top of a side face, 1 at the bottom
                    side = name not in ('up', 'down')
                    if kind == 'snow':
                        if name == 'down':
                            continue
                        if name == 'up':
                            p = 0.92
                        else:
                            p = 0.5 * (1 - row) ** 1.6 + 0.07
                            if part == LEG:
                                p *= 0.5 if row < 0.4 else 0.0
                            if part == HEAD and name == 'north':
                                p = 0.9 if j < 1 else (0.06 if j > 5 else 0.0)  # the brow, not the eyes and mouth
                            if part == ARM:
                                p = p * 1.2 + (0.25 if name == 'up' else 0)
                        if r < p:
                            c = ((244, 248, 255), (222, 232, 248), (196, 212, 236))[int(r2 * 3) % 3 if name != 'up' else 0 if r2 < 0.7 else 1]
                            px[y][x] = c + (255,)
                    elif kind == 'wet':
                        if name == 'up' or not side:
                            a = 70 + int(r2 * 25)
                        else:
                            a = 55 + int(r2 * 30)
                        px[y][x] = (20, 34, 56, a)
                        if side and noise(x, 0, seed + 3) < 0.22:
                            # a streak of water running down this column
                            px[y][x] = (24, 40, 66, 120 + int(r2 * 40))
                        if r < 0.06:
                            px[y][x] = (190, 214, 236, 170)  # a droplet catching the light
                    else:  # dust
                        low = row if side else 0.3
                        if part == LEG:
                            low = 0.5 + row * 0.5
                        base = 34 + int(low * 40)
                        px[y][x] = (196, 164, 112, base)
                        if r < 0.1 + 0.5 * low * low:
                            px[y][x] = (206, 176, 120, 150 + int(r2 * 70))
    # the face keeps its eyes and mouth: nothing on those texels of the face
    if kind != 'wet':
        for y in range(10, 13):
            for x in range(9, 15):
                px[y][x] = (0, 0, 0, 0)
    return px


# ------------------------------------------------------------------------------------------------------------- PNG io

def png(px):
    raw = b''.join(b'\0' + b''.join(bytes(c) for c in row) for row in px)

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def unpng(data):
    """Rows of (r, g, b, a) from one of our own 8-bit RGBA PNGs (zombie_skins.py, this file: filter 0 on every row)."""
    pos, idat = 8, b''
    while pos < len(data):
        n, kind = struct.unpack('>I4s', data[pos:pos + 8])
        if kind == b'IHDR':
            w, h, depth, ctype = struct.unpack('>IIBB', data[pos + 8:pos + 18])
            assert (w, h, depth, ctype) == (W, H, 8, 6), (w, h, depth, ctype)
        elif kind == b'IDAT':
            idat += data[pos + 8:pos + 8 + n]
        pos += 12 + n
    raw = zlib.decompress(idat)
    stride = 1 + W * 4
    rows = []
    for y in range(H):
        assert raw[y * stride] == 0, 'filtered PNG'
        row = raw[y * stride + 1:(y + 1) * stride]
        rows.append([tuple(row[i:i + 4]) for i in range(0, len(row), 4)])
    return rows


def bake(skin_png, kind):
    """The skin with the effect laid on it (what the server's models carry)."""
    skin, fx = unpng(skin_png), overlay(kind)
    out = []
    for y in range(H):
        row = []
        for x in range(W):
            s, o = skin[y][x], fx[y][x]
            a = o[3] / 255.0
            if s[3] == 0 or a == 0:
                row.append(s)
            else:
                row.append(tuple(int(s[k] * (1 - a) + o[k] * a) for k in range(3)) + (s[3],))
        out.append(row)
    return png(out)


if __name__ == '__main__':
    for kind in EFFECTS:
        p = os.path.join(sys.argv[1], 'zombie_fx_%s.png' % kind)
        with open(p, 'wb') as f:
            f.write(png(overlay(kind)))
        print('wrote', p)
