package io.github.dean.lightenupchunks.scanner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public final class BoxScanner implements ChunkRegionScanner {
	private final int minX;
	private final int minZ;
	private final int maxX;
	private final int maxZ;

	public BoxScanner(int x1, int z1, int x2, int z2) {
		this.minX = Math.min(x1, x2);
		this.minZ = Math.min(z1, z2);
		this.maxX = Math.max(x1, x2);
		this.maxZ = Math.max(z1, z2);
	}

	@Override
	public List<ChunkPos> scan(ServerLevel level) throws IOException {
		RegionFileProbe regionFileProbe = new RegionFileProbe();
		List<ChunkPos> chunkPositions = new ArrayList<>();
		int minChunkX = SectionPos.blockToSectionCoord(minX);
		int minChunkZ = SectionPos.blockToSectionCoord(minZ);
		int maxChunkX = SectionPos.blockToSectionCoord(maxX);
		int maxChunkZ = SectionPos.blockToSectionCoord(maxZ);
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
				if (regionFileProbe.shouldIncludeChunk(level, chunkPos)) {
					chunkPositions.add(chunkPos);
				}
			}
		}
		return chunkPositions;
	}
}
