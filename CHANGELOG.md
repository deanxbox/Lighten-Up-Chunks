# Changelog

Each version section is published as the Modrinth changelog for its tag.

## 1.3.0

- Minecraft version: 26.1.x.
- Select relight areas with Chunky-style shapes, centers, radii, or corners using /luc; the default scan now covers the whole world.
- Relighting never generates chunks: it preflights saved FULL chunks, blocks generation while relighting, and skips chunks replaced during the operation.
- Edge chunks can now be relit; chunks with missing neighbours are skipped.
- Region data is read without opening region files for writing.
- Saves and Voxy notifications are coalesced once per chunk, with same-tick worker refill and per-dimension workers; a 500-block test ran about 20-30% faster.
- Failed or interrupted relights are handled safely and are not treated as successfully saved.
- Fix a C2ME/Starlight compatibility issue that could leave dark patches.
- Log a [LUC timings] line with per-stage relight timings.
- Notify Voxy and VoxyServer after relighting.