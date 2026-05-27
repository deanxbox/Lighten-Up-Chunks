package io.github.dean.lightenupchunks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldPathResolverTest {
	@Test
	void detectWorldRootWalksUpFromNestedPaths(@TempDir Path tempDir) throws IOException {
		Path world = createWorld(tempDir.resolve("saves").resolve("Example"), 1_000L);
		assertEquals(world, WorldPathResolver.detectWorldRoot(world.resolve("region")));
	}

	@Test
	void resolveFromFilesystemFindsRequestedWorld(@TempDir Path tempDir) throws IOException {
		Path world = createWorld(tempDir.resolve("saves").resolve("Example"), 1_000L);
		assertEquals(world, WorldPathResolver.resolveFromFilesystem(tempDir, "Example"));
	}

	@Test
	void resolveFromFilesystemFallsBackToMostRecentWorld(@TempDir Path tempDir) throws IOException {
		createWorld(tempDir.resolve("saves").resolve("Older"), 1_000L);
		Path newer = createWorld(tempDir.resolve("saves").resolve("Newer"), 2_000L);
		assertEquals(newer, WorldPathResolver.resolveFromFilesystem(tempDir, null));
	}

	private static Path createWorld(Path worldRoot, long markerTimeMillis) throws IOException {
		Files.createDirectories(worldRoot.resolve("region"));
		Path marker = Files.writeString(worldRoot.resolve("level.dat"), "test");
		Files.setLastModifiedTime(marker, FileTime.fromMillis(markerTimeMillis));
		return worldRoot.toAbsolutePath().normalize();
	}
}
