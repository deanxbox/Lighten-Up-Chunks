package io.github.dean.lightenupchunks.task;

import io.github.dean.lightenupchunks.LucDimensions;
import io.github.dean.lightenupchunks.LightenUpChunks;
import io.github.dean.lightenupchunks.mixin.accessor.ChunkMapInvoker;
import io.github.dean.lightenupchunks.mixin.accessor.ServerChunkCacheAccessor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ChunkLightWorker {
	private static final int INSPECTION_THREADS = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() / 2));
	private static final int LOAD_RADIUS = 1;
	private static final TicketType LOAD_TICKET_TYPE = TicketType.FORCED;

	private final Queue<PersistedLightInspector> inspectors = new ConcurrentLinkedQueue<>();
	private final Map<ChunkPos, Integer> retainedLightDataCounts = new HashMap<>();
	private final ThreadLocal<PersistedLightInspector> inspectorLocal = ThreadLocal.withInitial(() -> {
		PersistedLightInspector inspector = new PersistedLightInspector();
		inspectors.add(inspector);
		return inspector;
	});
	private final ExecutorService inspectionExecutor = Executors.newFixedThreadPool(INSPECTION_THREADS, runnable -> {
		Thread thread = new Thread(runnable, "luc-light-inspector");
		thread.setDaemon(true);
		return thread;
	});

	public void submitChunks(ServerLevel level, LightTask task, Map<ChunkPos, InFlightChunk> inFlightChunks, int maxInFlight) {
		while (inFlightChunks.size() < maxInFlight) {
			ChunkPos chunkPos = task.pollNextChunk();
			if (chunkPos == null) {
				return;
			}

			List<ChunkPos> retainedChunks = ticketArea(chunkPos);
			retainLightData(level, retainedChunks);
			CompletableFuture<?> loadFuture = level.getChunkSource().addTicketAndLoadWithRadius(LOAD_TICKET_TYPE, chunkPos, LOAD_RADIUS);
			inFlightChunks.put(chunkPos, new InFlightChunk(chunkPos, retainedChunks, loadFuture, createInspectionFuture(level, task, chunkPos)));
		}
	}

	public int processLoadedChunks(ServerLevel level, Map<ChunkPos, InFlightChunk> inFlightChunks) {
		int processed = 0;
		int maxRelightsStartedThisTick = maxRelightsStartedThisTick(level);
		int startedThisTick = 0;
		Iterator<Map.Entry<ChunkPos, InFlightChunk>> iterator = inFlightChunks.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<ChunkPos, InFlightChunk> entry = iterator.next();
			ChunkPos chunkPos = entry.getKey();
			InFlightChunk inFlightChunk = entry.getValue();
			boolean finished = false;

			try {
				if (!inFlightChunk.hasRelightFuture()) {
					if (!inFlightChunk.loadReady()) {
						continue;
					}
					inFlightChunk.awaitLoaded();
					if (!inFlightChunk.shouldRelightReady()) {
						continue;
					}
					if (!inFlightChunk.shouldRelight()) {
						processed++;
						finished = true;
						continue;
					}
					if (startedThisTick >= maxRelightsStartedThisTick) {
						continue;
					}

					LevelChunk levelChunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
					if (levelChunk == null) {
						continue;
					}

					inFlightChunk.startRelight(relightChunk(level, levelChunk));
					startedThisTick++;
					continue;
				}

				if (!inFlightChunk.relightFuture().isDone()) {
					continue;
				}

				ChunkAccess relitChunk = inFlightChunk.relightFuture().join();
				if (relitChunk instanceof LevelChunk levelChunk) {
					levelChunk.markUnsaved();
				}
				saveRelitArea(level, inFlightChunk.chunkPos());
				processed++;
				finished = true;
			} catch (CompletionException exception) {
				finished = true;
				LightenUpChunks.LOGGER.error(
					"Failed to relight chunk {} in {}",
					chunkPos,
					LucDimensions.asString(level),
					unwrap(exception)
				);
			} catch (RuntimeException exception) {
				finished = true;
				LightenUpChunks.LOGGER.error(
					"Failed to relight chunk {} in {}",
					chunkPos,
					LucDimensions.asString(level),
					exception
				);
			} finally {
				if (finished) {
					iterator.remove();
					releaseChunk(level, chunkPos, inFlightChunk);
				}
			}
		}
		return processed;
	}

	public void releaseAll(ServerLevel level, Map<ChunkPos, InFlightChunk> inFlightChunks) {
		for (Map.Entry<ChunkPos, InFlightChunk> entry : inFlightChunks.entrySet()) {
			releaseChunk(level, entry.getKey(), entry.getValue());
		}
		inFlightChunks.clear();
	}

	private void releaseChunk(ServerLevel level, ChunkPos chunkPos, InFlightChunk inFlightChunk) {
		level.getChunkSource().removeTicketWithRadius(LOAD_TICKET_TYPE, chunkPos, LOAD_RADIUS);
		releaseLightData(level, inFlightChunk.retainedChunks());
	}

	private CompletableFuture<ChunkAccess> relightChunk(ServerLevel level, LevelChunk chunk) {
		ServerChunkCache chunkSource = level.getChunkSource();
		ThreadedLevelLightEngine lightEngine = chunkSource.getLightEngine();
		ImposterProtoChunk protoChunk = new ImposterProtoChunk(chunk, true);
		ChunkPos chunkPos = protoChunk.getPos();

		// Existing chunks relight more reliably when routed through the proto wrapper
		// vanilla uses for re-running chunk status work on loaded chunks.
		protoChunk.setLightCorrect(false);

		// The light engine is incremental, so existing broken values can survive a relight.
		// Clear section light storage first so vanilla rebuilds this chunk from zero.
		for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
			int sectionY = level.getSectionYFromSectionIndex(sectionIndex);
			SectionPos sectionPos = SectionPos.of(chunkPos, sectionY);
			lightEngine.queueSectionData(LightLayer.BLOCK, sectionPos, null);
			if (level.dimensionType().hasSkyLight()) {
				lightEngine.queueSectionData(LightLayer.SKY, sectionPos, null);
			}
		}

		lightEngine.setLightEnabled(chunkPos, true);
		protoChunk.initializeLightSources();
		return lightEngine.initializeLight(protoChunk, false)
			.thenApply(initializedChunk -> {
				lightEngine.propagateLightSources(initializedChunk.getPos());
				return initializedChunk;
			})
			.thenCompose(initializedChunk -> lightEngine.lightChunk(initializedChunk, false))
			.thenApply(relitChunk -> chunk);
	}

	private void saveRelitArea(ServerLevel level, ChunkPos centerChunkPos) {
		ServerChunkCache chunkSource = level.getChunkSource();
		ChunkMapInvoker chunkMap = (ChunkMapInvoker) ((ServerChunkCacheAccessor) chunkSource).lightenupchunks$getChunkMap();
		for (ChunkPos retainedChunkPos : ticketArea(centerChunkPos)) {
			LevelChunk retainedChunk = chunkSource.getChunkNow(retainedChunkPos.x, retainedChunkPos.z);
			if (retainedChunk != null) {
				retainedChunk.markUnsaved();
				chunkMap.lightenupchunks$saveChunk(retainedChunk);
				broadcastRelitChunk(chunkMap, retainedChunk);
			}
		}
	}

	private void broadcastRelitChunk(ChunkMapInvoker chunkMap, LevelChunk chunk) {
		List<ServerPlayer> players = chunkMap.lightenupchunks$getPlayers(chunk.getPos(), false);
		for (ServerPlayer player : players) {
			chunkMap.lightenupchunks$markChunkPendingToSend(player, chunk);
		}
	}

	private List<ChunkPos> ticketArea(ChunkPos centerChunkPos) {
		List<ChunkPos> retainedChunks = new ArrayList<>((LOAD_RADIUS * 2 + 1) * (LOAD_RADIUS * 2 + 1));
		for (int dx = -LOAD_RADIUS; dx <= LOAD_RADIUS; dx++) {
			for (int dz = -LOAD_RADIUS; dz <= LOAD_RADIUS; dz++) {
				retainedChunks.add(new ChunkPos(centerChunkPos.x + dx, centerChunkPos.z + dz));
			}
		}
		return retainedChunks;
	}

	private void retainLightData(ServerLevel level, List<ChunkPos> chunkPositions) {
		ThreadedLevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
		for (ChunkPos chunkPos : chunkPositions) {
			Integer count = retainedLightDataCounts.get(chunkPos);
			if (count == null) {
				lightEngine.retainData(chunkPos, true);
				retainedLightDataCounts.put(chunkPos, 1);
			} else {
				retainedLightDataCounts.put(chunkPos, count + 1);
			}
		}
	}

	private void releaseLightData(ServerLevel level, List<ChunkPos> chunkPositions) {
		ThreadedLevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
		for (ChunkPos chunkPos : chunkPositions) {
			Integer count = retainedLightDataCounts.get(chunkPos);
			if (count == null) {
				continue;
			}
			if (count > 1) {
				retainedLightDataCounts.put(chunkPos, count - 1);
			} else {
				retainedLightDataCounts.remove(chunkPos);
				lightEngine.retainData(chunkPos, false);
			}
		}
	}

	private CompletableFuture<Boolean> createInspectionFuture(ServerLevel level, LightTask task, ChunkPos chunkPos) {
		if (!task.inspectMissingLightOnly()) {
			return null;
		}

		return CompletableFuture.supplyAsync(() -> {
			try {
				return inspectorLocal.get().chunkNeedsLighting(level, chunkPos);
			} catch (IOException exception) {
				LightenUpChunks.LOGGER.warn(
					"Falling back to relighting chunk {} in {} because its saved light data could not be inspected",
					chunkPos,
					LucDimensions.asString(level),
					exception
				);
				return true;
			}
		}, inspectionExecutor);
	}

	private int maxRelightsStartedThisTick(ServerLevel level) {
		double averageMspt = level.getServer().getAverageTickTimeNanos() / 1_000_000.0D;
		if (averageMspt >= 45.0D) {
			return 4;
		}
		if (averageMspt >= 35.0D) {
			return 8;
		}
		if (averageMspt >= 25.0D) {
			return 16;
		}
		if (averageMspt >= 18.0D) {
			return 32;
		}
		if (averageMspt >= 14.0D) {
			return 48;
		}
		return 72;
	}

	private static Throwable unwrap(CompletionException exception) {
		Throwable cause = exception.getCause();
		return cause != null ? cause : exception;
	}

	public void close() {
		inspectionExecutor.shutdownNow();
		try {
			inspectionExecutor.awaitTermination(5L, TimeUnit.SECONDS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
		IOException thrown = null;
		for (PersistedLightInspector inspector : inspectors) {
			try {
				inspector.close();
			} catch (IOException exception) {
				if (thrown == null) {
					thrown = exception;
				} else {
					thrown.addSuppressed(exception);
				}
			}
		}
		if (thrown != null) {
			LightenUpChunks.LOGGER.warn("Failed to close persisted light inspector", thrown);
		}
	}

	public static final class InFlightChunk {
		private final ChunkPos chunkPos;
		private final List<ChunkPos> retainedChunks;
		private final CompletableFuture<?> loadFuture;
		private final CompletableFuture<Boolean> inspectionFuture;
		private CompletableFuture<ChunkAccess> relightFuture;

		private InFlightChunk(
			ChunkPos chunkPos,
			List<ChunkPos> retainedChunks,
			CompletableFuture<?> loadFuture,
			CompletableFuture<Boolean> inspectionFuture
		) {
			this.chunkPos = chunkPos;
			this.retainedChunks = retainedChunks;
			this.loadFuture = loadFuture;
			this.inspectionFuture = inspectionFuture;
		}

		public ChunkPos chunkPos() {
			return chunkPos;
		}

		public List<ChunkPos> retainedChunks() {
			return retainedChunks;
		}

		private boolean loadReady() {
			return loadFuture.isDone();
		}

		private void awaitLoaded() {
			loadFuture.join();
		}

		public boolean hasRelightFuture() {
			return relightFuture != null;
		}

		public CompletableFuture<ChunkAccess> relightFuture() {
			return relightFuture;
		}

		private void startRelight(CompletableFuture<ChunkAccess> relightFuture) {
			this.relightFuture = relightFuture;
		}

		private boolean shouldRelightReady() {
			return inspectionFuture == null || inspectionFuture.isDone();
		}

		private boolean shouldRelight() {
			return inspectionFuture == null || inspectionFuture.join();
		}
	}
}
