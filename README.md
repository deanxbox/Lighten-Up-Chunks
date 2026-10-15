# Lighten Up, Chunks!

Server-side chunk relighting for existing Fabric worlds on Minecraft `26.3`.

This mod is aimed at repairing broken saved lighting in bulk without asking admins to fly around and trigger chunk updates manually.

## Requirements and Build

- Minecraft `26.3`
- Java `25` or newer
- Fabric Loader `0.19.5` or newer
- Fabric API `0.161.0+26.3` or newer for Minecraft `26.3`
- Optional: Mod Menu `21.0.0` for the singleplayer config screen

Build and run the tests with `./gradlew build` (`.\gradlew.bat build` on Windows).
The mod JAR is written to `build/libs/lighten-up-chunks-1.3.0.jar`.

## What It Does

- Scans existing chunks from region files instead of generating new terrain
- Relights whole dimensions, circular radii, or boxed regions
- Supports pause, resume, cancel, and crash-safe persistence through `luc_state.json`
- Shows progress in chat and on a boss bar
- Broadcasts a completion message to online players
- Optionally notifies Voxy and VoxyServer after relighting so LODs can refresh without a manual import

## Commands

The selection workflow now follows Chunky: select a world, shape, centre, and radius, then preview or start it. The default selection is a 500-block square centred at `0, 0` in the overworld.

- `/luc` — show the current selection and task state
- `/luc world [dimension]` — select a dimension; omitting it uses your current dimension
- `/luc shape <square|circle|rectangle|world>` — select the scan shape
- `/luc center [x z]` — set the centre; omitting coordinates uses your position
- `/luc radius <radius> [radiusZ]` — set one radius, or separate X/Z radii
- `/luc corners <x1 z1 x2 z2>` — set a rectangular selection from corner coordinates
- `/luc mode [missing-only|full|full-2-pass]` — show or change the relight mode
- `/luc selection` — show the current selection
- `/luc preview` — count matched chunks without starting
- `/luc start` — start relighting the current selection
- `/luc progress` — show active or saved task progress
- `/luc pause` — pause and persist the active task
- `/luc continue` — continue a saved task
- `/luc cancel` — cancel the active or saved task
- `/luc config [get|set|reload|reset]` — inspect or change server settings

Example:

```text
/luc world overworld
/luc shape circle
/luc center 0 0
/luc radius 5000
/luc preview
/luc start
```

## Relight Modes

- `missing-only`
  Only relights chunks whose saved light data appears to be missing
- `full`
  Performs a full relight pass on each matched chunk
- `full-2-pass`
  Queues two passes for each chunk, which can help more stubborn edge cases

## Safety Notes

- The mod only targets chunks that already exist on disk
- It skips chunks whose 3x3 lighting neighborhood is incomplete on disk, which avoids accidental neighbor generation at world borders
- Radius selections are true circles now, not bounding squares
- `/luc pause` and `/luc cancel` release in-flight work immediately instead of waiting for chunk loads to drain

## Voxy Integration

- When `enableVoxyCompat` is enabled, Lighten Up, Chunks tries to notify supported Voxy variants after saving relit chunks
- In singleplayer it attempts to use Voxy client's auto-ingest path
- On dedicated servers it attempts to mark VoxyServer sections dirty so they can rebuild
- If neither mod is present, or if Voxy is not ready yet, relighting still completes normally
- Debug logging will note when a supported Voxy integration is detected

## Config

The config file lives at Fabric's config path as `lighten-up-chunks.json`.

Notable options:

- `defaultRelightMode`
- `maxInFlightChunks`
- `maxRelightsPerTick`
- `saveFlushIntervalSeconds`
- `enableVoxyCompat`
- Boss bar display toggles

Set them in-game with `/luc config`, `/luc config get <key>`, and `/luc config set <key> <value>`, or through Mod Menu in singleplayer.
