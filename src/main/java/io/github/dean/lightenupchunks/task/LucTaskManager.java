package io.github.dean.lightenupchunks.task;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.dean.lightenupchunks.CommandFeedback;
import io.github.dean.lightenupchunks.LucConfigManager;
import io.github.dean.lightenupchunks.LucDimensions;
import io.github.dean.lightenupchunks.LightenUpChunks;
import io.github.dean.lightenupchunks.ServerLevels;
import io.github.dean.lightenupchunks.TextComponents;
import io.github.dean.lightenupchunks.WorldPathResolver;
import io.github.dean.lightenupchunks.scanner.BoxScanner;
import io.github.dean.lightenupchunks.scanner.ChunkRegionScanner;
import io.github.dean.lightenupchunks.scanner.RadiusScanner;
import io.github.dean.lightenupchunks.scanner.WorldScanner;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

public final class LucTaskManager {
	private static final Map<MinecraftServer, LucTaskManager> INSTANCES = Collections.synchronizedMap(new WeakHashMap<>());
	private static final int MAX_IN_FLIGHT = Math.max(48, Math.min(256, Runtime.getRuntime().availableProcessors() * 16));
	private static final long SAVE_FLUSH_INTERVAL_MILLIS = 30000L;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final DecimalFormat RATE_FORMAT = new DecimalFormat("0.00");

	private final MinecraftServer server;
	private final Clock clock;
	private final ChunkLightWorker worker;
	private final LucProgressBar progressBar;
	private Path stateFile;

	private LightTask activeTask;
	private final Map<ChunkPos, ChunkLightWorker.InFlightChunk> inFlightChunks = new LinkedHashMap<>();
	private PendingAction pendingAction = PendingAction.NONE;
	private long lastChunkSaveFlushAtMillis;
	private boolean pendingChunkSaveFlush;
	private String configuredDimension = LucDimensions.OVERWORLD;
	private ConfiguredScan configuredScan = ConfiguredScan.world();

	private LucTaskManager(MinecraftServer server) {
		this.server = server;
		this.clock = Clock.systemUTC();
		this.worker = new ChunkLightWorker();
		this.progressBar = new LucProgressBar();
	}

	public static LucTaskManager get(MinecraftServer server) {
		return INSTANCES.computeIfAbsent(server, LucTaskManager::new);
	}

	public static void onServerStarted(MinecraftServer server) {
		get(server);
	}

	public static void onEndServerTick(MinecraftServer server) {
		LucTaskManager manager = INSTANCES.get(server);
		if (manager != null) {
			manager.tick();
		}
	}

	public static void onServerStopping(MinecraftServer server) {
		LucTaskManager manager = INSTANCES.remove(server);
		if (manager != null) {
			manager.persistActiveTaskForShutdown();
			manager.worker.close();
		}
	}

	public int startConfigured(CommandSourceStack source) throws CommandSyntaxException {
		return startTask(source, configuredDimension, configuredScan);
	}

	public int startWorld(CommandSourceStack source, String dimension) throws CommandSyntaxException {
		configuredDimension = dimension;
		configuredScan = ConfiguredScan.world();
		return startTask(source, dimension, configuredScan);
	}

	public int startRadius(CommandSourceStack source, String dimension, int radius, int centerX, int centerZ) throws CommandSyntaxException {
		configuredDimension = dimension;
		configuredScan = ConfiguredScan.radius(radius, centerX, centerZ);
		return startTask(source, dimension, configuredScan);
	}

	public int startRegion(CommandSourceStack source, String dimension, int x1, int z1, int x2, int z2) throws CommandSyntaxException {
		configuredDimension = dimension;
		configuredScan = ConfiguredScan.box(x1, z1, x2, z2);
		return startTask(source, dimension, configuredScan);
	}

