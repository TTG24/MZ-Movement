"""Procedural gore / cutscene sound effects for the tactical mod (pure Python, mono 44.1 kHz -> ogg via ffmpeg).

Usage (from the project root; needs Python 3 and ffmpeg on PATH):
    python tools/sfx/synth.py
Regenerates every sound into src/main/resources/assets/tacticalmovement/sounds/cutscene (seeded, so the same each run).
After adding a new sound, list it in src/main/resources/assets/tacticalmovement/sounds.json and rebuild the mod.
"""
import math, os, random, struct, subprocess, sys, wave

SR = 44100
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main', 'resources', 'assets', 'tacticalmovement', 'sounds', 'cutscene')
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'wav')
os.makedirs(TMP, exist_ok=True)
os.makedirs(OUT, exist_ok=True)


def n(sec):
    return int(sec * SR)


def zeros(sec):
    return [0.0] * n(sec)


def noise(sec, rng):
    return [rng.uniform(-1, 1) for _ in range(n(sec))]


def biquad(x, kind, f, q=0.707, gain_db=0.0):
    w = 2 * math.pi * f / SR
    c, s = math.cos(w), math.sin(w)
    a = s / (2 * q)
    if kind == 'lp':
        b0, b1, b2 = (1 - c) / 2, 1 - c, (1 - c) / 2
        a0, a1, a2 = 1 + a, -2 * c, 1 - a
    elif kind == 'hp':
        b0, b1, b2 = (1 + c) / 2, -(1 + c), (1 + c) / 2
        a0, a1, a2 = 1 + a, -2 * c, 1 - a
    elif kind == 'bp':
        b0, b1, b2 = a, 0, -a
        a0, a1, a2 = 1 + a, -2 * c, 1 - a
    else:
        raise ValueError(kind)
    b0, b1, b2, a1, a2 = b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0
    y = [0.0] * len(x)
    x1 = x2 = y1 = y2 = 0.0
    for i, v in enumerate(x):
        o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, v, y1, o
        y[i] = o
    return y


def sweep_bp(x, f0, f1, q):
    """Band-pass whose centre glides from f0 to f1 (state-variable filter)."""
    y = [0.0] * len(x)
    low = band = 0.0
    L = len(x)
    for i, v in enumerate(x):
        f = f0 * (f1 / f0) ** (i / L)
        k = 2 * math.sin(math.pi * min(f, SR / 6) / SR)
        low += k * band
        high = v - low - band / q
        band += k * high
        y[i] = band
    return y


def env(L, attack, decay, curve=1.0):
    a, d = n(attack), n(decay)
    e = [0.0] * L
    for i in range(L):
        if i < a:
            e[i] = i / max(a, 1)
        else:
            t = (i - a) / max(d, 1)
            e[i] = max(0.0, 1 - t) ** curve
    return e


def mul(x, e):
    return [a * b for a, b in zip(x, e)]


def add(dst, src, at=0.0, gain=1.0):
    o = n(at)
    if o + len(src) > len(dst):
        dst.extend([0.0] * (o + len(src) - len(dst)))
    for i, v in enumerate(src):
        dst[o + i] += v * gain
    return dst


def sine_sweep(sec, f0, f1, curve=1.0):
    L = n(sec)
    out = [0.0] * L
    ph = 0.0
    for i in range(L):
        t = (i / L) ** curve
        f = f0 + (f1 - f0) * t
        ph += 2 * math.pi * f / SR
        out[i] = math.sin(ph)
    return out


def clicks(sec, rate, rng, width=0.0008, amp=(0.3, 1.0)):
    """Random short impulses: crackle / crunch grain."""
    L = n(sec)
    out = [0.0] * L
    t = 0.0
    while True:
        t += rng.expovariate(rate)
        i = n(t)
        if i >= L:
            break
        w = max(2, n(width * rng.uniform(0.5, 1.5)))
        g = rng.uniform(*amp) * (1 if rng.random() < 0.5 else -1)
        for k in range(w):
            if i + k < L:
                out[i + k] += g * (1 - k / w) * rng.uniform(0.6, 1)
    return out


def drive(x, k):
    return [math.tanh(v * k) / math.tanh(k) for v in x]


