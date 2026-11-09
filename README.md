# Tactical Movement

A Fabric mod for Minecraft Java Edition 26.3 that adds Call of Duty-inspired tactical movement.

## Controls

- Tap `C`: toggle tactical crouch.
- Hold `C` for 0.4 seconds: enter prone.
- Press jump while prone: stand up.
- Sprint and press jump: dolphin dive.
- Hold sneak and press jump: dolphin dive.

The `C` binding can be changed in Minecraft's Controls menu.

## Features

- Server-authoritative movement state for integrated and dedicated multiplayer servers.
- Synchronized crouch, prone, and dolphin-dive states for nearby clients.
- Low prone/dive collision box and eye height.
- Procedural first-person and third-person tactical animation.
- Tactical two-handed sword pose.
- No animation-library dependency.

## Building

Minecraft 26.3 requires Java 25. This repository includes no JDK in source control; point `JAVA_HOME` at a Java 25 JDK and run:

```powershell
.\gradlew.bat build
```

The development workspace may use the ignored `.jdk` directory for a project-local Java installation.

## Multiplayer

Install the mod and Fabric API on both the client and server. Players without the mod cannot join a server that expects these custom payloads and animations.

## License

All rights reserved. The source is viewable only; copying, modifying and redistributing it require written permission. See [LICENSE](LICENSE).

## Author

[TTG24](https://github.com/TTG24)