	public int pause(CommandSourceStack source) throws CommandSyntaxException {
		if (activeTask == null) {
			throw commandError("No active relight task is running.");
		}
		if (pendingAction == PendingAction.PAUSE) {
			throw commandError("A pause has already been requested.");
		}
		if (pendingAction == PendingAction.CANCEL) {
			throw commandError("Cancellation is already draining in-flight chunks.");
		}
		if (!inFlightChunks.isEmpty()) {
			pendingAction = PendingAction.PAUSE;
			CommandFeedback.sendSuccess(
				source,
				"Pause requested. Waiting for " + inFlightChunks.size() + " in-flight chunk(s) to finish before saving luc_state.json.",
				true
			);
			return 1;
		}

		requeueInFlightChunks(activeTask);
		try {
			ServerLevel level = findLevel(activeTask.getDimensionId());
			if (level != null) {
				flushRelitChunks(level, clock.millis());
			}
			saveTask(activeTask);
		} catch (IOException exception) {
			throw commandError("Failed to save luc_state.json: " + exception.getMessage());
		}

		CommandFeedback.sendSuccess(source, "Paused relight task and wrote luc_state.json.", true);
		progressBar.stop();
		activeTask = null;
		pendingAction = PendingAction.NONE;
		return 1;
	}

	public int resume(CommandSourceStack source) throws CommandSyntaxException {
		if (activeTask != null) {
			throw commandError("A relight task is already running.");
		}
		if (!Files.exists(stateFile())) {
			throw commandError("No saved relight task was found.");
		}

		try {
			activeTask = loadTask();
			inFlightChunks.clear();
			pendingAction = PendingAction.NONE;
			lastChunkSaveFlushAtMillis = clock.millis();
			pendingChunkSaveFlush = false;
			progressBar.start(server);
			progressBar.update(activeTask, clock.millis(), inFlightChunks.size(), currentDisplayChunk());
		} catch (IOException | IllegalArgumentException exception) {
			throw commandError("Failed to resume luc_state.json: " + exception.getMessage());
		}

		CommandFeedback.sendSuccess(
			source,
			"Resumed " + activeTask.getMode().serializedName() + " task in " + activeTask.getDimensionId(),
			true
		);
		return 1;
	}

	public int cancel(CommandSourceStack source) {
		if (activeTask != null) {
			if (!inFlightChunks.isEmpty()) {
				pendingAction = PendingAction.CANCEL;
				CommandFeedback.sendSuccess(
					source,
					"Cancellation requested. Waiting for " + inFlightChunks.size() + " in-flight chunk(s) to finish.",
					true
				);
				return 1;
			}
			requeueInFlightChunks(activeTask);
			ServerLevel level = findLevel(activeTask.getDimensionId());
			if (level != null) {
				flushRelitChunks(level, clock.millis());
			}
		}
		progressBar.stop();
		activeTask = null;
		pendingAction = PendingAction.NONE;
		pendingChunkSaveFlush = false;
		try {
			Files.deleteIfExists(stateFile());
		} catch (IOException exception) {
			LightenUpChunks.LOGGER.warn("Failed to delete {}", stateFile(), exception);
		}

		CommandFeedback.sendSuccess(source, "Cancelled the active or saved relight task.", true);
		return 1;
	}

	public int status(CommandSourceStack source) {
		CommandFeedback.sendSuccess(source, describeStatus(), false);
		return 1;
	}

	public int resetSelection(CommandSourceStack source) {
		configuredScan = ConfiguredScan.world();
		CommandFeedback.sendSuccess(source, "Reset selection to the whole loaded world in " + configuredDimension + ".", false);
		return 1;
	}

	public int selection(CommandSourceStack source) {
		CommandFeedback.sendSuccess(source, describeSelection(), false);
		return 1;
	}

	public int overview(CommandSourceStack source) {
		CommandFeedback.sendSuccess(source, describeSelection() + " | " + describeStatus(), false);
		return 1;
	}

	public int configureWorld(CommandSourceStack source, String dimension) throws CommandSyntaxException {
		requireLevel(dimension);
		configuredDimension = dimension;
		configuredScan = ConfiguredScan.world();
		CommandFeedback.sendSuccess(source, "Configured world scan for " + dimension + ".", false);
		return 1;
	}

