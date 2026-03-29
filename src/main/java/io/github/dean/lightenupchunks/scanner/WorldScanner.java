package io.github.dean.lightenupchunks.scanner;

import java.io.IOException;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public final class WorldScanner implements ChunkRegionScanner {
	@Override
	public List<ChunkPos> scan(ServerLevel level) throws IOException {
		return new RegionFileProbe().scanAllExistingChunks(level);
	}
}