def reverb(x, wet=0.3, size=1.0, tail=1.2):
    """Small Schroeder reverb (combs + allpasses)."""
    x = x + [0.0] * n(tail)
    L = len(x)
    combs = [int(d * size) for d in (1557, 1617, 1491, 1422)]
    out = [0.0] * L
    for d in combs:
        buf = [0.0] * d
        j = 0
        fb = 0.8
        lp = 0.0
        for i in range(L):
            o = buf[j]
            lp = o * 0.6 + lp * 0.4
            buf[j] = x[i] + lp * fb
            j = (j + 1) % d
            out[i] += o * 0.25
    for d in (225, 556):
        buf = [0.0] * d
        j = 0
        for i in range(L):
            b = buf[j]
            v = out[i]
            o = -v + b
            buf[j] = v + b * 0.5
            j = (j + 1) % d
            out[i] = o
    return [a * (1 - wet) + b * wet for a, b in zip(x, out)]


def fade_out(x, sec=0.03):
    f = n(sec)
    L = len(x)
    for i in range(max(0, L - f), L):
        x[i] *= (L - i) / f
    return x


def write(name, x, peak=0.9):
    x = fade_out(list(x))
    m = max(1e-9, max(abs(v) for v in x))
    x = [v / m * peak for v in x]
    w = os.path.join(TMP, name + '.wav')
    with wave.open(w, 'wb') as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(SR)
        f.writeframes(b''.join(struct.pack('<h', int(max(-1, min(1, v)) * 32767)) for v in x))
    o = os.path.join(OUT, name + '.ogg')
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', w, '-ac', '1', '-c:a', 'libvorbis', '-q:a', '5', o], check=True)
    print(name, round(len(x) / SR, 2), 's')


# ------------------------------------------------------------------ sounds

def squelch(sec, rng, f0=300, f1=120):
    """Wet slurp: narrow resonant band gliding down, wobbling."""
    s = sweep_bp(noise(sec, rng), f0, f1, 6)
    wob = [0.6 + 0.4 * math.sin(2 * math.pi * rng.uniform(9, 16) * i / SR) for i in range(len(s))]
    return mul(mul(s, wob), env(len(s), 0.02, sec - 0.02, 1.5))


def flesh_tear(seed):
    rng = random.Random(seed)
    sec = rng.uniform(0.85, 1.1)
    out = zeros(sec)
    # The rip: stuttering crackle that builds then lets go
    cr = clicks(sec, 900, rng, 0.0006)
    cr = biquad(biquad(cr, 'hp', 900), 'lp', 6000)
    e = [0.0] * len(cr)
    L = len(cr)
    for i in range(L):
        t = i / L
        e[i] = (t / 0.55) ** 2 if t < 0.55 else math.exp(-(t - 0.55) * 9)
        e[i] *= 0.6 + 0.4 * (rng.random() < 0.7)
    add(out, mul(cr, e), gain=0.9)
    # Fibrous tearing noise (band-passed, rising)
    tn = sweep_bp(noise(sec, rng), 700, 2200, 3)
    add(out, mul(tn, e), gain=0.5)
    # Wet squelches through it and at the end
    add(out, squelch(0.45, rng, 420, 140), at=sec * 0.15, gain=0.8)
    add(out, squelch(0.4, rng, 380, 110), at=sec * 0.52, gain=1.0)
    # The snap free: thick low pop
    pop = mul(sine_sweep(0.18, 160, 50), env(n(0.18), 0.002, 0.17, 2))
    add(out, pop, at=sec * 0.55, gain=0.8)
    return reverb(out, 0.12, 0.7, 0.3)


def bubbles(sec, rate, rng, f=(300, 1400)):
    """Wet: lots of tiny bubble pops (short sine chirps that rise as they die), the sound of liquid and wet tissue."""
    L = n(sec)
    out = [0.0] * L
    t = 0.0
    while True:
        t += rng.expovariate(rate)
        i = n(t)
        if i >= L:
            break
        f0 = rng.uniform(*f)
        d = rng.uniform(0.004, 0.018)
        m = n(d)
        g = rng.uniform(0.3, 1.0)
        ph = 0.0
        for k in range(m):
            if i + k >= L:
                break
            u = k / m
            ph += 2 * math.pi * f0 * (1 + 1.5 * u) / SR
            out[i + k] += g * math.sin(ph) * math.exp(-5 * u)
    return out


