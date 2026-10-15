package io.github.dean.lightenupchunks.task;

import io.github.dean.lightenupchunks.WorldPathResolver;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/** Strictly read-only reader of saved chunk NBT; never opens a vanilla RegionFile (which can write/pad). */
final class PersistedLightInspector implements AutoCloseable {
	private static final int SECTOR_BYTES = 4096;

	PersistedLightInspector() {
	}

	private Path currentRegionPath;
	private FileChannel currentChannel;

	/** Returns the saved chunk tag, or null when the chunk is absent, external (.mcc) or unreadable. */
	CompoundTag read(ServerLevel level, ChunkPos chunkPos) throws IOException {
		Path regionDirectory = resolveRegionDirectory(level);
		int regionX = Math.floorDiv(chunkPos.x, 32);
		int regionZ = Math.floorDiv(chunkPos.z, 32);
		Path regionFilePath = regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca");
		if (!Files.exists(regionFilePath)) {
			return null;
		}

		if (!regionFilePath.equals(currentRegionPath) || currentChannel == null) {
			close();
			currentChannel = FileChannel.open(regionFilePath, StandardOpenOption.READ);
			currentRegionPath = regionFilePath;
		}

		int index = Math.floorMod(chunkPos.x, 32) + Math.floorMod(chunkPos.z, 32) * 32;
		ByteBuffer location = ByteBuffer.allocate(4);
		if (!readFully(location, index * 4L)) {
			return null;
		}
		int packed = location.getInt(0);
		long offset = (packed >>> 8) & 0xFFFFFFL;
		int sectors = packed & 0xFF;
		if (offset < 2 || sectors == 0) {
			return null;
		}

		ByteBuffer head = ByteBuffer.allocate(5);
		if (!readFully(head, offset * SECTOR_BYTES)) {
			return null;
		}
		int length = head.getInt(0);
		int version = head.get(4) & 0xFF;
		if (length <= 1 || length > sectors * SECTOR_BYTES - 4 || (version & 0x80) != 0) {
			return null;
		}
		RegionFileVersion regionVersion = RegionFileVersion.fromId(version);
		if (regionVersion == null) {
			return null;
		}
		ByteBuffer payload = ByteBuffer.allocate(length - 1);
		if (!readFully(payload, offset * SECTOR_BYTES + 5)) {
			return null;
		}
		try (DataInputStream inputStream = new DataInputStream(new BufferedInputStream(regionVersion.wrap(new ByteArrayInputStream(payload.array()))))) {
			return NbtIo.read(inputStream, NbtAccounter.uncompressedQuota());
		}
	}

	private boolean readFully(ByteBuffer buffer, long position) throws IOException {
		while (buffer.hasRemaining()) {
			int read = currentChannel.read(buffer, position + buffer.position());
			if (read < 0) {
				return false;
			}
		}
		return true;
	}

	/** Only fully generated chunks may be loaded; anything else would make vanilla finish generating it. */
	static boolean isFull(CompoundTag chunkTag) {
		if (chunkTag == null || !"minecraft:full".equals(chunkTag.getStringOr("Status", ""))) {
			return false;
		}
		// Vanilla replaces undeserialisable chunks with generated ones, so reject obviously corrupt block storage here.
		ListTag sections = chunkTag.getListOrEmpty("sections");
		for (int index = 0; index < sections.size(); index++) {
			CompoundTag blockStates = sections.getCompoundOrEmpty(index).getCompoundOrEmpty("block_states");
			if (blockStates.getListOrEmpty("palette").size() > 1 && !blockStates.contains("data")) {
				return false;
			}
		}
		return true;
	}

	static boolean lacksStoredLightData(CompoundTag chunkTag, boolean hasSkyLight) {
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
		if (currentChannel != null) {
			currentChannel.close();
			currentChannel = null;
		}
		currentRegionPath = null;
	}
}
