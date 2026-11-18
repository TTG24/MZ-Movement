# Zombie animation reference (v1, before the Blockbench model)

Snapshot of how zombies looked and moved on 2026-11-17, kept so the new model and animations can be checked against it.

| File | What it is |
|---|---|
| `ZombieAnim_v1.java.txt` | The mod's code-posed zombie (vanilla 6-box model): idle / walk / sprint / attack. |
| `ZombieRig_v1.java.txt` | The same pose on the cutscene rig (adds elbow and knee bends). |
| `mz_zombie_v1.bbmodel` | `zombie_body.py` output at this point (open in Blockbench to play every animation). |
| `mz_zombie_live_server.bbmodel` | The copy BetterModel was serving to non-mod players at this point. |

## Body language (all angles radians, vanilla ModelPart: x+ = limb swings back, body leans forward)

Blend weights come from the vanilla walk data: `move = smooth(speed / 0.15)`,
`sprint = smooth((speed - 0.55) / (0.80 - 0.55))`, `walk = move * (1 - sprint)`; `c, s = cos/sin(walkPos * 0.6662)`.

**Idle** - hunched, head hanging and tilted, arms dangling.
- Torso lean 0.22, side roll `sin(t*0.06)*0.05`.
- Arms: right `-0.30 + sin(t*0.07)*0.06`, left `-0.18 + sin(t*0.07+1.7)*0.06`; slightly out (z 0.10 / -0.14).
- Head drop 0.38, roll 0.16 plus a twitch: `sin(t*0.9) * smooth((sin(t*0.045)-0.92)*12) * 0.25` (a shake every ~7 s).
- In Blockbench: 4.5 s loop.

**Walk** - lurching shamble, left leg drags, both arms reaching forward.
- Lean 0.32, roll `s*0.13`, hip twist `sin(walkPos*0.3331)*0.10`.
- Arms reach: right `-1.40 + c*0.28*speed`, left `-1.10 - c*0.34*speed`, turned in (y -0.15 / +0.18).
- Legs: right `c*speed*1.4`, left only `0.62x` of that (the drag). Knee lifts on the forward swing (right 0.28, left 0.12).
- Head drop 0.18, rolls against the body (`-roll*0.7 + 0.10`).
- In Blockbench: two steps, 2.1 s at speed 0.45.

**Sprint** - leaned far forward, arms pumping, long strides.
- Lean 0.62, roll `s*0.06`, twist `c*0.12`.
- Arms pump opposite each other: `-0.35 -/+ c*1.55`, flared out (z +/-0.30), elbows bent more.
- Legs both `1.35x`, knee lift 0.45. Head up (-0.30) to look at the target.
- In Blockbench: two steps, 1.05 s at speed 0.90.

**Attack** (swing 0..1, weight `sin(swing*pi)`) - both arms rear up to -2.8 by 35 %, then claw down to -0.45; arms
turn in (y +/-0.35), lean +0.28, head comes up 0.25. Upper body only; legs keep walking. 0.4 s.

**Rise** - `zombie_rise.py` keys (2.5 s): one hand claws up, then the other, hauls up, steps out, ends in the idle pose.

**Deaths** (non-mod only; the mod uses physics ragdolls): `death_back`, `death_forward`, `death_headshot` (head scales to 0).

## Things the mod does with the model today (must keep working)

- Glowing orange eyes (`ZombieEyesLayer`, `zombie_eyes.png`).
- Corpses: ragdoll limbs, head removed after a headshot kill (`wounds/Corpses.java`).
- Rise animation on spawn (`ZombieRise`), game-over cutscene rig (`cutscene/body/ZombieRig`).
- Debug: `/tacticaledbg zombie on|off`, `/tacticaledbg zombie sprint <from> <to>`.