	public int configureRadius(CommandSourceStack source, int radius, int centerX, int centerZ) {
		configuredScan = ConfiguredScan.radius(radius, centerX, centerZ);
		CommandFeedback.sendSuccess(
			source,
			"Configured radius scan: r=" + radius + " blocks at (" + centerX + ", " + centerZ + ").",
			false
		);
		return 1;
	}

	public int configureRegion(CommandSourceStack source, int x1, int z1, int x2, int z2) {
		configuredScan = ConfiguredScan.box(x1, z1, x2, z2);
		CommandFeedback.sendSuccess(
			source,
			"Configured region scan from block (" + x1 + ", " + z1 + ") to (" + x2 + ", " + z2 + ").",
			false
		);
		return 1;
	}

	private int startTask(CommandSourceStack source, String dimension, ConfiguredScan scan) throws CommandSyntaxException {
		if (activeTask != null) {
			throw commandError("A relight task is already running. Use /luc pause or /luc cancel first.");
		}

		try {
			ServerLevel level = requireLevel(dimension);
			ChunkRegionScanner scanner = switch (scan.kind()) {
				case WORLD -> new WorldScanner();
				case RADIUS -> new RadiusScanner(scan.a(), scan.b(), scan.c());
				case BOX -> new BoxScanner(scan.a(), scan.b(), scan.c(), scan.d());
			};

			List<ChunkPos> chunks = scanner.scan(level);

			if (chunks.isEmpty()) {
				throw commandError("No existing chunks matched that request. Radius and region coordinates use block/world coordinates.");
			}

			try {
				Files.deleteIfExists(stateFile());
			} catch (IOException exception) {
				LightenUpChunks.LOGGER.warn("Failed to clear stale state file {}", stateFile(), exception);
			}

			ArrayDeque<ChunkPos> queue = createTaskQueue(scan.kind(), chunks);
			activeTask = new LightTask(
				dimension,
				scan.kind().mode(),
				shouldInspectMissingLightOnly(scan.kind()),
				queue,
				queue.size(),
				0L,
				clock.millis()
			);
			lastChunkSaveFlushAtMillis = clock.millis();
			pendingChunkSaveFlush = false;
			progressBar.start(server);
			progressBar.update(activeTask, clock.millis(), inFlightChunks.size(), currentDisplayChunk());
			CommandFeedback.sendSuccess(
				source,
				"Started " + scan.kind().mode().serializedName() + " relight task in " + dimension +
					" for " + queue.size() + " relight step(s)" +
					(twoPassRelight(scan.kind()) ? " across 2 lighting passes" : "") +
					" with up to " + MAX_IN_FLIGHT + " chunks in flight.",
				true
			);
			return 1;
		} catch (CommandSyntaxException exception) {
			throw exception;
		} catch (IOException exception) {
			throw commandError("Failed to scan region files: " + exception.getMessage());
		} catch (RuntimeException exception) {
			LightenUpChunks.LOGGER.error("Failed to start relight task in {}", dimension, exception);
			throw commandError("Failed to start relight task: " + exception.getClass().getSimpleName());
		}
	}

