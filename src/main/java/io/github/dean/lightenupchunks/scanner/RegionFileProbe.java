package io.github.dean.lightenupchunks.scanner;

import io.github.dean.lightenupchunks.WorldPathResolver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public final class RegionFileProbe {
	private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	private final Map<Long, BitSet> headerCache = new HashMap<>();

	public boolean chunkExists(ServerLevel level, ChunkPos chunkPos) throws IOException {
		int regionX = Math.floorDiv(chunkPos.x(), 32);
		int regionZ = Math.floorDiv(chunkPos.z(), 32);
		BitSet occupancy = loadHeader(level, regionX, regionZ);
		int localX = Math.floorMod(chunkPos.x(), 32);
		int localZ = Math.floorMod(chunkPos.z(), 32);
		return occupancy.get(localZ * 32 + localX);
	}

	public boolean shouldIncludeChunk(ServerLevel level, ChunkPos chunkPos) throws IOException {
		return chunkExists(level, chunkPos);
	}

	public List<ChunkPos> scanAllExistingChunks(ServerLevel level) throws IOException {
		Path regionDirectory = resolveRegionDirectory(level);
		if (!Files.isDirectory(regionDirectory)) {
			return List.of();
		}

		List<Path> regionFiles;
		try (var stream = Files.list(regionDirectory)) {
			regionFiles = stream
				.filter(path -> REGION_FILE_PATTERN.matcher(path.getFileName().toString()).matches())
				.sorted()
				.toList();
		}

		List<ChunkPos> chunkPositions = new ArrayList<>();
		for (Path regionFile : regionFiles) {
			Matcher matcher = REGION_FILE_PATTERN.matcher(regionFile.getFileName().toString());
			if (!matcher.matches()) {
				continue;
			}

			int regionX = Integer.parseInt(matcher.group(1));
			int regionZ = Integer.parseInt(matcher.group(2));
			BitSet occupancy = readHeader(regionFile);
			for (int index = occupancy.nextSetBit(0); index >= 0; index = occupancy.nextSetBit(index + 1)) {
				int localX = index % 32;
				int localZ = index / 32;
				chunkPositions.add(new ChunkPos(regionX * 32 + localX, regionZ * 32 + localZ));
			}
		}
		return chunkPositions;
	}

	private BitSet loadHeader(ServerLevel level, int regionX, int regionZ) throws IOException {
		long cacheKey = (((long) regionX) << 32) ^ (regionZ & 0xFFFFFFFFL);
		BitSet cached = headerCache.get(cacheKey);
		if (cached != null) {
			return cached;
		}

		Path regionFile = resolveRegionDirectory(level)
			.resolve("r." + regionX + "." + regionZ + ".mca");
		BitSet loaded = readHeader(regionFile);
		headerCache.put(cacheKey, loaded);
		return loaded;
	}

	private static BitSet readHeader(Path regionFile) throws IOException {
		BitSet occupancy = new BitSet(1024);
		if (!Files.exists(regionFile)) {
			return occupancy;
		}

		byte[] header = new byte[4096];
		try (InputStream inputStream = Files.newInputStream(regionFile)) {
			int bytesRead = inputStream.read(header);
			if (bytesRead < 4096) {
				return occupancy;
			}
		}

		for (int index = 0; index < 1024; index++) {
			int base = index * 4;
			int offset = ((header[base] & 0xFF) << 16) | ((header[base + 1] & 0xFF) << 8) | (header[base + 2] & 0xFF);
			int sectors = header[base + 3] & 0xFF;
			if (offset != 0 && sectors != 0) {
				occupancy.set(index);
			}
		}
		return occupancy;
	}

	private static Path resolveRegionDirectory(ServerLevel level) {
		return WorldPathResolver.resolveDimensionRoot(level).resolve("region");
	}
}
