package io.github.dean.lightenupchunks;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

public final class VoxyCompat {
	private static volatile boolean clientResolutionAttempted;
	private static volatile boolean serverResolutionAttempted;
	private static volatile boolean loggedClientDetection;
	private static volatile boolean loggedServerDetection;

	private static MethodHandle tryAutoIngestHandle;
	private static Field dirtyTrackerInstanceField;
	private static MethodHandle markDirtyHandle;

	private VoxyCompat() {
	}

	public static void notifyChunkRelit(ServerLevel level, LevelChunk chunk) {
		if (!LucConfigManager.get().enableVoxyCompat) {
			return;
		}

		resolveClientIfNeeded();
		if (tryAutoIngestHandle != null) {
			try {
				Object result = tryAutoIngestHandle.invoke(chunk);
				if (Boolean.TRUE.equals(result)) {
					return;
				}
			} catch (Throwable throwable) {
				LightenUpChunks.LOGGER.debug("Failed to notify Voxy client about relit chunk {}", chunk.getPos(), throwable);
			}
		}

		resolveServerIfNeeded();
		if (dirtyTrackerInstanceField != null && markDirtyHandle != null) {
			try {
				Object dirtyTrackerInstance = dirtyTrackerInstanceField.get(null);
				if (dirtyTrackerInstance == null) {
					return;
				}

				ChunkPos pos = chunk.getPos();
				for (int y = level.getMinY(); y < level.getMaxY(); y += 32) {
					markDirtyHandle.invoke(dirtyTrackerInstance, level, pos.x(), y, pos.z());
				}
			} catch (Throwable throwable) {
				LightenUpChunks.LOGGER.debug("Failed to notify VoxyServer about relit chunk {}", chunk.getPos(), throwable);
			}
		}
	}

	private static void resolveClientIfNeeded() {
		if (clientResolutionAttempted) {
			return;
		}
		synchronized (VoxyCompat.class) {
			if (clientResolutionAttempted) {
				return;
			}
			try {
				Class<?> serviceClass = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService");
				MethodHandles.Lookup lookup = MethodHandles.publicLookup();
				tryAutoIngestHandle = lookup.findStatic(
					serviceClass,
					"tryAutoIngestChunk",
					MethodType.methodType(boolean.class, LevelChunk.class)
				);
				if (!loggedClientDetection) {
					LightenUpChunks.LOGGER.debug("Detected Voxy client integration via {}", serviceClass.getName());
					loggedClientDetection = true;
				}
			} catch (ClassNotFoundException ignored) {
			} catch (NoSuchMethodException | IllegalAccessException exception) {
				LightenUpChunks.LOGGER.debug("Failed to resolve Voxy client integration", exception);
			} finally {
				clientResolutionAttempted = true;
			}
		}
	}

	private static void resolveServerIfNeeded() {
		if (markDirtyHandle != null && dirtyTrackerInstanceField != null) {
			return;
		}
		if (serverResolutionAttempted && dirtyTrackerInstanceField == null) {
			return;
		}
		synchronized (VoxyCompat.class) {
			if (markDirtyHandle != null && dirtyTrackerInstanceField != null) {
				return;
			}
			try {
				Class<?> dirtyTrackerClass = Class.forName("com.dripps.voxyserver.server.DirtyTracker");
				Field instanceField = dirtyTrackerClass.getField("INSTANCE");
				MethodHandles.Lookup lookup = MethodHandles.publicLookup();
				dirtyTrackerInstanceField = instanceField;
				markDirtyHandle = lookup.findVirtual(
					dirtyTrackerClass,
					"markDirty",
					MethodType.methodType(void.class, ServerLevel.class, int.class, int.class, int.class)
				);
				if (!loggedServerDetection) {
					LightenUpChunks.LOGGER.debug("Detected VoxyServer integration via {}", dirtyTrackerClass.getName());
					loggedServerDetection = true;
				}
			} catch (ClassNotFoundException ignored) {
				dirtyTrackerInstanceField = null;
				markDirtyHandle = null;
			} catch (NoSuchFieldException | IllegalAccessException | NoSuchMethodException exception) {
				LightenUpChunks.LOGGER.debug("Failed to resolve VoxyServer integration", exception);
				dirtyTrackerInstanceField = null;
				markDirtyHandle = null;
			} finally {
				serverResolutionAttempted = true;
			}
		}
	}
}
