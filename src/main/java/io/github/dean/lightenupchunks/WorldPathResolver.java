package io.github.dean.lightenupchunks;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelStorageSource;

public final class WorldPathResolver {
	private WorldPathResolver() {
	}

	public static Path resolveWorldRoot(MinecraftServer server) {
		Throwable failure = null;
		String levelId = null;

		try {
			LevelStorageSource.LevelStorageAccess storageAccess = findStorageAccess(server);
			levelId = findLevelId(storageAccess);

			Path reflected = resolveFromStorageAccess(storageAccess, levelId);
			if (reflected != null) {
				return reflected;
			}
		} catch (ReflectiveOperationException | RuntimeException exception) {
			failure = exception;
		}

		Path filesystem = resolveFromFilesystem(levelId);
		if (filesystem != null) {
			return filesystem;
		}

		IllegalStateException exception = new IllegalStateException("Failed to resolve world root path");
		if (failure != null) {
			exception.initCause(failure);
		}
		throw exception;
	}

	public static Path resolveWorldRoot(ServerLevel level) {
		MinecraftServer server = resolveServer(level);
		if (server == null) {
			throw new IllegalStateException("Failed to resolve server from level");
		}
		return resolveWorldRoot(server);
	}

	private static LevelStorageSource.LevelStorageAccess findStorageAccess(MinecraftServer server) throws ReflectiveOperationException {
		Class<?> type = server.getClass();
		while (type != null) {
			for (Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				if (LevelStorageSource.LevelStorageAccess.class.isAssignableFrom(field.getType())) {
					field.setAccessible(true);
					Object value = field.get(server);
					if (value instanceof LevelStorageSource.LevelStorageAccess storageAccess) {
						return storageAccess;
					}
				}
			}
			type = type.getSuperclass();
		}
		throw new NoSuchFieldException("Could not locate LevelStorageAccess on MinecraftServer");
	}

	private static MinecraftServer resolveServer(ServerLevel level) {
		Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		return resolveServer(level, visited, 3);
	}

	private static MinecraftServer resolveServer(Object instance, Set<Object> visited, int depth) {
		if (instance == null || depth < 0 || !visited.add(instance)) {
			return null;
		}
		if (instance instanceof MinecraftServer server) {
			return server;
		}

		Class<?> type = instance.getClass();
		while (type != null) {
			for (Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				try {
					field.setAccessible(true);
					Object value = field.get(instance);
					if (value instanceof MinecraftServer server) {
						return server;
					}
					if (value != null && shouldDescendInto(value.getClass())) {
						MinecraftServer resolved = resolveServer(value, visited, depth - 1);
						if (resolved != null) {
							return resolved;
						}
					}
				} catch (ReflectiveOperationException | RuntimeException ignored) {
				}
			}
			type = type.getSuperclass();
		}

		return null;
	}

	private static Path resolveFromStorageAccess(LevelStorageSource.LevelStorageAccess storageAccess, String levelId) {
		Set<Path> candidates = new LinkedHashSet<>();
		collectPaths(storageAccess, candidates, Collections.newSetFromMap(new IdentityHashMap<>()), 3);
		for (Path candidate : candidates) {
			Path resolved = resolveCandidate(candidate, levelId);
			if (resolved != null) {
				return resolved;
			}
		}
		return null;
	}

	private static void collectPaths(Object instance, Set<Path> paths, Set<Object> visited, int depth) {
		if (instance == null || depth < 0 || !visited.add(instance)) {
			return;
		}
		if (instance instanceof Path path) {
			paths.add(path.toAbsolutePath().normalize());
			return;
		}
		if (instance instanceof Map<?, ?> map) {
			for (Object value : map.values()) {
				collectPaths(value, paths, visited, depth - 1);
			}
			return;
		}

		Class<?> type = instance.getClass();
		while (type != null) {
			for (Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				try {
					field.setAccessible(true);
					Object value = field.get(instance);
					if (value == null) {
						continue;
					}
					if (value instanceof Path path) {
						paths.add(path.toAbsolutePath().normalize());
						continue;
					}
					if (shouldDescendInto(value.getClass())) {
						collectPaths(value, paths, visited, depth - 1);
					}
				} catch (ReflectiveOperationException | RuntimeException ignored) {
				}
			}
			type = type.getSuperclass();
		}
	}

