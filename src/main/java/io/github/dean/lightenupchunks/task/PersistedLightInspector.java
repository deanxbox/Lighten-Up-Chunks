package io.github.dean.lightenupchunks.task;

import io.github.dean.lightenupchunks.WorldPathResolver;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

final class PersistedLightInspector implements AutoCloseable {
	PersistedLightInspector() {
	}

	private Path currentRegionPath;
	private Path currentRegionDirectory;
	private RegionFile currentRegionFile;
	private boolean currentHasSkyLight;

	public boolean chunkNeedsLighting(ServerLevel level, ChunkPos chunkPos) throws IOException {
		Path regionDirectory = resolveRegionDirectory(level);
		int regionX = Math.floorDiv(chunkPos.x(), 32);
		int regionZ = Math.floorDiv(chunkPos.z(), 32);
		Path regionFilePath = regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca");
		if (!Files.exists(regionFilePath)) {
			return true;
		}

		openRegionIfNeeded(level, regionDirectory, regionFilePath);
		try (DataInputStream inputStream = currentRegionFile.getChunkDataInputStream(chunkPos)) {
			if (inputStream == null) {
				return true;
			}

			CompoundTag chunkTag = NbtIo.read(inputStream, NbtAccounter.unlimitedHeap());
			return lacksStoredLightData(chunkTag, currentHasSkyLight);
		}
	}

	private void openRegionIfNeeded(ServerLevel level, Path regionDirectory, Path regionFilePath) throws IOException {
		if (regionFilePath.equals(currentRegionPath) && currentRegionFile != null) {
			return;
		}

		close();
		RegionStorageInfo info = new RegionStorageInfo(
			level.getServer().getWorldData().getLevelName(),
			level.dimension(),
			"chunk"
		);
		currentRegionDirectory = regionDirectory;
		currentRegionPath = regionFilePath;
		currentHasSkyLight = level.dimensionType().hasSkyLight();
		currentRegionFile = new RegionFile(info, regionFilePath, currentRegionDirectory, false);
	}

	private static boolean lacksStoredLightData(CompoundTag chunkTag, boolean hasSkyLight) {
		if (chunkTag == null || !chunkTag.getBooleanOr("isLightOn", false)) {
			return true;
		}

		ListTag sections = chunkTag.getListOrEmpty("sections");
		for (int index = 0; index < sections.size(); index++) {
			CompoundTag sectionTag = sections.getCompoundOrEmpty(index);
			if (sectionTag.contains("BlockLight")) {
				return false;
			}
			if (hasSkyLight && sectionTag.contains("SkyLight")) {
				return false;
			}
		}
		return true;
	}

	private static Path resolveRegionDirectory(ServerLevel level) {
		return WorldPathResolver.resolveDimensionRoot(level).resolve("region");
	}

	@Override
	public void close() throws IOException {
		if (currentRegionFile != null) {
			currentRegionFile.close();
			currentRegionFile = null;
		}
		currentRegionPath = null;
		currentRegionDirectory = null;
	}
}
