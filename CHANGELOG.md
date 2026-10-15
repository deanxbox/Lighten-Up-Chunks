# Changelog

Each version section is published as the Modrinth changelog for its tag.

## 1.3.0

- Chunky-style selection workflow: world, shape, center, radius, corners.
- Only chunks already present in region files are relit; missing chunks are never generated or written.
- Chunks are verified as saved "minecraft:full" (read-only, off-thread) over the full 5x5 dependency area before any chunk ticket is added; areas that fail the check are skipped.
- Hard no-generation guard: a mixin cancels any chunk generation (and flags replacement of unreadable chunks) inside areas being relit, and such areas are skipped without saving.
- Read-only region reads (no RegionFile handle opened for writing); duplicate saves/Voxy notifications for overlapping areas are removed.
- Voxy / VoxyServer notified after relighting.