def squish(sec, rng, f=900, punch=False):
    """Thick meat being squeezed: dark noise chopped into wet grains, with bubbles through it."""
    x = biquad(biquad(noise(sec, rng), 'lp', f), 'lp', f)
    if punch:
        x = biquad(x, 'hp', 250)
    L = len(x)
    gate = [0.0] * L
    i = 0
    while i < L:
        w = n(rng.uniform(0.006, 0.03))
        g = rng.uniform(0.2, 1.0)
        for k in range(w):
            if i + k < L:
                gate[i + k] = g * math.sin(math.pi * k / w)
        i += w
    x = drive(mul(x, gate), 2.0)
    add(x, bubbles(sec, rng.uniform(90, 160), rng, (700, 2400) if punch else (300, 1400)), gain=0.6 if punch else 0.45)
    # punch: hits at once and dies fast (front-loaded), instead of swelling in
    return mul(x, env(L, 0.003, sec - 0.003, 2.6) if punch else env(L, 0.02, sec - 0.02, 1.3))


def smack(rng, tone=1.0):
    """The wet slap of a mouth closing on flesh: teeth clacking in, a hard meaty thock, the slap."""
    s = biquad(biquad(noise(0.05, rng), 'lp', 5000 * tone), 'hp', 400)
    s = mul(s, env(len(s), 0.0008, 0.045, 3.0))
    add(s, bubbles(0.05, 400, rng, (900, 2600)), gain=0.5)
    t = biquad(biquad(noise(0.01, rng), 'bp', 3200 * tone, 1.5), 'hp', 1800)
    add(s, mul(t, env(len(t), 0.0005, 0.0095, 2.5)), gain=0.7)
    return drive(s, 2.0)


def bite(seed):
    """Teeth into flesh, wet and meaty. Each seed is its own KIND of bite, so they're easy to tell apart:
    0 chomp   - a wet smack, one crunch, meat squished
    1 chew    - jaw working: grinding gristle, squishing between each
    2 tear    - bite and rip: meat stretching and tearing away, wet
    3 crunch  - into cartilage: a hard crackling crunch in a wet mouthful
    4 slurp   - sloppy: thick squishing and bubbling, sucking at the wound
    (5-7 the same kinds with different timing and tone)"""
    rng = random.Random(seed)
    kind = seed % 5
    sec = {0: 0.45, 1: 0.85, 2: 0.9, 3: 0.6, 4: 0.8}[kind] * rng.uniform(0.9, 1.1)
    out = zeros(sec)
    tone = rng.uniform(0.85, 1.2)  # bigger/smaller mouth

    def crunch(at, dur, gain, f, rate, k=2.5):
        g = biquad(clicks(dur, rate, rng, 0.0009, (0.3, 1.0)), 'bp', f * tone, 0.8)
        g = drive(biquad(g, 'lp', 3000), k)
        add(out, mul(g, env(len(g), 0.008, dur - 0.008, 1.2)), at=at, gain=gain)

    add(out, smack(rng, tone), gain=1.2)
    if kind == 0:
        crunch(0.01, 0.1, 0.45, 1000, 2000)
        add(out, squish(0.3, rng, 2860 * tone, True), at=0.03, gain=1.0)
    elif kind == 1:
        at = 0.03
        for c in range(rng.randint(3, 4)):
            d = rng.uniform(0.09, 0.14)
            crunch(at, d, 0.5 - c * 0.07, rng.uniform(600, 1000), rng.uniform(1500, 2400))
            add(out, squish(d * 1.4, rng, 2600 * tone, True), at=at + 0.02, gain=0.8)
            at += d + rng.uniform(0.04, 0.07)
    elif kind == 2:
        crunch(0.01, 0.07, 0.4, 1100, 2000)
        add(out, squish(0.28, rng, 2860 * tone, True), at=0.04, gain=0.9)
        # Pulled away: the meat stretching and tearing, ragged and wet
        tr = biquad(sweep_bp(noise(0.5, rng), 500 * tone, 1600 * tone, 1.5), 'lp', 2500)
        gate = []
        on = 1.0
        for i in range(len(tr)):
            if i % 260 == 0:
                on = 0.3 + 0.7 * (rng.random() < 0.65)
            gate.append(on)
        tr = drive(mul(tr, gate), 2.0)
        add(out, mul(tr, env(len(tr), 0.1, 0.4, 1.3)), at=0.2, gain=0.5)
        add(out, bubbles(0.4, 140, rng), at=0.35, gain=0.4)
    elif kind == 3:
        crunch(0.01, 0.2, 0.75, 1500, 3000, 3.5)
        add(out, squish(0.32, rng, 3120 * tone, True), at=0.05, gain=0.8)
    else:
        add(out, squish(0.38, rng, 2340 * tone, True), at=0.0, gain=1.1)
        add(out, squish(0.3, rng, 2860 * tone, True), at=0.25, gain=0.9)
        add(out, bubbles(0.3, 220, rng, (250, 900)), at=sec - 0.32, gain=0.6)
    # Dry and up front (no room wash), brighter, and squashed a little so it hits
    out = drive(biquad(biquad(out, 'lp', 11000), 'hp', 180), 1.5)
    return reverb(out, 0.025, 0.4, 0.06)


