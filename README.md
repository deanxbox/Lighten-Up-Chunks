# Lighten Up, Chunks!

Server-side chunk relighting for existing Fabric worlds on Minecraft `26.1.2`.

This mod is aimed at repairing broken saved lighting in bulk without asking admins to fly around and trigger chunk updates manually.

## What It Does

- Scans existing chunks from region files instead of generating new terrain
- Relights whole dimensions, circular radii, or boxed regions
- Supports pause, resume, cancel, and crash-safe persistence through `luc_state.json`
- Shows progress in chat and on a boss bar
- Broadcasts a completion message to online players
- Optionally notifies Voxy and VoxyServer after relighting so LODs can refresh without a manual import

## Commands

- `/luc`
  Shows the current selection and task status
- `/luc dimensions`
  Lists loaded dimensions and powers tab completion for custom dimensions
- `/luc mode <missing-only|full|full-2-pass>`
  Chooses how the next task should relight chunks
- `/luc world [dimension]`
  Configures a whole-dimension selection
- `/luc radius <radius> [centerX centerZ] [dimension]`
  Configures a circular selection in block coordinates
- `/luc region <x1 z1 x2 z2>`
  Configures a boxed selection in block coordinates
- `/luc preview`
  Scans the configured selection and shows how many chunks and relight steps would run before you start
- `/luc estimate`
  Alias for `/luc preview`
- `/luc start`
  Starts the configured task
- `/luc pause`
- `/luc resume`
- `/luc cancel`

You can also run `preview`, `estimate`, and `start` directly with `world`, `radius`, `region`, and explicit dimension arguments.

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

Set them in-game with `/luc config show`, `/luc config get <key>`, and `/luc config set <key> <value>`, or through Mod Menu in singleplayer.
