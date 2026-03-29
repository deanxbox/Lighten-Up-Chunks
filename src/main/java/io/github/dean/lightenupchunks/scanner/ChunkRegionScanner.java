package io.github.dean.lightenupchunks.scanner;

import java.io.IOException;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public interface ChunkRegionScanner {
	List<ChunkPos> scan(ServerLevel level) throws IOException;
}