def bone_snap(seed):
    """A bone snapping: one hard, crisp SNAP (a sharp broadband crack with a short woody knock in it, like a thick stick
    or a stalk of celery breaking), a little after-crack or two as it gives, a few fibres crackling, and the wet flesh
    around it. No ringing tones: those made it sound like a machine."""
    rng = random.Random(seed)
    sec = 0.35
    out = zeros(sec)

    def snap(at, gain, length):
        c = noise(length, rng)
        c = biquad(biquad(c, 'hp', 500), 'lp', 7500)
        e = [math.exp(-k / (length * SR * 0.25)) for k in range(len(c))]
        add(out, mul(c, e), at=at, gain=gain)
        # The woody knock inside it (broad, not a ringing tone)
        k = biquad(noise(0.04, rng), 'bp', rng.uniform(380, 600), 0.9)
        add(out, mul(k, env(len(k), 0.0005, 0.039, 3.0)), at=at, gain=gain * 1.6)
        # The weight behind it: a short, dense thwack in the low mids (a hit, not a boom)
        w = biquad(biquad(noise(0.045, rng), 'lp', 900), 'hp', 160)
        add(out, mul(w, env(len(w), 0.0005, 0.044, 3.2)), at=at, gain=gain * 1.3)

    snap(0.0, 1.0, rng.uniform(0.009, 0.013))
    # It gives a little more: one or two smaller cracks, unevenly spaced
    at = 0.0
    for i in range(rng.randint(1, 2)):
        at += rng.uniform(0.018, 0.05)
        snap(at, rng.uniform(0.3, 0.55), rng.uniform(0.003, 0.006))
    # Fibres: a few sparse dry crackles trailing off
    fb = biquad(clicks(0.09, 260, rng, 0.0003, (0.2, 0.8)), 'hp', 1200)
    add(out, mul(fb, env(len(fb), 0.0, 0.09, 2.0)), at=0.012, gain=0.5)
    # The flesh around it, a quick wet crunch
    add(out, squish(0.14, rng, 2400, True), at=0.008, gain=0.3)
    # Squashed hard so it hits
    return drive(reverb(out, 0.04, 0.4, 0.08), 2.6)


def blood_gush(seed):
    rng = random.Random(seed)
    sec = 1.5
    out = zeros(sec)
    # Spurting: low-passed noise pulsing like a heartbeat pump, dying off
    sp = biquad(biquad(noise(sec, rng), 'lp', 1400), 'hp', 120)
    L = len(sp)
    rate = rng.uniform(2.6, 3.4)
    e = [0.0] * L
    for i in range(L):
        t = i / SR
        ph = (t * rate) % 1
        e[i] = (math.exp(-ph * 5) * 0.8 + 0.2) * math.exp(-t * 1.3)
    add(out, mul(sp, e), gain=1.0)
    # Bubbles / gurgle: short rising sine blips
    t = 0.0
    while t < sec - 0.1:
        t += rng.expovariate(28)
        d = rng.uniform(0.01, 0.035)
        f = rng.uniform(180, 700)
        b = mul(sine_sweep(d, f, f * rng.uniform(1.4, 2.2)), env(n(d), 0.002, d - 0.002))
        add(out, b, at=t, gain=rng.uniform(0.15, 0.4) * math.exp(-t * 1.0))
    # Splatter onto the ground
    sl = biquad(clicks(sec, 140, rng, 0.002), 'lp', 2500)
    add(out, mul(sl, env(len(sl), 0.05, sec, 1.5)), gain=0.5)
    return out