	private void tick() {
		if (activeTask == null) {
			return;
		}

		ServerLevel level = findLevel(activeTask.getDimensionId());
		if (level == null) {
			LightenUpChunks.LOGGER.error("Pausing task because {} is not available", activeTask.getDimensionId());
			persistAndClearTask();
			return;
		}

		try {
			if (pendingAction == PendingAction.NONE) {
				worker.submitChunks(level, activeTask, inFlightChunks, currentMaxInFlight());
			}
			int processed = worker.processLoadedChunks(level, inFlightChunks);
			long nowMillis = clock.millis();
			activeTask.recordProcessed(processed, nowMillis);
			if (processed > 0) {
				pendingChunkSaveFlush = true;
			}
			if (pendingChunkSaveFlush && nowMillis - lastChunkSaveFlushAtMillis >= SAVE_FLUSH_INTERVAL_MILLIS) {
				flushRelitChunks(level, nowMillis);
			}
			progressBar.update(activeTask, nowMillis, inFlightChunks.size(), currentDisplayChunk());
			if (pendingAction == PendingAction.PAUSE && inFlightChunks.isEmpty()) {
				LightenUpChunks.LOGGER.info("Paused relight task in {}", activeTask.getDimensionId());
				flushRelitChunks(level, nowMillis);
				saveTask(activeTask);
				progressBar.stop();
				activeTask = null;
				pendingAction = PendingAction.NONE;
				return;
			}
			if (pendingAction == PendingAction.CANCEL && inFlightChunks.isEmpty()) {
				LightenUpChunks.LOGGER.info("Cancelled relight task in {}", activeTask.getDimensionId());
				flushRelitChunks(level, nowMillis);
				progressBar.stop();
				activeTask = null;
				pendingAction = PendingAction.NONE;
				Files.deleteIfExists(stateFile());
				return;
			}
			if (pendingAction == PendingAction.NONE && activeTask.isComplete() && inFlightChunks.isEmpty()) {
				LightenUpChunks.LOGGER.info("Completed relight task in {}", activeTask.getDimensionId());
				flushRelitChunks(level, nowMillis);
				progressBar.stop();
				activeTask = null;
				pendingAction = PendingAction.NONE;
				Files.deleteIfExists(stateFile());
			}
		} catch (Exception exception) {
			LightenUpChunks.LOGGER.error("Task crashed; saving luc_state.json for resume", exception);
			persistAndClearTask();
		}
	}

	private void persistActiveTaskForShutdown() {
		if (activeTask != null) {
			persistAndClearTask();
		}
	}

	private void persistAndClearTask() {
		if (activeTask == null) {
			return;
		}

		requeueInFlightChunks(activeTask);
		progressBar.stop();
		try {
			ServerLevel level = findLevel(activeTask.getDimensionId());
			if (level != null) {
				flushRelitChunks(level, clock.millis());
			}
			saveTask(activeTask);
		} catch (IOException exception) {
			LightenUpChunks.LOGGER.error("Failed to persist luc_state.json", exception);
		} finally {
			activeTask = null;
			pendingAction = PendingAction.NONE;
			pendingChunkSaveFlush = false;
		}
	}

	private ServerLevel requireLevel(String dimension) throws CommandSyntaxException {
		ServerLevel level = findLevel(dimension);
		if (level == null) {
			throw commandError("Dimension is not available on this server: " + dimension);
		}
		return level;
	}

	private ServerLevel requireLevelUnchecked(String dimension) {
		ServerLevel level = findLevel(dimension);
		if (level == null) {
			throw new IllegalArgumentException("Dimension is not available on this server: " + dimension);
		}
		return level;
	}

	private ServerLevel findLevel(String dimension) {
		String normalized = LucDimensions.normalize(dimension);
		for (ServerLevel level : ServerLevels.all(server)) {
			if (LucDimensions.asString(level).equals(normalized)) {
				return level;
			}
		}
		return null;
	}

	private String describeStatus() {
		if (activeTask != null) {
			long now = clock.millis();
			double rate = activeTask.getRecentChunksPerSecond(now);
			long remaining = activeTask.getRemainingChunks() + inFlightChunks.size();
			String eta = rate > 0.0D ? formatDuration(Duration.ofSeconds((long) Math.ceil(remaining / rate))) : "calculating";
			return "LUC " + pendingAction.label() + " | dim=" + activeTask.getDimensionId() +
				" | mode=" + activeTask.getMode().serializedName() +
				" | relight=" + describeRelightMode(activeTask.getMode(), activeTask.inspectMissingLightOnly()) +
				" | progress=" + activeTask.getProcessedChunks() + "/" + activeTask.getTotalChunks() +
				" (" + percentage(activeTask.getProcessedChunks(), activeTask.getTotalChunks()) + ")" +
				" | in_flight=" + inFlightChunks.size() +
				" | rate=" + RATE_FORMAT.format(rate) + " chunks/s" +
				" | eta=" + eta;
		}

		if (Files.exists(stateFile())) {
			try {
				SavedTaskState saved = readSavedState();
				return "LUC paused | dim=" + saved.dimension +
					" | mode=" + saved.mode +
					" | relight=" + describeRelightMode(TaskMode.fromSerializedName(saved.mode), saved.inspect_missing_light_only == null || saved.inspect_missing_light_only) +
					" | progress=" + saved.processed + "/" + saved.total +
					" | remaining=" + saved.remaining_chunks.size();
			} catch (IOException | IllegalArgumentException exception) {
				return "LUC paused | luc_state.json exists but could not be read: " + exception.getMessage();
			}
		}

		return "LUC idle | configured dim=" + configuredDimension +
			" | mode=" + configuredScan.describe();
	}

