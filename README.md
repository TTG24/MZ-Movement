# MZ-Movement

The Fabric client mod for **The Blocky Zombies** server and the
[MinecraftZombies](https://github.com/TTG24/MZ-Minecraft-Zombies) plugin: Call of Duty-style movement, animated
zombies, gore and death cutscenes. Minecraft Java **26.3**.

Players can join without it, but the server is built around it. The easiest way to install it is the
[mod guide](https://theblockyzombies.net/play#mod-guide), which offers a Prism Launcher instance that updates itself,
a modpack, a CurseForge profile and a Windows installer. Jars are on the
[releases page](https://github.com/TTG24/MZ-Movement/releases), and changes are in [RELEASE_NOTES.md](RELEASE_NOTES.md).

## Features

- **Tactical movement.** Crouch, prone and dolphin dives, with stamina and a lower hitbox while prone or diving.
- **Animated zombies.** A jointed zombie model with idle, walk, sprint, attack, rise and death animations in
  several styles, and five rotting-soldier looks.
- **Crawlers and gore.** Zombies whose legs are blown off keep crawling. Hits tear off chunks, and corpses fall as
  ragdolls that keep their wounds.
- **Death cutscenes** when you go down, and tactical first- and third-person sword animations.
- **Server-synced.** Nearby players see your stance and animations.

## Controls

| Key | Action |
|---|---|
| `C` | Tap: crouch. Hold: go prone |
| `X` | Crouch (toggle) |
| Sprint, then Sneak | Dolphin dive |
| `R` | Reload |
| `G` | Throw grenade |
| `V` | Inspect sword |

Change them in **Options > Controls > Key Binds > MZ-Movement**.

## Installing by hand

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.3.
2. Put `mz-movement-<version>.jar` and [Fabric API](https://modrinth.com/mod/fabric-api) in your `mods` folder.
   When updating from an old `tactical-movement-*.jar`, delete it first.
3. Join `mc.theblockyzombies.net`.

The server side runs on Paper/Purpur with a companion plugin, so players without the mod still get stamina and the
zombie models.

## Building

Requires JDK 25.

```
./gradlew build        # .\gradlew.bat build on Windows
```

The jar is written to `build/libs/mz-movement-<version>.jar`. Each build also writes `guide.json` into the jar
(version, default keys, newest release notes), which the server uses for the website's mod guide.

## Related projects

- [MZ-Minecraft-Zombies](https://github.com/TTG24/MZ-Minecraft-Zombies): the MinecraftZombies server plugin (round-based zombie arenas).
- [BlockBlocker](https://github.com/TTG24/BlockBlocker): server plugin for invisible barriers that block players and mobs.

## License

All rights reserved. The source is viewable only; copying, modifying and redistributing it require written
permission. See [LICENSE](LICENSE).

Author: [TTG24](https://github.com/TTG24)