def splat(seed):
    rng = random.Random(seed)
    out = zeros(0.5)
    # Wet slap onto the ground
    sl = biquad(noise(0.06, rng), 'lp', 2200)
    add(out, mul(sl, env(len(sl), 0.001, 0.055, 2)), gain=1.0)
    th = mul(sine_sweep(0.14, 120, 55), env(n(0.14), 0.001, 0.13, 2))
    add(out, th, gain=0.9)
    add(out, squelch(0.3, rng, 520, 200), at=0.02, gain=0.7)
    dr = biquad(clicks(0.35, 90, rng, 0.0015), 'lp', 2500)
    add(out, mul(dr, env(len(dr), 0.0, 0.35, 2)), at=0.04, gain=0.4)
    return reverb(out, 0.15, 0.8, 0.3)


def punch(seed):
    rng = random.Random(seed)
    out = zeros(0.6)
    # A heavy blow to the head: sharp transient, deep boom, ringing skull
    tr = biquad(noise(0.02, rng), 'lp', 5000)
    add(out, mul(tr, env(len(tr), 0.0005, 0.019, 2)), gain=1.0)
    boom = mul(sine_sweep(0.4, 110, 32, 0.5), env(n(0.4), 0.002, 0.39, 1.8))
    add(out, boom, gain=1.2)
    body = biquad(noise(0.15, rng), 'lp', 600)
    add(out, mul(body, env(len(body), 0.001, 0.14, 2)), gain=0.9)
    ring = mul(sine_sweep(0.45, 620, 560), env(n(0.45), 0.001, 0.44, 3))
    add(out, ring, gain=0.12)
    return drive(reverb(out, 0.2, 1.0, 0.6), 2.2)


def face_punch(seed):
    rng = random.Random(seed)
    out = zeros(0.7)
    # Fist into flesh: a dull, soft-edged smack, a deep meaty thud, wet squelching as it pulls away
    sl = biquad(noise(0.05, rng), 'lp', 1800)
    add(out, mul(sl, env(len(sl), 0.003, 0.045, 2)), gain=0.6)
    th = mul(sine_sweep(0.2, rng.uniform(95, 115), 50, 0.5), env(n(0.2), 0.003, 0.19, 2))
    add(out, th, gain=0.8)
    body = biquad(noise(0.14, rng), 'lp', 600)
    add(out, mul(body, env(len(body), 0.003, 0.13, 1.8)), gain=0.9)
    add(out, squelch(0.3, rng, rng.uniform(320, 420), 130), at=0.005, gain=1.1)
    add(out, squelch(0.25, rng, rng.uniform(500, 650), 220), at=0.05, gain=0.6)
    sm = biquad(clicks(0.2, 220, rng, 0.0025, (0.2, 0.6)), 'lp', 1500)
    add(out, mul(sm, env(len(sm), 0.01, 0.19, 2)), at=0.02, gain=0.35)
    return reverb(out, 0.08, 0.6, 0.25)


def body_fall(seed):
    rng = random.Random(seed)
    out = zeros(0.9)
    # Hits the ground: thud, then the limbs flop down
    th = mul(sine_sweep(0.3, 85, 38), env(n(0.3), 0.002, 0.29, 1.8))
    add(out, th, gain=1.0)
    no = biquad(noise(0.12, rng), 'lp', 900)
    add(out, mul(no, env(len(no), 0.001, 0.11, 2)), gain=0.8)
    for k in range(3):
        t = 0.08 + k * rng.uniform(0.07, 0.13)
        f = biquad(noise(0.05, rng), 'lp', 1500)
        add(out, mul(f, env(len(f), 0.001, 0.045, 2)), at=t, gain=0.4 / (k + 1))
        add(out, mul(sine_sweep(0.08, 110, 60), env(n(0.08), 0.001, 0.075, 2)), at=t, gain=0.35 / (k + 1))
    gr = biquad(clicks(0.3, 120, rng, 0.0008), 'bp', 2500, 1)
    add(out, mul(gr, env(len(gr), 0.0, 0.3, 2)), at=0.01, gain=0.25)
    return reverb(out, 0.15, 0.9, 0.4)