	private static boolean shouldDescendInto(Class<?> type) {
		String name = type.getName();
		if (type.isPrimitive() || type.isEnum() || name.startsWith("java.lang")) {
			return false;
		}
		if (Path.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
			return true;
		}
		if (name.startsWith("java.")) {
			return false;
		}
		return name.startsWith("net.minecraft.") || name.contains("LevelStorage");
	}

	private static String findLevelId(LevelStorageSource.LevelStorageAccess storageAccess) throws ReflectiveOperationException {
		Class<?> type = storageAccess.getClass();
		while (type != null) {
			for (Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
					continue;
				}
				field.setAccessible(true);
				Object value = field.get(storageAccess);
				if (value instanceof String text && !text.isBlank()) {
					return text;
				}
			}
			type = type.getSuperclass();
		}
		return null;
	}

	private static Path resolveFromFilesystem(String levelId) {
		List<Path> searchRoots = new ArrayList<>();
		Path workingDirectory = Path.of("").toAbsolutePath().normalize();
		searchRoots.add(workingDirectory);
		searchRoots.add(workingDirectory.resolve("saves"));
		searchRoots.add(workingDirectory.resolve(".minecraft"));
		searchRoots.add(workingDirectory.resolve(".minecraft").resolve("saves"));
		if (workingDirectory.getParent() != null) {
			searchRoots.add(workingDirectory.getParent());
			searchRoots.add(workingDirectory.getParent().resolve("saves"));
		}

		if (levelId != null && !levelId.isBlank()) {
			for (Path searchRoot : searchRoots) {
				Path resolved = resolveCandidate(searchRoot.resolve(levelId), levelId);
				if (resolved != null) {
					return resolved;
				}
			}
		}

		List<Path> discoveredWorlds = new ArrayList<>();
		for (Path searchRoot : searchRoots) {
			collectWorldDirectories(searchRoot, discoveredWorlds);
		}
		if (discoveredWorlds.isEmpty()) {
			return null;
		}
		discoveredWorlds.sort((left, right) -> Long.compare(lastModified(right), lastModified(left)));
		return discoveredWorlds.get(0);
	}

	private static void collectWorldDirectories(Path searchRoot, List<Path> worlds) {
		if (!Files.isDirectory(searchRoot)) {
			return;
		}

		Path direct = detectWorldRoot(searchRoot);
		if (direct != null) {
			worlds.add(direct);
		}

		Path savesDirectory = searchRoot.getFileName() != null && searchRoot.getFileName().toString().equalsIgnoreCase("saves")
			? searchRoot
			: searchRoot.resolve("saves");
		if (!Files.isDirectory(savesDirectory)) {
			return;
		}

		try (var stream = Files.list(savesDirectory)) {
			stream
				.filter(Files::isDirectory)
				.map(WorldPathResolver::detectWorldRoot)
				.filter(path -> path != null)
				.forEach(worlds::add);
		} catch (IOException ignored) {
		}
	}

	private static Path resolveCandidate(Path candidate, String levelId) {
		if (candidate == null) {
			return null;
		}

		Path direct = detectWorldRoot(candidate);
		if (direct != null) {
			return direct;
		}

		if (levelId != null && !levelId.isBlank()) {
			Path withLevelId = detectWorldRoot(candidate.resolve(levelId));
			if (withLevelId != null) {
				return withLevelId;
			}
		}

		return null;
	}

	private static Path detectWorldRoot(Path candidate) {
		if (candidate == null) {
			return null;
		}

		Path current = candidate.toAbsolutePath().normalize();
		for (int depth = 0; depth < 6 && current != null; depth++) {
			if (looksLikeWorldRoot(current)) {
				return current;
			}
			current = current.getParent();
		}
		return null;
	}

	private static boolean looksLikeWorldRoot(Path path) {
		return Files.exists(path.resolve("level.dat")) || Files.exists(path.resolve("session.lock"));
	}

	private static long lastModified(Path path) {
		try {
			Path marker = Files.exists(path.resolve("session.lock")) ? path.resolve("session.lock") : path.resolve("level.dat");
			return Files.getLastModifiedTime(marker).toMillis();
		} catch (IOException ignored) {
			return Long.MIN_VALUE;
		}
	}
}
