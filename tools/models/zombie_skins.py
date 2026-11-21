"""The five arena zombie skins: 1940s soldiers long dead, in dark worn-out uniforms, the skin grey-green and rotting with dark
veins, sores and bone showing through. Plain uniforms on purpose: no badges, insignia or flags.

Usage: python -I tools/models/zombie_skins.py <out dir>   -> zombie_1.png .. zombie_5.png (64x64)

Layout: the vanilla 64x64 humanoid one (what mz_zombie.bbmodel's UVs use): head (0,0), hat layer (32,0), body (16,16),
right arm (40,16) and right leg (0,16); the model mirrors the arm and leg for the left side. Rows on an arm or a leg run
from the shoulder / hip (0) down to the hand / sole (11): mz_zombie cuts them at 6 (elbow / knee) and 10 (hand / foot).

  1 rifleman   tunic with four pockets, belt and ammo pouches, side cap, puttees over the boots
  2 greatcoat  long coat to the knees, two rows of buttons, steel helmet
  3 officer    darker tunic, cross strap, peaked cap, breeches and tall boots
  4 torn       tunic ripped open on the ribs and a gut wound, sleeves torn off, bare head with patchy hair
  5 bandaged   helmet with chin strap, a bandage over one eye, scarf, rolled-up sleeves
"""
import os, struct, sys, zlib

W = H = 64

# ------------------------------------------------------------------------------------------------------------- colour


def noise(x, y, s=0):
    """0..1, the same for the same pixel (every skin is the same each time it is made)."""
    n = (x * 374761393 + y * 668265263 + s * 1442695041) & 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c[:3]) + (c[3] if len(c) > 3 else 255,)


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


def grain(c, x, y, s, amount=0.12):
    return shade(c, 1 - amount / 2 + amount * noise(x, y, s))


CLEAR = (0, 0, 0, 0)
FLESH = (98, 106, 86)
FLESH_DARK = (66, 72, 58)
BRUISE = (88, 74, 90)
VEIN = (52, 46, 72)
SORE = (104, 38, 34)
RAW = (128, 28, 30)
BONE = (196, 188, 158)
BONE_DARK = (150, 140, 112)
TOOTH = (206, 196, 160)
SOCKET = (20, 16, 18)
MOUTH = (44, 14, 16)
HAIR = (34, 30, 26)
BLOOD = (84, 16, 18)
DIRT = (62, 56, 44)

# ------------------------------------------------------------------------------------------------------------- layout


class Box:
    """One body part's box in the texture: w x h x d at (u, v)."""

    def __init__(self, name, u, v, w, h, d):
        self.name, self.u, self.v, self.w, self.h, self.d = name, u, v, w, h, d

    def texels(self):
        """(x, y, face, col, row, face width) for every texel; row is down the part on the sides, col across the face
        from its left as seen from outside."""
        u, v, w, h, d = self.u, self.v, self.w, self.h, self.d
        sides = (('east', u, d), ('north', u + d, w), ('west', u + d + w, d), ('south', u + 2 * d + w, w))
        for face, x0, fw in sides:
            for col in range(fw):
                for row in range(h):
                    yield x0 + col, v + d + row, face, col, row, fw
        for face, x0 in (('up', u + d), ('down', u + d + w)):
            for col in range(w):
                for row in range(d):
                    yield x0 + col, v + row, face, col, row, w


HEAD = Box('head', 0, 0, 8, 8, 8)
HAT = Box('hat', 32, 0, 8, 8, 8)
BODY = Box('body', 16, 16, 8, 12, 4)
ARM = Box('arm', 40, 16, 4, 12, 4)
LEG = Box('leg', 0, 16, 4, 12, 4)


# --------------------------------------------------------------------------------------------------------- the dead


