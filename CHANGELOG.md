# Changelog

Each version section is published as the Modrinth changelog for its tag.

## 1.1.0

- Backport of the current feature set to Minecraft 26.1.x.
- Fix: chunks are verified as saved "minecraft:full" (read-only, off-thread) before any chunk ticket is added, so partially generated chunks are never loaded or generated further. Areas that fail the check are skipped.
- Read-only region reads (no RegionFile handle opened for writing); duplicate saves for overlapping areas are removed.
- Chunky-style selection workflow: world, shape, center, radius, corners.
- Only chunks already present in region files are relit; missing chunks are never generated or written.
- Voxy / VoxyServer notified after relighting.