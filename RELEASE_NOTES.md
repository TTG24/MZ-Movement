# MZ-Movement 1.2.2

Fabric mod for Minecraft 26.3. Install it with Fabric API.

## New

- **Weather on zombies:** snow settles on zombies out in the snow, rain soaks them (and water), and desert or badlands
  dust clings to them. It builds up over a few seconds outside and wears off once they're out of it; corpses keep it.

# MZ-Movement 1.2.1

Fabric mod for Minecraft 26.3. Install it with Fabric API.

## New

- **Five zombie looks** (uniform, greatcoat, officer, torn, bandaged), each with matching gore and ragdoll joints.
- **A real open mouth** with teeth, and blood that runs down from wounds.
- **Ragdolls** no longer pass through themselves, and corpses hang their jaw open.

## Fixed

- Ragdolls spinning in place: bodies now settle and lie still (tested over 300 random falls, none spun).
- Hold-F repair stopping when you move.

# MZ-Movement 1.2.0

Fabric mod for Minecraft 26.3. Install it with Fabric API.

## New

- **Renamed to MZ-Movement** (it was "Tactical Movement"): the mod list, the key binds category
  (Options > Controls > Key Binds > MZ-Movement) and the jar, now `mz-movement-<version>.jar`.
- **New icon** in the server's theme: glowing eyes in the purple static.

## Updating by hand

Delete the old `tactical-movement-*.jar` from your `mods` folder before adding the new jar: two copies of the
mod stop the game from starting. The Prism instance and the Windows installer from theblockyzombies.net
remove it for you, and a freshly imported modpack or CurseForge profile starts clean.

# MZ-Movement 1.1.0

Fabric mod for Minecraft 26.3. Install it with Fabric API.

## New

- **One zombie model for everyone:** a jointed Blockbench zombie with idle, walk, sprint, attack,
  rise and death animations, in several styles. Players without the mod see the same model through
  the server.
- **Five zombie looks:** long-dead 1940s soldiers (rifleman, greatcoat, officer, torn, bandaged),
  also worn by the zombies in the death cutscenes.
- **Crawlers:** zombies whose legs get blown off fall and keep crawling and attacking.
- **Gore:** hits tear off chunks of skin and meat; corpses keep their wounds and stumps.
- **Throw Grenade key (G).**

## Fixes

- The crosshair ignores [BlockBlocker](https://github.com/TTG24/BlockBlocker)'s invisible wall colliders.

## Default keys

C prone, X crouch (toggle), R reload, G throw grenade, V inspect sword. Change them under
Options > Controls > Key Binds > Tactical Movement.