def skin(x, y, s, rot=0.0):
    """Rotting skin: grey-green, blotched dark and purple, with sores; rot 0..1 makes it worse."""
    n = noise(x, y, s)
    m = noise(x // 2, y // 2, s + 7)
    c = grain(FLESH, x, y, s, 0.18)
    if m < 0.25 + rot * 0.2:
        c = mix(c, FLESH_DARK, 0.6)
    if m > 0.86 - rot * 0.1:
        c = mix(c, BRUISE, 0.7)
    if n > 0.965 - rot * 0.04:
        c = SORE
    return c


def veins(x, y, s, density=0.5):
    """Thin dark veins: a wandering line wherever the hashed field crosses a band."""
    f = (noise(x // 3, y // 3, s + 11) + noise(x, y // 2, s + 12) * 0.35)
    return abs(f - 0.6) < 0.05 * density + 0.015


def flesh(x, y, s, rot=0.0, vein=0.6):
    c = skin(x, y, s, rot)
    if veins(x, y, s, vein):
        c = mix(c, VEIN, 0.8)
    return c


def cloth(base, x, y, s, wear=0.5):
    """A worn, dirty uniform: grain, darker creases, dirt and the odd blood stain or hole."""
    c = grain(base, x, y, s, 0.16)
    if noise(x, y // 2, s + 3) < 0.12:
        c = shade(c, 0.82)  # creases
    d = noise(x // 2, y // 2, s + 4)
    if d > 0.9 - wear * 0.1:
        c = mix(c, DIRT, 0.45)
    if d < 0.04 * wear:
        c = mix(c, BLOOD, 0.75)
    return c


# ------------------------------------------------------------------------------------------------------------ the face


def face(col, row, x, y, s, style):
    """The front of the head (8x8; eyes at row 2, cols 2 and 5, where the glowing eyes sit)."""
    c = flesh(x, y, s, 0.4, 0.8)
    # Sunken, dark round the eyes
    if row in (1, 2, 3) and col in (1, 2, 3, 4, 5, 6):
        c = mix(c, BRUISE, 0.45 if row != 2 else 0.6)
    if row == 2 and col in (2, 5):
        c = SOCKET
    # Nose: gone, two holes
    if row == 4 and col in (3, 4):
        c = SOCKET if style != 'bandaged' else c
    # Lips rotted back: a row of teeth over a dark mouth
    # (the teeth are 3D, mz_zombie's upper_teeth / lower_teeth: here only the rotted lips round them)
    if row == 5 and 1 <= col <= 6:
        c = mix(MOUTH, SORE, noise(x, y, s + 30) * 0.6)
    if row == 6 and 1 <= col <= 6:
        c = mix(c, BRUISE, 0.5) if col in (1, 6) else mix(SORE, MOUTH, 0.4)
    if row == 7 and 2 <= col <= 5:
        c = mix(c, BLOOD, 0.5)
    # A cheekbone showing through on one side
    if style in ('torn', 'rifleman') and col == 6 and row in (3, 4):
        c = BONE if row == 3 else BONE_DARK
    if style == 'officer' and col == 1 and row in (3, 4):
        c = RAW if row == 4 else SORE
    return c


def head_texel(face_name, col, row, x, y, s, style):
    if face_name == 'north':
        return face(col, row, x, y, s, style)
    c = flesh(x, y, s, 0.35, 0.7)
    if face_name == 'up' or (row <= 1 and face_name != 'down'):
        # Hair: thin and patchy (the hat covers it on the others)
        if noise(x, y, s + 21) > (0.45 if style == 'torn' else 0.25):
            c = grain(HAIR, x, y, s, 0.3)
        elif style == 'torn' and noise(x // 2, y // 2, s + 22) > 0.7:
            c = BONE  # skull showing
    if face_name in ('east', 'west') and row == 3 and col in (3, 4):
        c = mix(c, FLESH_DARK, 0.7)  # ear, rotted down
    return c


# --------------------------------------------------------------------------------------------------------- the styles

TUNIC = (50, 56, 50)
TUNIC_DARK = (38, 41, 40)
COAT = (58, 60, 56)
OFFICER = (36, 38, 42)
TROUSERS = (46, 50, 48)
LEATHER = (52, 38, 28)
BELT = (30, 28, 26)
METAL = (96, 92, 70)
BOOT = (30, 26, 24)
PUTTEE = (70, 66, 52)
HELMET = (60, 64, 58)
BANDAGE = (176, 168, 140)
SCARF = (74, 62, 52)


class Style:
    def __init__(self, name, seed):
        self.name, self.seed = name, seed

    def body(self, f, col, row, x, y, fw):
        s, n = self.seed, self.name
        if n == 'torn':
            c = cloth(TUNIC, x, y, s, 0.9)
            # Ripped open down the front: ribs, then the gut wound
            if f == 'north' and 2 <= col <= 5 and row >= 1:
                if row <= 6:
                    c = BONE if row % 2 == 0 and 2 <= col <= 5 else RAW if noise(x, y, s) < 0.4 else mix(RAW, MOUTH, 0.5)
                    if col in (2, 5) and noise(x, y, s + 1) < 0.5:
                        c = flesh(x, y, s, 0.6)
                elif row <= 9:
                    c = RAW if noise(x, y, s + 2) < 0.6 else BLOOD
            if f == 'north' and col in (1, 6) and row >= 1 and noise(x, y, s + 3) < 0.5:
                c = shade(c, 0.6)  # torn edge
            return c
        base = {'rifleman': TUNIC, 'greatcoat': COAT, 'officer': OFFICER, 'bandaged': TUNIC_DARK}[n]
        c = cloth(base, x, y, s)
        if f in ('up', 'down'):
            return flesh(x, y, s) if f == 'up' and 2 <= col <= 5 and n != 'bandaged' else c
        # Collar open at the throat: rotting neck
        if f == 'north' and row == 0 and 3 <= col <= 4:
            c = flesh(x, y, s, 0.5, 0.9)
        if n == 'bandaged' and row <= 1:
            c = cloth(SCARF, x, y, s + 5, 0.7)
        belt_row = 8
        if n == 'rifleman':
            if f == 'north' and col in (1, 6) and row in (2, 3, 9, 10):
                c = shade(c, 0.8) if row in (2, 9) else shade(c, 1.1)  # pockets
            if f == 'north' and col in (3, 4) and row in (2, 4, 6):
                c = METAL if col == 3 else c  # buttons
            if row == belt_row:
                c = grain(BELT, x, y, s, 0.2)
            if row in (6, 7) and f == 'north' and col in (0, 1, 6, 7):
                c = grain(LEATHER, x, y, s)  # ammo pouches on the belt
            if f == 'north' and col == 6 and row < belt_row:
                c = grain(LEATHER, x, y, s + 9)  # the strap of the bread bag
        elif n == 'greatcoat':
            if f == 'north' and col in (2, 5) and row in (2, 4, 6, 8, 10):
                c = METAL
            if f == 'north' and col in (3, 4) and row >= 1:
                c = shade(c, 0.85)  # where the coat closes
            if row == 0:
                c = shade(cloth(COAT, x, y, s + 6), 0.9)  # turned-up collar
            if row == belt_row - 1:
                c = grain(BELT, x, y, s, 0.2)
        elif n == 'officer':
            # Cross strap from its right shoulder to its left hip, over the belt
            if f == 'north' and abs(col - (row * 6 / 8 + 1)) < 0.6 and row < belt_row:
                c = grain(LEATHER, x, y, s)
            if f == 'south' and abs((fw - 1 - col) - (row * 6 / 8 + 1)) < 0.6 and row < belt_row:
                c = grain(LEATHER, x, y, s)
            if row == belt_row:
                c = METAL if f == 'north' and col in (3, 4) else grain(LEATHER, x, y, s + 1)
            if f == 'north' and col in (3, 4) and row in (2, 4, 6, 10):
                c = METAL if col == 4 else c
        elif n == 'bandaged':
            # Suspenders and a belt
            if f in ('north', 'south') and col in (1, 6) and 2 <= row < belt_row:
                c = grain(LEATHER, x, y, s)
            if row == belt_row:
                c = grain(BELT, x, y, s, 0.2)
        # Below the belt: the tunic's skirt, then the top of the trousers
        return c

    def arm(self, f, col, row, x, y, fw):
        s, n = self.seed, self.name
        sleeve = {'rifleman': TUNIC, 'greatcoat': COAT, 'officer': OFFICER, 'torn': TUNIC, 'bandaged': TUNIC_DARK}[n]
        end = {'rifleman': 9, 'greatcoat': 10, 'officer': 9, 'torn': 3, 'bandaged': 5}[n]
        if f == 'up':
            return cloth(sleeve, x, y, s)
        if f == 'down' or row >= 10:
            # Hands: bony, the fingertips worn to the bone
            c = flesh(x, y, s, 0.6, 1.0)
            if f == 'down' and noise(x, y, s + 4) < 0.4:
                c = BONE_DARK
            if row == 11 and noise(x, y, s + 4) < 0.5:
                c = BONE
            return c
        if row < end:
            c = cloth(sleeve, x, y, s)
            if row == end - 1:
                c = shade(c, 0.75 if n != 'bandaged' else 1.15)  # cuff, or the roll of a rolled sleeve
            return c
        # Bare forearm (and upper arm where the sleeve is torn off): veins and sores
        c = flesh(x, y, s, 0.5, 1.3)
        if n == 'torn' and row == end and noise(x, y, s + 8) < 0.6:
            c = shade(cloth(sleeve, x, y, s), 0.7)  # ragged edge of the sleeve
        if n == 'torn' and f == 'east' and 5 <= row <= 7 and col in (1, 2):
            c = RAW if row != 6 else BONE  # a bite down to the bone
        return c

    def leg(self, f, col, row, x, y, fw):
        s, n = self.seed, self.name
        if f == 'down':
            return grain(BOOT, x, y, s, 0.3)
        if n == 'greatcoat' and row <= 5:
            c = cloth(COAT, x, y, s + 2)
            return shade(c, 0.8) if row == 5 else c  # the coat's hem
        if n == 'officer':
            if row >= 6:
                c = grain(BOOT, x, y, s, 0.25)
                return shade(c, 1.35) if row == 6 else c  # tall boots, the top caught by the light
            c = cloth((54, 52, 46), x, y, s + 2)  # breeches
            return shade(c, 1.08) if f in ('east', 'west') and row in (2, 3) else c
        if row >= 10:
            c = grain(BOOT, x, y, s, 0.25)
            if n == 'torn' and f == 'north' and row == 10 and col == 1:
                c = flesh(x, y, s, 0.8)  # a toe through the boot
            return c
        if n == 'rifleman' and row >= 7:
            c = grain(PUTTEE, x, y, s + 3, 0.2)
            return shade(c, 0.85) if row % 2 == 0 else c  # wound round in bands
        c = cloth(TROUSERS if n != 'bandaged' else (42, 44, 40), x, y, s + 2, 0.8 if n == 'torn' else 0.5)
        if n == 'torn' and f == 'north' and 4 <= row <= 6 and col in (1, 2):
            c = flesh(x, y, s, 0.7) if row != 5 else RAW  # torn knee
        if n == 'bandaged' and f == 'east' and row in (2, 3):
            c = grain(BANDAGE, x, y, s, 0.2) if noise(x, y, s + 5) > 0.2 else BLOOD  # field dressing on the thigh
        return c

    def hat(self, f, col, row, x, y, fw):
        """The hat layer (drawn a little bigger than the head): caps, helmets, the bandage."""
        s, n = self.seed, self.name
        if n == 'rifleman':
            # Side cap: a narrow cap over the top, front to back
            if f == 'up':
                return cloth(TUNIC, x, y, s + 9) if 2 <= col <= 5 else CLEAR
            if f in ('north', 'south') and row == 0 and 2 <= col <= 5:
                return cloth(TUNIC, x, y, s + 9)
            if f in ('east', 'west') and row in (0, 1):
                return cloth(TUNIC, x, y, s + 9) if row == 0 or 1 <= col <= 6 else CLEAR
            return CLEAR
        if n in ('greatcoat', 'bandaged'):
            # Steel helmet: covers the top and down to the brow / ears, a darker rim
            if f == 'up':
                return grain(HELMET, x, y, s + 9, 0.14)
            if f == 'down':
                return CLEAR
            if row <= 1:
                c = grain(HELMET, x, y, s + 9, 0.14)
                if noise(x, y, s + 10) < 0.08:
                    c = shade(c, 0.7)  # dents and rust
                return shade(c, 0.75) if row == 1 else c
            if n == 'greatcoat' and f in ('east', 'west', 'south') and row == 2:
                return shade(grain(HELMET, x, y, s + 9), 0.7)  # the flare over the neck
            if n == 'bandaged':
                if f in ('east', 'west') and col in (3, 4) and 2 <= row <= 6:
                    return grain(LEATHER, x, y, s)  # chin strap
                if f == 'north' and row == 2 and 0 <= col <= 3:
                    return grain(BANDAGE, x, y, s, 0.2)  # bandage over its right eye
                if f in ('north', 'east', 'west', 'south') and row == 3 and (f != 'north' or col <= 4):
                    c = grain(BANDAGE, x, y, s + 1, 0.2)
                    return mix(c, BLOOD, 0.7) if noise(x, y, s + 2) < 0.2 else c
            return CLEAR
        if n == 'officer':
            # Peaked cap: a crown wider than the head, a band, the peak over the eyes
            if f == 'up':
                return cloth(OFFICER, x, y, s + 9)
            if f == 'down':
                return CLEAR
            if row == 0:
                return cloth(OFFICER, x, y, s + 9)
            if row == 1:
                return grain((22, 22, 24), x, y, s + 9, 0.2)  # the band
            if f == 'north' and row == 2:
                return shade(grain(BOOT, x, y, s), 1.3)  # the peak, shiny leather
            return CLEAR
        return CLEAR


STYLES = [Style('rifleman', 11), Style('greatcoat', 23), Style('officer', 37), Style('torn', 41), Style('bandaged', 53)]


def paint(style):
    px = [[CLEAR] * W for _ in range(H)]
    for x, y, f, col, row, fw in HEAD.texels():
        px[y][x] = head_texel(f, col, row, x, y, style.seed, style.name)
    for x, y, f, col, row, fw in HAT.texels():
        px[y][x] = style.hat(f, col, row, x, y, fw)
    for x, y, f, col, row, fw in BODY.texels():
        px[y][x] = style.body(f, col, row, x, y, fw)
    for x, y, f, col, row, fw in ARM.texels():
        px[y][x] = style.arm(f, col, row, x, y, fw)
    for x, y, f, col, row, fw in LEG.texels():
        px[y][x] = style.leg(f, col, row, x, y, fw)
    return px


def png(px):
    raw = b''.join(b'\0' + b''.join(bytes(tuple(c[:3]) + (c[3] if len(c) > 3 else 255,)) for c in row) for row in px)

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def skins():
    """[(name, png bytes)] for skins 1..5."""
    return [(st.name, png(paint(st))) for st in STYLES]


if __name__ == '__main__':
    out = sys.argv[1]
    for i, (name, data) in enumerate(skins(), 1):
        p = os.path.join(out, 'zombie_%d.png' % i)
        with open(p, 'wb') as f:
            f.write(data)
        print('wrote', p, '(%s)' % name)