	private String describeSelection() {
		return "LUC selection | dim=" + configuredDimension + " | mode=" + configuredScan.describe();
	}

	public boolean hasActiveTask() {
		return activeTask != null;
	}

	private void saveTask(LightTask task) throws IOException {
		SavedTaskState state = new SavedTaskState();
		state.dimension = task.getDimensionId();
		state.mode = task.getMode().serializedName();
		state.inspect_missing_light_only = task.inspectMissingLightOnly();
		state.processed = task.getProcessedChunks();
		state.total = task.getTotalChunks();
		state.remaining_chunks = task.snapshotRemainingChunks();

		Path stateFile = stateFile();
		Files.createDirectories(stateFile.getParent());
		try (Writer writer = Files.newBufferedWriter(stateFile)) {
			GSON.toJson(state, writer);
		}
	}

	private LightTask loadTask() throws IOException {
		SavedTaskState state = readSavedState();
		String dimension = LucDimensions.normalize(state.dimension);
		requireLevelUnchecked(dimension);

		ArrayDeque<ChunkPos> queue = new ArrayDeque<>(state.remaining_chunks.size());
		for (int[] entry : state.remaining_chunks) {
			if (entry.length >= 2) {
				queue.addLast(new ChunkPos(entry[0], entry[1]));
			}
		}
		boolean inspectMissingLightOnly = state.inspect_missing_light_only != null
			? state.inspect_missing_light_only
			: TaskMode.fromSerializedName(state.mode) == TaskMode.WORLD && LucConfigManager.get().calculateOnlyEmptyLightChunks;
		return new LightTask(
			dimension,
			TaskMode.fromSerializedName(state.mode),
			inspectMissingLightOnly,
			queue,
			state.total,
			state.processed,
			clock.millis()
		);
	}

	private SavedTaskState readSavedState() throws IOException {
		try (Reader reader = Files.newBufferedReader(stateFile())) {
			SavedTaskState state = GSON.fromJson(reader, SavedTaskState.class);
			if (state == null || state.dimension == null || state.mode == null || state.remaining_chunks == null) {
				throw new IllegalArgumentException("State file is missing required fields");
			}
			return state;
		}
	}

	private static String percentage(long value, long total) {
		if (total <= 0L) {
			return "0.0%";
		}
		return String.format(Locale.ROOT, "%.1f%%", (value * 100.0D) / total);
	}

	private static String formatDuration(Duration duration) {
		long seconds = Math.max(duration.getSeconds(), 0L);
		long hours = seconds / 3600L;
		long minutes = (seconds % 3600L) / 60L;
		long remainingSeconds = seconds % 60L;
		if (hours > 0L) {
			return hours + "h " + minutes + "m";
		}
		if (minutes > 0L) {
			return minutes + "m " + remainingSeconds + "s";
		}
		return remainingSeconds + "s";
	}

	private static CommandSyntaxException commandError(String message) {
		return new SimpleCommandExceptionType(TextComponents.literal(message)).create();
	}

	public void onPlayerJoin(ServerPlayer player) {
		if (activeTask != null) {
			progressBar.addPlayer(player);
		}
	}

	private Path stateFile() {
		if (stateFile == null) {
			stateFile = WorldPathResolver.resolveWorldRoot(server).resolve("luc_state.json");
		}
		return stateFile;
	}

