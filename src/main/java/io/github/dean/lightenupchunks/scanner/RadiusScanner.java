package io.github.dean.lightenupchunks.scanner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public final class RadiusScanner implements ChunkRegionScanner {
	private final int radiusBlocks;
	private final int centerBlockX;
	private final int centerBlockZ;

	public RadiusScanner(int radiusBlocks, int centerBlockX, int centerBlockZ) {
		this.radiusBlocks = radiusBlocks;
		this.centerBlockX = centerBlockX;
		this.centerBlockZ = centerBlockZ;
	}

	@Override
	public List<ChunkPos> scan(ServerLevel level) throws IOException {
		RegionFileProbe regionFileProbe = new RegionFileProbe();
		int minChunkX = SectionPos.blockToSectionCoord(centerBlockX - radiusBlocks);
		int maxChunkX = SectionPos.blockToSectionCoord(centerBlockX + radiusBlocks);
		int minChunkZ = SectionPos.blockToSectionCoord(centerBlockZ - radiusBlocks);
		int maxChunkZ = SectionPos.blockToSectionCoord(centerBlockZ + radiusBlocks);

		List<ChunkPos> chunkPositions = new ArrayList<>();
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