def drag(seed):
    rng = random.Random(seed)
    sec = 1.4
    out = zeros(sec)
    # The body scraped over the ground in pulls: gritty friction + cloth
    L = n(sec)
    e = [0.0] * L
    pulls = [0.0, 0.48, 0.95]
    for i in range(L):
        t = i / SR
        v = 0.0
        for p in pulls:
            if t >= p:
                u = (t - p) / 0.42
                if u < 1:
                    v = max(v, math.sin(math.pi * u) ** 0.7)
        e[i] = v
    grit = biquad(clicks(sec, 2500, rng, 0.0004, (0.1, 1.0)), 'bp', 1800, 0.7)
    add(out, mul(grit, e), gain=0.8)
    cloth = biquad(biquad(noise(sec, rng), 'bp', 700, 0.8), 'lp', 2500)
    add(out, mul(cloth, e), gain=0.7)
    rum = biquad(noise(sec, rng), 'lp', 180)
    add(out, mul(rum, e), gain=1.2)
    return out


def heartbeat(seed, sec=0.9):
    rng = random.Random(seed)
    out = zeros(sec)
    for t, f, g in ((0.0, 58, 1.0), (0.26, 50, 0.7)):
        b = mul(sine_sweep(0.22, f * 1.4, f * 0.8), env(n(0.22), 0.008, 0.21, 2.2))
        add(out, b, at=t, gain=g)
        no = biquad(noise(0.08, rng), 'lp', 220)
        add(out, mul(no, env(len(no), 0.005, 0.075, 2)), at=t, gain=g * 0.6)
    return drive(biquad(out, 'lp', 300), 1.5)


def tinnitus(seed):
    rng = random.Random(seed)
    sec = 4.5
    L = n(sec)
    out = [0.0] * L
    # Ringing ears after the blow, with a muffled rumble underneath
    for f, g in ((3950, 0.5), (4010, 0.35), (7900, 0.06)):
        ph = rng.uniform(0, 6.28)
        for i in range(L):
            out[i] += math.sin(ph + 2 * math.pi * f * i / SR) * g
    e = env(L, 0.08, sec - 0.08, 1.4)
    out = mul(out, e)
    rum = mul(biquad(biquad(noise(sec, rng), 'lp', 160), 'lp', 160), env(L, 0.3, sec - 0.3, 1.2))
    m = max(abs(v) for v in rum)
    add(out, [v / m for v in rum], gain=0.45)
    return out


def strain(seed):
    rng = random.Random(seed)
    sec = 1.6
    out = zeros(sec)
    # Sinew stretching: stick-slip creak (pulse train with a wandering rate) through a meaty resonance
    L = n(sec)
    x = [0.0] * L
    t = 0.0
    while True:
        u = t / sec
        rate = 35 + 60 * u + 25 * math.sin(t * 7 + seed)
        t += 1 / rate * rng.uniform(0.7, 1.3)
        i = n(t)
        if i >= L:
            break
        x[i] = rng.uniform(0.5, 1) * (1 if rng.random() < 0.5 else -1)
    cr = biquad(biquad(x, 'bp', rng.uniform(380, 520), 5), 'bp', 900, 1.5)
    e = env(L, sec * 0.6, sec * 0.4, 1.2)
    add(out, mul(cr, e), gain=1.0)
    # Wet pulling and little pops of fibres giving way
    add(out, mul(squelch(sec, rng, 260, 340), e), gain=0.5)
    pp = biquad(clicks(sec, 30, rng, 0.0004), 'hp', 1500)
    add(out, mul(pp, e), gain=0.5)
    return drive(out, 1.6)


def flashback(seed):
    rng = random.Random(seed)
    sec = 2.6
    L = n(sec)
    out = [0.0] * L
    # Reverse swell into a bright hit, then a hollow shimmering tail (the flashback)
    sw = biquad(noise(0.9, rng), 'hp', 400)
    sw = mul(sw, [(i / n(0.9)) ** 3 for i in range(n(0.9))])
    sw = biquad(sw, 'lp', 7000)
    add(out, sw, gain=0.7)
    hit = 0.9
    boom = mul(sine_sweep(1.2, 70, 28, 0.4), env(n(1.2), 0.003, 1.19, 1.5))
    add(out, boom, at=hit, gain=1.1)
    shim = [0.0] * n(1.6)
    for f in (880, 1318.5, 1760, 2093, 2637):
        ph = rng.uniform(0, 6.28)
        det = rng.uniform(-3, 3)
        for i in range(len(shim)):
            shim[i] += math.sin(ph + 2 * math.pi * (f + det) * i / SR) * (0.3 + 0.2 * math.sin(i / SR * 13 + f))
    shim = mul(shim, env(len(shim), 0.01, 1.59, 2.2))
    add(out, shim, at=hit, gain=0.12)
    wh = biquad(noise(1.5, rng), 'bp', 2500, 0.8)
    add(out, mul(wh, env(len(wh), 0.005, 1.4, 2.5)), at=hit, gain=0.6)
    return reverb(out, 0.4, 1.4, 1.5)


