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
				if (intersectsRadius(chunkPos) && regionFileProbe.shouldIncludeChunk(level, chunkPos)) {
					chunkPositions.add(chunkPos);
				}
			}
		}
		chunkPositions.sort((left, right) -> {
			long leftDistance = squaredDistanceFromCenter(left);
			long rightDistance = squaredDistanceFromCenter(right);
			if (leftDistance != rightDistance) {
				return Long.compare(leftDistance, rightDistance);
			}
			if (left.x() != right.x()) {
				return Integer.compare(left.x(), right.x());
			}
			return Integer.compare(left.z(), right.z());
		});
		return chunkPositions;
	}

	private boolean intersectsRadius(ChunkPos chunkPos) {
		int chunkMinX = SectionPos.sectionToBlockCoord(chunkPos.x());
		int chunkMinZ = SectionPos.sectionToBlockCoord(chunkPos.z());
		int chunkMaxX = chunkMinX + 15;
		int chunkMaxZ = chunkMinZ + 15;
		int nearestX = clamp(centerBlockX, chunkMinX, chunkMaxX);
		int nearestZ = clamp(centerBlockZ, chunkMinZ, chunkMaxZ);
		long deltaX = centerBlockX - nearestX;
		long deltaZ = centerBlockZ - nearestZ;
		long radiusSquared = (long) radiusBlocks * radiusBlocks;
		return deltaX * deltaX + deltaZ * deltaZ <= radiusSquared;
	}

	private long squaredDistanceFromCenter(ChunkPos chunkPos) {
		long centerX = SectionPos.sectionToBlockCoord(chunkPos.x()) + 8L;
		long centerZ = SectionPos.sectionToBlockCoord(chunkPos.z()) + 8L;
		long deltaX = centerBlockX - centerX;
		long deltaZ = centerBlockZ - centerZ;
		return deltaX * deltaX + deltaZ * deltaZ;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
