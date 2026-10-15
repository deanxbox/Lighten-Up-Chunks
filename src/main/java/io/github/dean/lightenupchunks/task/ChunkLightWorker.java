package io.github.dean.lightenupchunks.task;

import io.github.dean.lightenupchunks.LucDimensions;
import io.github.dean.lightenupchunks.LucConfigManager;
import io.github.dean.lightenupchunks.LightenUpChunks;
import io.github.dean.lightenupchunks.LucNoGeneration;
import net.minecraft.server.level.ChunkMap;
import io.github.dean.lightenupchunks.VoxyCompat;
import io.github.dean.lightenupchunks.mixin.accessor.ChunkAccessAccessor;
import io.github.dean.lightenupchunks.mixin.accessor.ChunkMapInvoker;
import io.github.dean.lightenupchunks.scanner.RegionFileProbe;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class ChunkLightWorker {
	private static final int INSPECTION_THREADS = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() / 2));
	private static final int RETAINED_AREA_RADIUS = 1;
	private static final int LOAD_TICKET_RADIUS = 0;
	private static final int NO_SAVE_GUARD_RADIUS = RETAINED_AREA_RADIUS;
	private static final TicketType LOAD_TICKET_TYPE = TicketType.FORCED;

	private final Queue<PersistedLightInspector> inspectors = new ConcurrentLinkedQueue<>();
	private final Map<ChunkPos, Integer> retainedLightDataCounts = new HashMap<>();
	private final Map<ChunkPos, Integer> retainedTicketCounts = new HashMap<>();
	// Vanilla FULL loading depends on neighbours up to 2 chunks out; all must already be FULL on disk or it would generate them.
	private static final int PREFLIGHT_RADIUS = 2;
	private static final int FULL_CACHE_LIMIT = 65536;
	private final Map<String, Boolean> fullCache = new ConcurrentHashMap<>();
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

	public int submitChunks(ServerLevel level, LightTask task, Map<ChunkPos, InFlightChunk> inFlightChunks, int maxInFlight) {
		int skipped = 0;
		while (inFlightChunks.size() < maxInFlight) {
			ChunkPos chunkPos = task.pollNextChunk();
			if (chunkPos == null) {
				return skipped;
			}

			List<ChunkPos> retainedChunks = ticketArea(chunkPos);
			// Disk preflight first: nothing is ticketed (and so nothing can be generated) until every chunk is verified FULL.
			inFlightChunks.put(chunkPos, new InFlightChunk(chunkPos, retainedChunks, createPreflightFuture(level, task, chunkPos, chunkArea(chunkPos, PREFLIGHT_RADIUS))));
		}
		return skipped;
	}

	public ProcessResult processLoadedChunks(ServerLevel level, Map<ChunkPos, InFlightChunk> inFlightChunks) {
		int relit = 0;
		int skipped = 0;
		List<FailureRecord> failures = new ArrayList<>();
		int maxRelightsStartedThisTick = maxRelightsStartedThisTick(level);
		int startedThisTick = 0;
		Set<ChunkPos> savedThisTick = new HashSet<>();
		Iterator<Map.Entry<ChunkPos, InFlightChunk>> iterator = inFlightChunks.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<ChunkPos, InFlightChunk> entry = iterator.next();
			ChunkPos chunkPos = entry.getKey();
			InFlightChunk inFlightChunk = entry.getValue();
			boolean finished = false;

			try {
				if (!inFlightChunk.hasRelightFuture()) {
					if (!inFlightChunk.loadStarted()) {
						if (!inFlightChunk.preflightDone()) {
							continue;
						}
						Preflight verdict = inFlightChunk.preflight();
						if (verdict != Preflight.RELIGHT) {
							skipped++;
							finished = true;
							continue;
						}
						retainLightData(level, inFlightChunk.retainedChunks());
						LucNoGeneration.claim(level.getChunkSource().chunkMap, chunkArea(chunkPos, PREFLIGHT_RADIUS));
						inFlightChunk.startLoad(loadRetainedArea(level, inFlightChunk.retainedChunks()));
						continue;
					}
					if (LucNoGeneration.anyBlocked(level.getChunkSource().chunkMap, chunkArea(chunkPos, PREFLIGHT_RADIUS))) {
						// Vanilla wanted to generate part of this area; the veto kept disk data untouched.
						skipped++;
						finished = true;
						continue;
					}
					if (!inFlightChunk.loadReady()) {
						continue;
					}
					inFlightChunk.awaitLoaded();
					if (startedThisTick >= maxRelightsStartedThisTick) {
						continue;
					}

					if (!allLoaded(level, inFlightChunk.retainedChunks())) {
						// Safety net: never relight or save an area that did not load as complete chunks.
						skipped++;
						finished = true;
						continue;
					}
					if (anyReplaced(level, inFlightChunk.retainedChunks())) {
						skipped++;
						finished = true;
						continue;
					}
					LevelChunk levelChunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());

					inFlightChunk.startRelight(relightChunk(level, levelChunk));
					startedThisTick++;
					continue;
				}

				if (!inFlightChunk.relightFuture().isDone()) {
					continue;
				}

				ChunkAccess relitChunk = inFlightChunk.relightFuture().join();
				if (anyReplaced(level, inFlightChunk.retainedChunks())) {
					skipped++;
					finished = true;
					continue;
				}
				if (relitChunk instanceof LevelChunk levelChunk) {
					levelChunk.markUnsaved();
				}
				saveRelitArea(level, inFlightChunk.retainedChunks(), savedThisTick, inFlightChunks);
				relit++;
				finished = true;
			} catch (CompletionException exception) {
				finished = true;
				Throwable cause = unwrap(exception);
				LightenUpChunks.LOGGER.error(
					"Failed to relight chunk {} in {}",
					chunkPos,
					LucDimensions.asString(level),
					cause
				);
				failures.add(new FailureRecord(chunkPos, failureMessage(cause)));
			} catch (RuntimeException exception) {
				finished = true;
				LightenUpChunks.LOGGER.error(
					"Failed to relight chunk {} in {}",
					chunkPos,
					LucDimensions.asString(level),
					exception
				);
				failures.add(new FailureRecord(chunkPos, failureMessage(exception)));
			} finally {
				if (finished) {
					iterator.remove();
					releaseChunk(level, chunkPos, inFlightChunk);
				}
			}
		}
		return new ProcessResult(relit, skipped, failures);
	}

	public void releaseAll(ServerLevel level, Map<ChunkPos, InFlightChunk> inFlightChunks) {
		for (Map.Entry<ChunkPos, InFlightChunk> entry : inFlightChunks.entrySet()) {
			releaseChunk(level, entry.getKey(), entry.getValue());
		}
		inFlightChunks.clear();
	}

	private void releaseChunk(ServerLevel level, ChunkPos chunkPos, InFlightChunk inFlightChunk) {
		inFlightChunk.cancelPendingWork();
		if (inFlightChunk.loadStarted()) {
			// Keep the no-generation veto until vanilla load work has settled (cancelling our wrapper does not stop it).
			ChunkMap chunkMap = level.getChunkSource().chunkMap;
			List<ChunkPos> claimed = chunkArea(chunkPos, PREFLIGHT_RADIUS);
			inFlightChunk.settledFuture.whenComplete((ignored, error) -> LucNoGeneration.release(chunkMap, claimed));
			releaseRetainedAreaTickets(level, inFlightChunk.retainedChunks());
			releaseLightData(level, inFlightChunk.retainedChunks());
		}
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

	private void saveRelitArea(ServerLevel level, List<ChunkPos> retainedChunks, Set<ChunkPos> savedThisTick, Map<ChunkPos, InFlightChunk> inFlightChunks) {
		ServerChunkCache chunkSource = level.getChunkSource();
		ChunkMapInvoker chunkMap = (ChunkMapInvoker) chunkSource.chunkMap;
		for (ChunkPos retainedChunkPos : retainedChunks) {
			// A neighbour mid-relight has its light cleared; saving/ingesting it now would store dark light. It saves itself when done.
			InFlightChunk neighbour = inFlightChunks.get(retainedChunkPos);
			if (neighbour != null && neighbour.hasRelightFuture() && !neighbour.relightFuture().isDone()) {
				continue;
			}
			if (!savedThisTick.add(retainedChunkPos) || !shouldSaveChunk(level, retainedChunkPos)) {
				continue;
			}
			LevelChunk retainedChunk = chunkSource.getChunkNow(retainedChunkPos.x(), retainedChunkPos.z());
			if (retainedChunk != null) {
				retainedChunk.markUnsaved();
				chunkMap.lightenupchunks$saveChunk(retainedChunk);
				broadcastRelitChunk(level, retainedChunk);
				VoxyCompat.notifyChunkRelit(level, retainedChunk);
			}
		}
	}

	private void broadcastRelitChunk(ServerLevel level, LevelChunk chunk) {
		for (ServerPlayer player : level.players()) {
			if (player.getChunkTrackingView().contains(chunk.getPos())) {
				player.connection.chunkSender.markChunkPendingToSend(chunk);
			}
		}
	}

	private List<ChunkPos> ticketArea(ChunkPos centerChunkPos) {
		return chunkArea(centerChunkPos, RETAINED_AREA_RADIUS);
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

	private List<CompletableFuture<?>> loadRetainedArea(ServerLevel level, List<ChunkPos> chunkPositions) {
		List<CompletableFuture<?>> loadFutures = new ArrayList<>(chunkPositions.size());
		for (ChunkPos chunkPos : chunkPositions) {
			retainTicket(level, chunkPos);
			loadFutures.add(level.getChunkSource().addTicketAndLoadWithRadius(LOAD_TICKET_TYPE, chunkPos, LOAD_TICKET_RADIUS));
		}
		return loadFutures;
	}

	private void retainTicket(ServerLevel level, ChunkPos chunkPos) {
		Integer count = retainedTicketCounts.get(chunkPos);
		if (count == null) {
			retainedTicketCounts.put(chunkPos, 1);
			return;
		}
		retainedTicketCounts.put(chunkPos, count + 1);
	}

	private void releaseRetainedAreaTickets(ServerLevel level, List<ChunkPos> chunkPositions) {
		for (ChunkPos chunkPos : chunkPositions) {
			Integer count = retainedTicketCounts.get(chunkPos);
			if (count == null) {
				continue;
			}
			if (count > 1) {
				retainedTicketCounts.put(chunkPos, count - 1);
			} else {
				retainedTicketCounts.remove(chunkPos);
				level.getChunkSource().removeTicketWithRadius(LOAD_TICKET_TYPE, chunkPos, LOAD_TICKET_RADIUS);
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

	private enum Preflight { RELIGHT, SKIP }

	/**
	 * Runs off-thread before any ticket is added. Every chunk in the area must be saved as minecraft:full;
	 * a partial chunk would be generated further by the FULL ticket, so the whole area is skipped instead.
	 */
	private CompletableFuture<Preflight> createPreflightFuture(ServerLevel level, LightTask task, ChunkPos chunkPos, List<ChunkPos> area) {
		boolean missingOnly = task.inspectMissingLightOnly();
		String dimension = LucDimensions.asString(level);
		return CompletableFuture.supplyAsync(() -> {
			PersistedLightInspector inspector = inspectorLocal.get();
			try {
				if (fullCache.size() > FULL_CACHE_LIMIT) {
					fullCache.clear();
				}
				boolean needsLight = true;
				for (ChunkPos pos : area) {
					String key = dimension + ":" + pos.x() + "," + pos.z();
					Boolean cached = fullCache.get(key);
					if (cached == null || (pos.equals(chunkPos) && missingOnly)) {
						CompoundTag tag = inspector.read(level, pos);
						cached = PersistedLightInspector.isFull(tag);
						if (cached) {
							fullCache.put(key, true); // only FULL is cached; partial chunks may finish generating later
						}
						if (pos.equals(chunkPos) && cached) {
							needsLight = PersistedLightInspector.lacksStoredLightData(tag, level.dimensionType().hasSkyLight());
						}
					}
					if (!cached) {
						return Preflight.SKIP;
					}
				}
				return missingOnly && !needsLight ? Preflight.SKIP : Preflight.RELIGHT;
			} catch (IOException | RuntimeException exception) {
				LightenUpChunks.LOGGER.warn("Skipping chunk {} in {}: saved data could not be verified as fully generated", chunkPos, dimension, exception);
				return Preflight.SKIP;
			}
		}, inspectionExecutor);
	}

	private boolean allLoaded(ServerLevel level, List<ChunkPos> area) {
		for (ChunkPos pos : area) {
			if (level.getChunkSource().getChunkNow(pos.x(), pos.z()) == null) {
				return false;
			}
		}
		return true;
	}

	private boolean anyReplaced(ServerLevel level, List<ChunkPos> area) {
		for (ChunkPos pos : area) {
			if (!shouldSaveChunk(level, pos)) {
				return true;
			}
		}
		return false;
	}

	private boolean shouldSaveChunk(ServerLevel level, ChunkPos chunkPos) {
		return !LucNoGeneration.wasReplaced(level.getChunkSource().chunkMap, chunkPos); // never overwrite disk data with a vanilla replacement chunk
	}
	private List<ChunkPos> chunkArea(ChunkPos centerChunkPos, int radius) {
		List<ChunkPos> chunkPositions = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				chunkPositions.add(new ChunkPos(centerChunkPos.x() + dx, centerChunkPos.z() + dz));
			}
		}
		return chunkPositions;
	}

	private int maxRelightsStartedThisTick(ServerLevel level) {
		int configured = LucConfigManager.get().maxRelightsPerTick;
		if (configured > 0) {
			return configured;
		}
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

	private static String failureMessage(Throwable throwable) {
		String message = throwable.getMessage();
		if (message == null || message.isBlank()) {
			return throwable.getClass().getSimpleName();
		}
		return throwable.getClass().getSimpleName() + ": " + message;
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
		private final CompletableFuture<Preflight> preflightFuture;
		private CompletableFuture<?> loadFuture;
		private CompletableFuture<ChunkAccess> relightFuture;
		private CompletableFuture<?> settledFuture;

		private InFlightChunk(ChunkPos chunkPos, List<ChunkPos> retainedChunks, CompletableFuture<Preflight> preflightFuture) {
			this.chunkPos = chunkPos;
			this.retainedChunks = retainedChunks;
			this.preflightFuture = preflightFuture;
		}

		public ChunkPos chunkPos() {
			return chunkPos;
		}

		public List<ChunkPos> retainedChunks() {
			return retainedChunks;
		}

		private boolean preflightDone() {
			return preflightFuture.isDone();
		}

		private Preflight preflight() {
			return preflightFuture.join();
		}

		private CompletableFuture<?> loadFuture() {
			return loadFuture;
		}

		private boolean loadStarted() {
			return loadFuture != null;
		}

		private void startLoad(List<CompletableFuture<?>> loads) {
			this.loadFuture = CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new));
			// Separate from loadFuture (which we cancel): completes only once vanilla load work has really settled.
			this.settledFuture = CompletableFuture.allOf(loads.stream().map(load -> load.handle((value, error) -> null)).toArray(CompletableFuture[]::new));
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

		private void cancelPendingWork() {
			preflightFuture.cancel(true);
			if (loadFuture != null) {
				loadFuture.cancel(true);
			}
			if (relightFuture != null) {
				relightFuture.cancel(true);
			}
		}
	}

	public record FailureRecord(ChunkPos chunkPos, String message) {
	}

	public record ProcessResult(int relit, int skipped, List<FailureRecord> failures) {
	}
}