	private void requeueInFlightChunks(LightTask task) {
		if (task == null || inFlightChunks.isEmpty()) {
			return;
		}

		List<ChunkPos> chunksToReturn = List.copyOf(inFlightChunks.keySet());
		ServerLevel level = findLevel(task.getDimensionId());
		if (level != null) {
			worker.releaseAll(level, inFlightChunks);
		}
		task.returnChunks(chunksToReturn);
		inFlightChunks.clear();
	}

	private ChunkPos currentDisplayChunk() {
		return inFlightChunks.isEmpty() ? null : inFlightChunks.keySet().iterator().next();
	}

	private void flushRelitChunks(ServerLevel level, long nowMillis) {
		if (!pendingChunkSaveFlush) {
			lastChunkSaveFlushAtMillis = nowMillis;
			return;
		}

		level.getChunkSource().save(false);
		lastChunkSaveFlushAtMillis = nowMillis;
		pendingChunkSaveFlush = false;
	}

	private int currentMaxInFlight() {
		double averageMspt = server.getAverageTickTimeNanos() / 1_000_000.0D;
		if (averageMspt >= 45.0D) {
			return 16;
		}
		if (averageMspt >= 35.0D) {
			return 32;
		}
		if (averageMspt >= 25.0D) {
			return 48;
		}
		if (averageMspt >= 18.0D) {
			return Math.min(80, MAX_IN_FLIGHT);
		}
		if (averageMspt >= 14.0D) {
			return Math.min(128, MAX_IN_FLIGHT);
		}
		return MAX_IN_FLIGHT;
	}

	private boolean shouldInspectMissingLightOnly(ScanKind scanKind) {
		return scanKind == ScanKind.WORLD && LucConfigManager.get().calculateOnlyEmptyLightChunks;
	}

	private ArrayDeque<ChunkPos> createTaskQueue(ScanKind scanKind, List<ChunkPos> chunks) {
		ArrayDeque<ChunkPos> queue = new ArrayDeque<>(chunks);
		if (twoPassRelight(scanKind)) {
			queue.addAll(chunks);
		}
		return queue;
	}

	private boolean twoPassRelight(ScanKind scanKind) {
		return scanKind == ScanKind.RADIUS || scanKind == ScanKind.BOX;
	}

	private String describeRelightMode(TaskMode mode, boolean inspectMissingLightOnly) {
		if (inspectMissingLightOnly) {
			return "missing-light-only";
		}
		if (mode == TaskMode.RADIUS || mode == TaskMode.BOX) {
			return "full-2-pass";
		}
		return "full";
	}

	private static final class SavedTaskState {
		String dimension;
		String mode;
		Boolean inspect_missing_light_only;
		long processed;
		long total;
		List<int[]> remaining_chunks;
	}

	private enum PendingAction {
		NONE("active"),
		PAUSE("pausing"),
		CANCEL("cancelling");

		private final String label;

		PendingAction(String label) {
			this.label = label;
		}

		private String label() {
			return label;
		}
	}

	private record ConfiguredScan(ScanKind kind, int a, int b, int c, int d) {
		static ConfiguredScan world() {
			return new ConfiguredScan(ScanKind.WORLD, 0, 0, 0, 0);
		}

		static ConfiguredScan radius(int radius, int centerX, int centerZ) {
			return new ConfiguredScan(ScanKind.RADIUS, radius, centerX, centerZ, 0);
		}

		static ConfiguredScan box(int x1, int z1, int x2, int z2) {
			return new ConfiguredScan(ScanKind.BOX, x1, z1, x2, z2);
		}

		String describe() {
			return switch (kind) {
				case WORLD -> "world";
				case RADIUS -> "radius(r=" + a + " blocks, x=" + b + ", z=" + c + ")";
				case BOX -> "region(blocks " + a + ", " + b + " -> " + c + ", " + d + ")";
			};
		}
	}

	private enum ScanKind {
		WORLD(TaskMode.WORLD),
		RADIUS(TaskMode.RADIUS),
		BOX(TaskMode.BOX);

		private final TaskMode mode;

		ScanKind(TaskMode mode) {
			this.mode = mode;
		}

		private TaskMode mode() {
			return mode;
		}
	}
}
