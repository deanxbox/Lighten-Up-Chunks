# Changelog

Each version section is published as the Modrinth changelog for its tag.

## 1.2.0

<<<<<<< ours
- Fix: chunks are now verified as saved "minecraft:full" (read-only, off-thread) before any chunk ticket is added, so partially generated chunks are never loaded or generated further. Areas that fail the check are skipped.
- Read-only region reads (no RegionFile handles opened for writing); duplicate saves/Voxy notifications for overlapping areas are removed.
- Chunky-style selection workflow: world, shape, center, radius, corners.
- Mod only reads existing chunks from region files; chunks missing on disk are never generated or written.
=======
- Port to Minecraft 26.3 (Fabric Loader 0.19.5, Fabric API 0.161.0+26.3).
- Fix: chunks are now verified as saved "minecraft:full" (read-only, off-thread) before any chunk ticket is added, so partially generated chunks are never loaded or generated further. Areas that fail the check are skipped.
- Read-only region reads (no RegionFile handles opened for writing); duplicate saves/Voxy notifications for overlapping areas are removed.
- Fix: chunks are now verified as saved "minecraft:full" (read-only, off-thread) before any chunk ticket is added, so partially generated chunks are never loaded or generated further. Areas that fail the check are skipped.
- Read-only region reads (no RegionFile handles opened for writing); duplicate saves/Voxy notifications for overlapping areas are removed.
- Chunky-style selection workflow: world, shape, centre, radius, corners.
- Only chunks already present in region files are relit; missing chunks are never generated or written.
>>>>>>> theirs
- Voxy / VoxyServer notified after relighting.


