package io.github.dean.lightenupchunks.task;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.dean.lightenupchunks.CommandFeedback;
import io.github.dean.lightenupchunks.LucConfig;
import io.github.dean.lightenupchunks.LucConfigManager;
import io.github.dean.lightenupchunks.LucDimensions;
import io.github.dean.lightenupchunks.LightenUpChunks;
import io.github.dean.lightenupchunks.ServerLevels;
import io.github.dean.lightenupchunks.TextComponents;
import io.github.dean.lightenupchunks.WorldPathResolver;
import io.github.dean.lightenupchunks.scanner.BoxScanner;
import io.github.dean.lightenupchunks.scanner.ChunkRegionScanner;
import io.github.dean.lightenupchunks.scanner.RegionFileProbe;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

public final class LucTaskManager {
	private static final Map<MinecraftServer, LucTaskManager> INSTANCES = Collections.synchronizedMap(new WeakHashMap<>());
	private static final int MAX_IN_FLIGHT = Math.max(48, Math.min(256, Runtime.getRuntime().availableProcessors() * 16));
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
	private RelightMode configuredRelightMode = configuredDefaultRelightMode();
	private boolean hasConfiguredSelection;

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
		TaskSelection selection = defaultSelection(source);
		return startTask(source, selection.dimension(), selection.scan(), configuredRelightMode);
	}

	public int startWorld(CommandSourceStack source, String dimension) throws CommandSyntaxException {
		configuredDimension = dimension;
		configuredScan = ConfiguredScan.world();
		hasConfiguredSelection = true;
		return startTask(source, dimension, configuredScan, configuredRelightMode);
	}

	public int startRadius(CommandSourceStack source, String dimension, int radius, int centerX, int centerZ) throws CommandSyntaxException {
		configuredDimension = dimension;
		configuredScan = ConfiguredScan.radius(radius, centerX, centerZ);
		hasConfiguredSelection = true;
		return startTask(source, dimension, configuredScan, configuredRelightMode);
	}

	public int startRegion(CommandSourceStack source, String dimension, int x1, int z1, int x2, int z2) throws CommandSyntaxException {
		configuredDimension = dimension;
		configuredScan = ConfiguredScan.box(x1, z1, x2, z2);
		hasConfiguredSelection = true;
		return startTask(source, dimension, configuredScan, configuredRelightMode);
	}

	public int previewConfigured(CommandSourceStack source) throws CommandSyntaxException {
		TaskSelection selection = defaultSelection(source);
		return previewTask(source, selection.dimension(), selection.scan(), configuredRelightMode);
	}

	public int previewWorld(CommandSourceStack source, String dimension) throws CommandSyntaxException {
		configuredDimension = dimension;
		configuredScan = ConfiguredScan.world();
		hasConfiguredSelection = true;
		return previewTask(source, dimension, configuredScan, configuredRelightMode);
	}

	public int previewRadius(CommandSourceStack source, String dimension, int radius, int centerX, int centerZ) throws CommandSyntaxException {
		configuredDimension = dimension;
		configuredScan = ConfiguredScan.radius(radius, centerX, centerZ);
		hasConfiguredSelection = true;
		return previewTask(source, dimension, configuredScan, configuredRelightMode);
	}

	public int previewRegion(CommandSourceStack source, String dimension, int x1, int z1, int x2, int z2) throws CommandSyntaxException {
		configuredDimension = dimension;
		configuredScan = ConfiguredScan.box(x1, z1, x2, z2);
		hasConfiguredSelection = true;
		return previewTask(source, dimension, configuredScan, configuredRelightMode);
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

		int released = inFlightChunks.size();
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

		CommandFeedback.sendSuccess(
			source,
			"Paused relight task and wrote luc_state.json." + (released > 0 ? " Released " + released + " in-flight chunk(s)." : ""),
			true
		);
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
			"Resumed " + activeTask.getMode().serializedName() + " task in " + activeTask.getDimensionId() +
				" with relight=" + activeTask.getRelightMode().serializedName(),
			true
		);
		return 1;
	}

	public int cancel(CommandSourceStack source) {
		if (activeTask != null) {
			int released = inFlightChunks.size();
			requeueInFlightChunks(activeTask);
			ServerLevel level = findLevel(activeTask.getDimensionId());
			if (level != null) {
				flushRelitChunks(level, clock.millis());
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
			CommandFeedback.sendSuccess(
				source,
				"Cancelled the active relight task." + (released > 0 ? " Released " + released + " in-flight chunk(s)." : ""),
				true
			);
			return 1;
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

	public int dimensions(CommandSourceStack source) {
		CommandFeedback.sendSuccess(source, "LUC dimensions | " + String.join(", ", availableDimensions()), false);
		return 1;
	}

	public int resetSelection(CommandSourceStack source) {
		configuredDimension = LucDimensions.asString(source.getLevel());
		configuredScan = ConfiguredScan.world();
		hasConfiguredSelection = false;
		CommandFeedback.sendSuccess(
			source,
			"Reset selection. /luc start will scan the whole current world/dimension (" + configuredDimension + ") with relight=" + configuredRelightMode.serializedName() + ".",
			false
		);
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

	public int relightMode(CommandSourceStack source) {
		CommandFeedback.sendSuccess(source, "LUC relight mode | configured=" + configuredRelightMode.serializedName(), false);
		return 1;
	}

	public int configureRelightMode(CommandSourceStack source, RelightMode relightMode) {
		configuredRelightMode = relightMode;
		CommandFeedback.sendSuccess(source, "Configured relight mode: " + relightMode.serializedName() + ".", false);
		return 1;
	}

	public int configureWorld(CommandSourceStack source, String dimension) throws CommandSyntaxException {
		requireLevel(dimension);
		configuredDimension = dimension;
		configuredScan = ConfiguredScan.world();
		hasConfiguredSelection = true;
		CommandFeedback.sendSuccess(
			source,
			"Configured world scan for " + dimension + " with relight=" + configuredRelightMode.serializedName() + ".",
			false
		);
		return 1;
	}

	public int configureRadius(CommandSourceStack source, int radius, int centerX, int centerZ) {
		configuredDimension = LucDimensions.asString(source.getLevel());
		configuredScan = ConfiguredScan.radius(radius, centerX, centerZ);
		hasConfiguredSelection = true;
		CommandFeedback.sendSuccess(
			source,
			"Configured radius scan in " + configuredDimension + ": r=" + radius + " blocks at (" + centerX + ", " + centerZ + ") with relight=" + configuredRelightMode.serializedName() + ".",
			false
		);
		return 1;
	}

	public int configureRegion(CommandSourceStack source, int x1, int z1, int x2, int z2) {
		configuredDimension = LucDimensions.asString(source.getLevel());
		configuredScan = ConfiguredScan.box(x1, z1, x2, z2);
		hasConfiguredSelection = true;
		CommandFeedback.sendSuccess(
			source,
			"Configured region scan in " + configuredDimension + " from block (" + x1 + ", " + z1 + ") to (" + x2 + ", " + z2 + ") with relight=" + configuredRelightMode.serializedName() + ".",
			false
		);
		return 1;
	}

	private TaskSelection defaultSelection(CommandSourceStack source) {
		if (hasConfiguredSelection) {
			return new TaskSelection(configuredDimension, configuredScan);
		}

		String currentDimension = LucDimensions.asString(source.getLevel());
		configuredDimension = currentDimension;
		configuredScan = ConfiguredScan.world();
		return new TaskSelection(currentDimension, configuredScan);
	}

	private int previewTask(CommandSourceStack source, String dimension, ConfiguredScan scan, RelightMode relightMode) throws CommandSyntaxException {
		try {
			TaskPlan plan = planTask(dimension, scan, relightMode);
			CommandFeedback.sendSuccess(source, plan.previewMessage(describeThroughputSettings()), false);
			return 1;
		} catch (CommandSyntaxException exception) {
			throw exception;
		} catch (IOException exception) {
			throw commandError("Failed to scan region files: " + exception.getMessage());
		}
	}

	private int startTask(CommandSourceStack source, String dimension, ConfiguredScan scan, RelightMode relightMode) throws CommandSyntaxException {
		if (activeTask != null) {
			throw commandError("A relight task is already running. Use /luc pause or /luc cancel first.");
		}

		try {
			TaskPlan plan = planTask(dimension, scan, relightMode);

			try {
				Files.deleteIfExists(stateFile());
			} catch (IOException exception) {
				LightenUpChunks.LOGGER.warn("Failed to clear stale state file {}", stateFile(), exception);
			}

			ArrayDeque<ChunkPos> queue = createTaskQueue(relightMode, plan.runnableChunks());
			activeTask = new LightTask(
				plan.dimension(),
				scan.kind().mode(),
				relightMode,
				queue,
				queue.size(),
				clock.millis()
			);
			lastChunkSaveFlushAtMillis = clock.millis();
			pendingChunkSaveFlush = false;
			progressBar.start(server);
			progressBar.update(activeTask, clock.millis(), inFlightChunks.size(), currentDisplayChunk());
			CommandFeedback.sendSuccess(source, plan.startMessage(describeThroughputSettings()), true);
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
			long nowMillis = clock.millis();
			if (pendingAction == PendingAction.NONE) {
				int skippedOnSubmit = worker.submitChunks(level, activeTask, inFlightChunks, currentMaxInFlight());
				if (skippedOnSubmit > 0) {
					activeTask.recordSkipped(skippedOnSubmit, nowMillis);
				}
			}
			ChunkLightWorker.ProcessResult processed = worker.processLoadedChunks(level, inFlightChunks);
			nowMillis = clock.millis();
			activeTask.recordRelit(processed.relit(), nowMillis);
			activeTask.recordSkipped(processed.skipped(), nowMillis);
			for (ChunkLightWorker.FailureRecord failure : processed.failures()) {
				activeTask.recordFailure(failure.chunkPos(), failure.message(), nowMillis);
			}
			if (processed.relit() > 0) {
				pendingChunkSaveFlush = true;
			}
			if (pendingChunkSaveFlush && nowMillis - lastChunkSaveFlushAtMillis >= saveFlushIntervalMillis()) {
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
				broadcastCompletion(activeTask, nowMillis);
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
			StringBuilder builder = new StringBuilder("LUC ")
				.append(pendingAction.label())
				.append(" | dim=").append(activeTask.getDimensionId())
				.append(" | mode=").append(activeTask.getMode().serializedName())
				.append(" | relight=").append(activeTask.getRelightMode().serializedName())
				.append(" | progress=").append(activeTask.getProcessedChunks()).append("/").append(activeTask.getTotalChunks())
				.append(" (").append(percentage(activeTask.getProcessedChunks(), activeTask.getTotalChunks())).append(")")
				.append(" | relit=").append(activeTask.getRelitChunks())
				.append(" | skipped=").append(activeTask.getSkippedChunks())
				.append(" | failed=").append(activeTask.getFailedChunks())
				.append(" | in_flight=").append(inFlightChunks.size())
				.append(" | rate=").append(RATE_FORMAT.format(rate)).append(" chunks/s")
				.append(" | eta=").append(eta);
			appendLastFailure(builder, activeTask.getLastFailedChunk(), activeTask.getLastFailureMessage());
			return builder.toString();
		}

		if (Files.exists(stateFile())) {
			try {
				SavedTaskState saved = readSavedState();
				RelightMode relightMode = savedRelightMode(saved);
				long relit = firstNonNull(saved.relit, saved.processed, 0L);
				long skipped = firstNonNull(saved.skipped, 0L);
				long failed = firstNonNull(saved.failed, 0L);
				StringBuilder builder = new StringBuilder("LUC paused | dim=")
					.append(saved.dimension)
					.append(" | mode=").append(saved.mode)
					.append(" | relight=").append(relightMode.serializedName())
					.append(" | progress=").append(relit + skipped + failed).append("/").append(saved.total)
					.append(" | relit=").append(relit)
					.append(" | skipped=").append(skipped)
					.append(" | failed=").append(failed)
					.append(" | remaining=").append(saved.remaining_chunks.size());
				appendLastFailure(builder, chunkPos(saved.last_failed_chunk), saved.last_failure_message);
				return builder.toString();
			} catch (IOException | IllegalArgumentException exception) {
				return "LUC paused | luc_state.json exists but could not be read: " + exception.getMessage();
			}
		}

		return "LUC idle | configured dim=" + configuredDimension +
			" | mode=" + configuredScan.describe() +
			" | relight=" + configuredRelightMode.serializedName();
	}

	private String describeSelection() {
		return "LUC selection" + (hasConfiguredSelection ? "" : " (default: /luc start scans the whole current world)") +
			" | dim=" + configuredDimension +
			" | mode=" + configuredScan.describe() +
			" | relight=" + configuredRelightMode.serializedName();
	}

	public boolean hasActiveTask() {
		return activeTask != null;
	}

	private void saveTask(LightTask task) throws IOException {
		SavedTaskState state = new SavedTaskState();
		state.dimension = task.getDimensionId();
		state.mode = task.getMode().serializedName();
		state.relight_mode = task.getRelightMode().serializedName();
		state.inspect_missing_light_only = task.inspectMissingLightOnly();
		state.relit = task.getRelitChunks();
		state.skipped = task.getSkippedChunks();
		state.failed = task.getFailedChunks();
		state.processed = task.getProcessedChunks();
		state.total = task.getTotalChunks();
		state.started_at_millis = task.getStartedAtMillis();
		state.last_progress_at_millis = task.getLastProgressAtMillis();
		state.recent_chunks_per_second = task.getRecentChunksPerSecond(clock.millis());
		state.last_failed_chunk = chunkArray(task.getLastFailedChunk());
		state.last_failure_message = task.getLastFailureMessage();
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
		return new LightTask(
			dimension,
			TaskMode.fromSerializedName(state.mode),
			savedRelightMode(state),
			queue,
			state.total,
			firstNonNull(state.relit, state.processed, 0L),
			firstNonNull(state.skipped, 0L),
			firstNonNull(state.failed, 0L),
			firstNonNull(state.started_at_millis, clock.millis()),
			firstNonNull(state.last_progress_at_millis, clock.millis()),
			state.recent_chunks_per_second != null ? state.recent_chunks_per_second : 0.0D,
			chunkPos(state.last_failed_chunk),
			state.last_failure_message
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

		// Relit chunks are saved explicitly as they complete. Avoid a broad world save
		// here so we never persist unrelated chunks that may have been loaded transiently.
		lastChunkSaveFlushAtMillis = nowMillis;
		pendingChunkSaveFlush = false;
	}

	private int currentMaxInFlight() {
		int configured = LucConfigManager.get().maxInFlightChunks;
		if (configured > 0) {
			return configured;
		}
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

	private ArrayDeque<ChunkPos> createTaskQueue(RelightMode relightMode, List<ChunkPos> chunks) {
		ArrayDeque<ChunkPos> queue = new ArrayDeque<>(chunks);
		if (relightMode.requiresTwoPasses()) {
			queue.addAll(chunks);
		}
		return queue;
	}

	private TaskPlan planTask(String dimension, ConfiguredScan scan, RelightMode relightMode) throws CommandSyntaxException, IOException {
		ServerLevel level = requireLevel(dimension);
		ChunkRegionScanner scanner = switch (scan.kind()) {
			case WORLD -> new WorldScanner();
			case RADIUS -> new RadiusScanner(scan.a(), scan.b(), scan.c());
			case BOX -> new BoxScanner(scan.a(), scan.b(), scan.c(), scan.d());
		};

		List<ChunkPos> matchedChunks = scanner.scan(level);
		if (matchedChunks.isEmpty()) {
			throw commandError("No existing chunks found in " + WorldPathResolver.resolveDimensionRoot(level).resolve("region") + ".");
		}

		RegionFileProbe regionFileProbe = new RegionFileProbe();
		List<ChunkPos> runnableChunks = new ArrayList<>(matchedChunks.size());
		int boundarySkipped = 0;
		for (ChunkPos chunkPos : matchedChunks) {
			if (allChunksExist(level, regionFileProbe, ticketArea(chunkPos))) {
				runnableChunks.add(chunkPos);
			} else {
				boundarySkipped++;
			}
		}
		if (runnableChunks.isEmpty()) {
			throw commandError("All matched chunks were skipped because at least one chunk in their required neighboring load area is missing on disk.");
		}

		return new TaskPlan(dimension, scan, relightMode, matchedChunks.size(), List.copyOf(runnableChunks), boundarySkipped);
	}

	private List<String> availableDimensions() {
		Set<String> dimensions = new LinkedHashSet<>();
		dimensions.add(LucDimensions.OVERWORLD);
		dimensions.add(LucDimensions.NETHER);
		dimensions.add(LucDimensions.END);
		for (ServerLevel level : ServerLevels.all(server)) {
			dimensions.add(LucDimensions.asString(level));
		}
		return List.copyOf(dimensions);
	}

	private String describeThroughputSettings() {
		LucConfig config = LucConfigManager.get();
		return "throughput maxInFlight=" + describeOverride(config.maxInFlightChunks, currentMaxInFlight()) +
			", relightsPerTick=" + describeOverride(config.maxRelightsPerTick, workerMaxRelightsPerTick()) +
			", saveFlush=" + config.saveFlushIntervalSeconds + "s";
	}

	private int workerMaxRelightsPerTick() {
		int configured = LucConfigManager.get().maxRelightsPerTick;
		if (configured > 0) {
			return configured;
		}
		double averageMspt = server.getAverageTickTimeNanos() / 1_000_000.0D;
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

	private long saveFlushIntervalMillis() {
		return LucConfigManager.get().saveFlushIntervalSeconds * 1000L;
	}

	private void broadcastCompletion(LightTask task, long nowMillis) {
		long elapsedSeconds = task.getElapsedSeconds(nowMillis);
		double averageRate = task.getRelitChunks() / (double) elapsedSeconds;
		StringBuilder builder = new StringBuilder("Lit up ")
			.append(formatCount(task.getRelitChunks()))
			.append(" chunks in ")
			.append(formatDuration(Duration.ofSeconds(elapsedSeconds)))
			.append(", averaging around ")
			.append(RATE_FORMAT.format(averageRate))
			.append(" chunks/s");
		if (task.getSkippedChunks() > 0L || task.getFailedChunks() > 0L) {
			builder.append(" | skipped ").append(formatCount(task.getSkippedChunks()))
				.append(" | failed ").append(formatCount(task.getFailedChunks()));
		}
		Component message = TextComponents.literal(builder.toString());
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.sendSystemMessage(message);
		}
	}

	private static String formatCount(long value) {
		return String.format(Locale.ROOT, "%,d", value);
	}

	private static String describeOverride(int configured, int effective) {
		return configured > 0 ? String.valueOf(configured) : "adaptive(" + effective + ")";
	}

	private static void appendLastFailure(StringBuilder builder, ChunkPos chunkPos, String failureMessage) {
		if (chunkPos == null || failureMessage == null || failureMessage.isBlank()) {
			return;
		}
		builder.append(" | last_failed=").append(chunkPos.x()).append(",").append(chunkPos.z()).append(" (").append(failureMessage).append(")");
	}

	private static int[] chunkArray(ChunkPos chunkPos) {
		if (chunkPos == null) {
			return null;
		}
		return new int[] {chunkPos.x(), chunkPos.z()};
	}

	private static ChunkPos chunkPos(int[] value) {
		if (value == null || value.length < 2) {
			return null;
		}
		return new ChunkPos(value[0], value[1]);
	}

	private static RelightMode savedRelightMode(SavedTaskState state) {
		if (state.relight_mode != null) {
			return RelightMode.fromSerializedName(state.relight_mode);
		}
		if (Boolean.TRUE.equals(state.inspect_missing_light_only)) {
			return RelightMode.MISSING_ONLY;
		}
		return TaskMode.fromSerializedName(state.mode) == TaskMode.WORLD ? RelightMode.FULL : RelightMode.FULL_2_PASS;
	}

	private static long firstNonNull(Long primary, long fallback) {
		return primary != null ? primary : fallback;
	}

	private static long firstNonNull(Long primary, Long secondary, long fallback) {
		if (primary != null) {
			return primary;
		}
		if (secondary != null) {
			return secondary;
		}
		return fallback;
	}

	private boolean allChunksExist(ServerLevel level, RegionFileProbe regionFileProbe, List<ChunkPos> chunkPositions) throws IOException {
		for (ChunkPos chunkPos : chunkPositions) {
			if (!regionFileProbe.chunkExists(level, chunkPos)) {
				return false;
			}
		}
		return true;
	}

	private static List<ChunkPos> ticketArea(ChunkPos centerChunkPos) {
		return chunkArea(centerChunkPos, 1);
	}

	private static List<ChunkPos> chunkArea(ChunkPos centerChunkPos, int radius) {
		List<ChunkPos> retainedChunks = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				retainedChunks.add(new ChunkPos(centerChunkPos.x() + dx, centerChunkPos.z() + dz));
			}
		}
		return retainedChunks;
	}

	private static RelightMode configuredDefaultRelightMode() {
		return RelightMode.fromSerializedName(LucConfigManager.get().defaultRelightMode);
	}

	private static final class SavedTaskState {
		String dimension;
		String mode;
		String relight_mode;
		Boolean inspect_missing_light_only;
		Long relit;
		Long skipped;
		Long failed;
		Long processed;
		long total;
		Long started_at_millis;
		Long last_progress_at_millis;
		Double recent_chunks_per_second;
		int[] last_failed_chunk;
		String last_failure_message;
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
				case RADIUS -> "radius(circle r=" + a + " blocks, x=" + b + ", z=" + c + ")";
				case BOX -> "region(blocks " + a + ", " + b + " -> " + c + ", " + d + ")";
			};
		}
	}

	private record TaskPlan(String dimension, ConfiguredScan scan, RelightMode relightMode, int matchedChunks, List<ChunkPos> runnableChunks, int boundarySkipped) {
		private int steps() {
			return relightMode.requiresTwoPasses() ? runnableChunks.size() * 2 : runnableChunks.size();
		}

		String previewMessage(String throughputDescription) {
			return "LUC preview | dim=" + dimension +
				" | mode=" + scan.describe() +
				" | relight=" + relightMode.serializedName() +
				" | matched=" + matchedChunks +
				" | runnable=" + runnableChunks.size() +
				" | boundary_skips=" + boundarySkipped +
				" | relight_steps=" + steps() +
				" | " + throughputDescription;
		}

		String startMessage(String throughputDescription) {
			return "Started " + scan.kind().mode().serializedName() + " relight task in " + dimension +
				" with relight=" + relightMode.serializedName() +
				" for " + runnableChunks.size() + " chunk(s), " + steps() + " relight step(s)" +
				(boundarySkipped > 0 ? ", skipping " + boundarySkipped + " chunk(s) with missing neighbors" : "") +
				" | " + throughputDescription;
		}
	}

	private record TaskSelection(String dimension, ConfiguredScan scan) {
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
