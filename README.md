# GLB Render (glbrender)

A standalone glTF/GLB model rendering mod for **Minecraft 1.21.1 / NeoForge 21.1.228**. It replaces the player's
model with a GLB and adds a GLB-rendered NPC entity with its own behavior and editor.

Status: **experimental (0.1.0)**. Everything listed under *Features* has been compiled and exercised in-game; the
remaining editor rows and world managers are visible placeholders (see *Not implemented yet*).

## Features

**Rendering**
- glTF 2.0 / GLB scene loading, skinned meshes, per-instance color tint.
- Three shading modes per NPC: PBR (GGX, normal/occlusion/emissive maps), vanilla-like, and unlit.
- Batched instanced rendering for untinted NPCs.
- Player model replacement with a clip-driven state machine: idle/walk/sprint/swim/sleep/crawl/climb/ride/sneak,
  crossfade between states, head look, held items.

**NPC (`glb_npc`)**
- `PathfinderMob` with a CustomNPCs-style editor: **sneak + right-click** opens it, a plain right-click talks.
- Six tabs: Display / Stats / AI / Inventory / Advanced / Global.
  - Display: model cycle, scale, yaw, ground offset, shading, tint, glow, display name, 3D preview.
  - Stats: health, regeneration, respawn, melee/ranged, passives — all server-applied.
  - AI: wander, follow owner, sit, look at player, on-attack response, doors, shelter, water, return-to-home,
    leap, mount control, movement speed.
  - Inventory: 52 slots (equipment, loot, projectile), per-slot drop chances, held items rendered in hand.
  - Advanced: spoken lines per event with sounds, overhead marks, factions, dialog slots, linked NPC.
  - Global: world-level factions, dialogs, transport locations and linked names (shared, operator-gated).
- World data is stored per save (`glbrender_world` SavedData in the overworld) and synced to clients on join.

## Requirements

- JDK 21
- Minecraft 1.21.1
- NeoForge 21.1.228 (pinned in `gradle.properties`)

Gradle must itself run on JDK 21 — the NeoForge moddev plugin loads in the Gradle JVM before toolchains apply. If
`JAVA_HOME` points at an older JDK, either fix it or uncomment `org.gradle.java.home` in `gradle.properties`.

## Building

```bash
./gradlew --offline build      # jar in build/libs/
./gradlew --offline runClient  # dev client
```

`--offline` skips a remote metadata check that can hang on restricted networks; drop it if dependencies are not cached.

There are also verification tasks that do not launch Minecraft:

```bash
./gradlew glbDump -Pglb=test-models/InterpolationTest.glb
./gradlew offscreenRender -Pglb=test-models/deepseek-chan.glb
./gradlew layoutSelfTest
./gradlew glSelfTest
```

## Using your own models

1. Drop the `.glb` file into the game directory (for example `run/models/`).
2. Add one descriptor per model under `run/config/glbrender/models/`. Files are loaded at startup and sorted by
   filename, which is also the order the editor cycles through.

```json
{
  "id": "my_npc",
  "model": "models/my_npc.glb",
  "scale": 1.0,
  "yaw_offset": 0.0,
  "ground_offset_y": 0.0
}
```

`model` is a filesystem path relative to the game directory (or absolute). If no descriptor loads, the mod falls
back to its built-in table (any `models/luka.glb` / `models/cesium.glb` present in the game directory).

## Not implemented yet

Editor: Role, Job, Day/Night transform, Scenes; Global: banks, quests, player data, recipes, natural spawn.
The NPC editor layout is inspired by CustomNPCs, but it is an independent implementation; no CustomNPCs code is
included or used at runtime.

## Sample assets

`test-models/` contains assets used by the offscreen tools and the dev environment:

- `deepseek-chan.glb`, `run.glb` — assets made for this project.
- `CesiumMan.glb` — from the Khronos glTF Sample Models (Cesium, CC-BY 4.0).
- `InterpolationTest.glb` — from the Khronos glTF Sample Models.

Third-party character models are not distributed with this repository.

## License

LGPL-3.0-or-later. See [LICENSE](LICENSE) (LGPL-3.0) and [LICENSE.GPL-3.0](LICENSE.GPL-3.0) (the GPL-3.0 terms the
LGPL incorporates). Third-party attribution is in [NOTICE](NOTICE).