def dread(seed):
    rng = random.Random(seed)
    sec = 5.0
    L = n(sec)
    out = [0.0] * L
    # Low, uneasy drone that swells as it begins: detuned cluster + breathy wind
    for f in (41.2, 43.65, 61.7, 82.4, 87.3):
        ph = rng.uniform(0, 6.28)
        for i in range(L):
            t = i / SR
            out[i] += math.sin(ph + 2 * math.pi * f * t + 0.5 * math.sin(t * 0.7 * f / 40)) * 0.2
    wind = biquad(biquad(noise(sec, rng), 'bp', 500, 1.2), 'lp', 1200)
    add(out, [v * (0.5 + 0.5 * math.sin(i / SR * 1.3)) for i, v in enumerate(wind)], gain=0.3)
    e = [min(1, (i / L) / 0.4) * (1 if i / L < 0.7 else (1 - i / L) / 0.3) for i in range(L)]
    return drive(mul(out, e), 1.3)


def scream(sec, rng):
    """A distant zombie screech: a rotten, gargling roar with vocal fry, rising and breaking down."""
    L = n(sec)
    out = [0.0] * L
    ph = 0.0
    fry = 0.0
    for i in range(L):
        t = i / L
        f = 150 + 190 * math.sin(math.pi * min(1, t * 1.25)) ** 0.7 - 60 * max(0, t - 0.6)
        f *= 1 + rng.uniform(-0.06, 0.06)  # unsteady, wet throat
        ph += f / SR
        saw = 2 * (ph % 1) - 1
        sub = 1 if (ph % 2) < 1 else -1  # an octave-down growl underneath
        fry += rng.uniform(38, 62) / SR  # vocal fry: the voice crackling in pulses
        pulse = 0.35 + 0.65 * math.exp(-((fry % 1) * 7))
        out[i] = (saw + 0.6 * sub) * pulse + rng.uniform(-0.7, 0.7) * (0.4 + 0.6 * t)
    v = [0.0] * L
    for fc, q, g in ((620, 4, 1.0), (1050, 5, 0.6), (2400, 6, 0.3), (240, 3, 0.6)):
        add(v, biquad(out, 'bp', fc, q), gain=g)
    e = [min(1, (i / L) / 0.12) * min(1, (1 - i / L) / 0.3) for i in range(L)]
    v = drive(mul(v, e), 9)
    # Crushed to a few levels (gritty), then far away
    v = [round(x * 6) / 6 for x in v]
    v = biquad(biquad(v, 'hp', 180), 'lp', 2800)
    return v


