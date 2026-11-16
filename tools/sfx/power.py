"""Power-on sounds for MinecraftZombies (electric / zappy), built with the helpers in synth.py.

Usage: python tools/sfx/power.py <output dir>
Writes power_on.ogg (the big one: a charge whine, a crackling arc snap, then a buzzing mains hum with sparks dying away)
and zap_1..4.ogg (short arcs, one per extra lightning strike). Seeded, so the same each run.
"""
import math, os, random, sys

out_dir = sys.argv[1] if len(sys.argv) > 1 else '.'
sys.argv = [sys.argv[0], out_dir]
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import synth as S  # noqa: E402  (reads its output dir from argv)

n, zeros, noise, biquad, env, mul, add = S.n, S.zeros, S.noise, S.biquad, S.env, S.mul, S.add


def buzz(sec, f, rng, harsh=1.0):
    """Mains buzz: a bright, clipped sawtooth at f with a little wobble (the hum of a big transformer)."""
    L = n(sec)
    out = [0.0] * L
    ph = 0.0
    for i in range(L):
        wob = 1 + 0.004 * math.sin(2 * math.pi * 5.3 * i / S.SR)
        ph += f * wob / S.SR
        saw = 2 * (ph % 1.0) - 1
        out[i] = saw + 0.35 * math.sin(2 * math.pi * f * 2 * i / S.SR)
    out = S.drive(out, 2.5 * harsh)
    return biquad(out, 'hp', 90)


def arc(sec, rng, density=900, bright=3000):
    """An electric arc: dense crackle (sparse loud ticks over fizzing noise), all high and hissy, flickering."""
    L = n(sec)
    fizz = biquad(biquad(noise(sec, rng), 'hp', bright), 'lp', 14000)
    # flicker: the arc's loudness jumps around, in bursts
    flick = [0.0] * L
    level = 0.0
    for i in range(L):
        if i % 220 == 0:
            level = rng.random() ** 2.2
        flick[i] = level
    fizz = mul(fizz, flick)
    ticks = S.clicks(sec, density, rng, width=0.0004, amp=(0.4, 1.0))
    ticks = biquad(ticks, 'hp', 1200)
    x = [a * 0.9 + b * 0.8 for a, b in zip(fizz, ticks)]
    return S.drive(x, 1.8)


def snap(rng, gain=1.0):
    """The crack of the arc striking: a very short, very loud broadband hit with a bright ring."""
    sec = 0.18
    x = noise(sec, rng)
    x = mul(x, env(n(sec), 0.0005, 0.06, 3.0))
    ring = mul(S.sine_sweep(sec, 4200, 2600), env(n(sec), 0.0005, 0.12, 2.0))
    body = mul(biquad(noise(sec, rng), 'lp', 600), env(n(sec), 0.001, 0.09, 2.5))
    y = [a * 1.0 + b * 0.35 + c * 0.8 for a, b, c in zip(x, ring, body)]
    return [v * gain for v in S.drive(y, 3.0)]


def power_on():
    rng = random.Random(4100)
    out = zeros(3.6)
    # 1. Charging: a whine rising fast, with the buzz coming up under it
    whine = S.sine_sweep(0.75, 180, 2400, curve=1.8)
    whine = [math.copysign(abs(v) ** 0.7, v) for v in whine]  # a bit squarer: more electric
    add(out, mul(whine, env(n(0.75), 0.4, 0.35, 0.5)), 0.0, 0.32)
    add(out, mul(buzz(0.75, 60, rng, 0.8), env(n(0.75), 0.7, 0.05)), 0.0, 0.25)
    # 2. The strike: a big snap and a burst of arcing
    add(out, snap(rng, 1.0), 0.72, 1.0)
    add(out, mul(arc(0.55, rng, 1600, 2200), env(n(0.55), 0.002, 0.5, 1.4)), 0.72, 0.85)
    # 3. Live: the mains buzz on, steady then dying away, with sparks and little arcs spitting off it
    hum_sec = 2.8
    hum = buzz(hum_sec, 60, rng, 1.0)
    hum = [a + 0.5 * b for a, b in zip(hum, buzz(hum_sec, 120, rng, 0.6))]
    add(out, mul(hum, env(n(hum_sec), 0.02, hum_sec - 0.02, 1.6)), 0.78, 0.42)
    t = 0.95
    while t < 3.0:
        sec = rng.uniform(0.04, 0.16)
        g = 0.55 * (1 - (t - 0.95) / 2.2)
        add(out, mul(arc(sec, rng, rng.uniform(600, 1400)), env(n(sec), 0.001, sec, 1.5)), t, g)
        if rng.random() < 0.3:
            add(out, snap(rng, 0.35 * g / 0.55), t, 1.0)
        t += rng.uniform(0.06, 0.32)
    out = biquad(out, 'hp', 45)
    S.write('power_on', S.reverb(out, wet=0.18, size=0.9, tail=0.8), 0.92)


def zap(seed):
    """A short arc: snap, a burst of crackle, a buzz tail."""
    rng = random.Random(seed)
    out = zeros(0.9)
    add(out, snap(rng, rng.uniform(0.8, 1.0)), 0.0, 1.0)
    sec = rng.uniform(0.25, 0.45)
    add(out, mul(arc(sec, rng, rng.uniform(1100, 1800), rng.uniform(1800, 3200)), env(n(sec), 0.002, sec, 1.3)), 0.005, 0.8)
    hs = rng.uniform(0.35, 0.6)
    add(out, mul(buzz(hs, rng.choice([60, 75, 90]), rng, 0.9), env(n(hs), 0.01, hs, 2.0)), 0.02, 0.3)
    S.write('zap_%d' % (seed - 4200), S.reverb(biquad(out, 'hp', 60), wet=0.15, size=0.8, tail=0.5), 0.9)


if __name__ == '__main__':
    power_on()
    for s in range(4201, 4205):
        zap(s)