def game_over(seed):
    rng = random.Random(seed)
    sec = 6.0
    L = n(sec)
    out = [0.0] * L
    # The GAME OVER sting: huge low hit, a dissonant chord ringing out, a distant bell-like toll
    boom = mul(sine_sweep(2.5, 60, 25, 0.3), env(n(2.5), 0.004, 2.49, 1.4))
    add(out, boom, gain=1.0)
    hitn = biquad(noise(0.4, rng), 'lp', 1200)
    add(out, mul(hitn, env(len(hitn), 0.001, 0.39, 2.5)), gain=0.7)
    chord = [0.0] * L
    for f in (55, 65.4, 77.8, 110, 116.5):
        ph = rng.uniform(0, 6.28)
        for i in range(L):
            t = i / SR
            v = math.sin(ph + 2 * math.pi * f * t)
            chord[i] += (v + 0.3 * math.sin(2 * (ph + 2 * math.pi * f * t))) * 0.18
    chord = biquad(chord, 'lp', 900)
    add(out, mul(chord, env(L, 0.03, sec - 0.03, 1.6)), gain=0.8)
    toll = [0.0] * n(4)
    for f, g in ((220, 1), (220 * 2.76, 0.5), (220 * 5.4, 0.25)):
        for i in range(len(toll)):
            toll[i] += math.sin(2 * math.pi * f * i / SR) * g * math.exp(-i / SR * (1.2 + g))
    add(out, toll, at=0.0, gain=0.22)
    # A scream, far off in the dark
    add(out, scream(1.8, rng), at=1.6, gain=0.2)
    out = drive(reverb(out, 0.35, 1.5, 2.0), 1.4)

    # Static under it, and the whole thing glitching: dropping out, stuttering, bursting into static
    L = len(out)
    st = biquad(noise(L / SR, rng), 'bp', 2200, 0.5)
    fl = [0.5 + 0.5 * abs(math.sin(2 * math.pi * 7.3 * i / SR) * math.sin(2 * math.pi * 0.43 * i / SR)) for i in range(L)]
    m = max(abs(v) for v in out)
    res = [o / m + s_ * f_ * 0.05 * max(0.0, min(1.0, (5.4 - i / SR) / 0.8)) for i, (o, s_, f_) in enumerate(zip(out, st, fl))]
    t = 0.5
    while t < 4.8:
        t += rng.uniform(0.25, 0.8)
        i0 = n(t)
        d = n(rng.uniform(0.03, 0.14))
        kind = rng.random()
        if i0 + d >= L:
            break
        if kind < 0.4:
            # Cut to static
            for k in range(d):
                res[i0 + k] = st[i0 + k] * 0.45
        elif kind < 0.7:
            # Stutter: a scrap repeated
            g = max(1, n(rng.uniform(0.012, 0.03)))
            for k in range(d):
                res[i0 + k] = res[i0 + (k % g)]
        else:
            # Drop out, with a click
            for k in range(d):
                res[i0 + k] *= 0.05
            res[i0] += 0.4
    # Ends with the GAME OVER screen (6 s): the rest of the echo faded out by then
    res = res[:n(5.9)]
    f = n(1.2)
    for i in range(len(res) - f, len(res)):
        res[i] *= (len(res) - i) / f
    return res


def tv_static(seed):
    rng = random.Random(seed)
    sec = 2.6
    L = n(sec)
    # A dead channel: loud hiss, crackle, glitch blips, cutting out and back, then dying away
    hiss = biquad(noise(sec, rng), 'hp', 300)
    out = [0.0] * L
    add(out, hiss, gain=0.55)
    add(out, biquad(clicks(sec, 300, rng, 0.0005), 'hp', 900), gain=0.5)
    t = 0.0
    while t < sec - 0.2:
        t += rng.uniform(0.05, 0.25)
        d = rng.uniform(0.02, 0.07)
        f = rng.choice([440, 880, 1320, 2200, 3100]) * rng.uniform(0.95, 1.05)
        bl = [math.copysign(1, math.sin(2 * math.pi * f * i / SR)) for i in range(n(d))]  # harsh square blip
        add(out, bl, at=t, gain=0.12)
    # Gating: the signal stutters and drops out
    g = [1.0] * L
    t = 0.0
    while t < sec:
        t += rng.uniform(0.06, 0.3)
        d = n(rng.uniform(0.02, 0.09))
        level = rng.choice([0.05, 0.3, 1.4])
        for k in range(n(t), min(L, n(t) + d)):
            g[k] = level
    e = [min(1, (i / L) / 0.02) * max(0.0, 1 - (i / L)) ** 1.5 for i in range(L)]
    out = [o * gg * ee for o, gg, ee in zip(out, g, e)]
    return biquad(out, 'lp', 7000)


def write_all():
    for i in range(3):
        write(f'flesh_tear{i + 1}', flesh_tear(100 + i))
    for i in range(8):
        write(f'bite{i + 1}', bite(200 + i))
    for i in range(3):
        write(f'bone_snap{i + 1}', bone_snap(300 + i))
    for i in range(2):
        write(f'blood_gush{i + 1}', blood_gush(400 + i))
    for i in range(3):
        write(f'splat{i + 1}', splat(500 + i))
    for i in range(2):
        write(f'punch{i + 1}', punch(600 + i))
    for i in range(2):
        write(f'body_fall{i + 1}', body_fall(700 + i))
    for i in range(4):
        write(f'face_punch{i + 1}', face_punch(1500 + i))
    for i in range(2):
        write(f'drag{i + 1}', drag(800 + i))
    write('heartbeat', heartbeat(900))
    write('tinnitus', tinnitus(1000), 0.5)
    for i in range(2):
        write(f'strain{i + 1}', strain(1100 + i))
    write('flashback', flashback(1200))
    write('dread', dread(1300))
    write('game_over', game_over(1400))
    write('tv_static', tv_static(1600))


if __name__ == '__main__':
    write_all()
