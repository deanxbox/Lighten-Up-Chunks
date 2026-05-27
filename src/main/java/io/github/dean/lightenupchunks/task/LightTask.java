package io.github.dean.lightenupchunks.task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import net.minecraft.world.level.ChunkPos;

public final class LightTask {
	private final String dimensionId;
	private final TaskMode mode;
	private final RelightMode relightMode;
	private final Deque<ChunkPos> pendingChunks;
	private final long totalChunks;
	private long relitChunks;
	private long skippedChunks;
	private long failedChunks;
	private final long startedAtMillis;
	private long lastProgressAtMillis;
	private double recentChunksPerSecond;
	private ChunkPos lastFailedChunk;
	private String lastFailureMessage;

	public LightTask(
		String dimensionId,
		TaskMode mode,
		RelightMode relightMode,
		Deque<ChunkPos> pendingChunks,
		long totalChunks,
		long relitChunks,
		long skippedChunks,
		long failedChunks,
		long startedAtMillis,
		long lastProgressAtMillis,
		double recentChunksPerSecond,
		ChunkPos lastFailedChunk,
		String lastFailureMessage
	) {
		this.dimensionId = dimensionId;
		this.mode = mode;
		this.relightMode = relightMode;
		this.pendingChunks = pendingChunks;
		this.totalChunks = totalChunks;
		this.relitChunks = relitChunks;
		this.skippedChunks = skippedChunks;
		this.failedChunks = failedChunks;
		this.startedAtMillis = startedAtMillis;
		this.lastProgressAtMillis = lastProgressAtMillis;
		this.recentChunksPerSecond = recentChunksPerSecond;
		this.lastFailedChunk = lastFailedChunk;
		this.lastFailureMessage = lastFailureMessage;
	}

	public LightTask(
		String dimensionId,
		TaskMode mode,
		RelightMode relightMode,
		Deque<ChunkPos> pendingChunks,
		long totalChunks,
		long startedAtMillis
	) {
		this(
			dimensionId,
			mode,
			relightMode,
			pendingChunks,
			totalChunks,
			0L,
			0L,
			0L,
			startedAtMillis,
			startedAtMillis,
			0.0D,
			null,
			null
		);
	}

	public String getDimensionId() {
		return dimensionId;
	}

	public TaskMode getMode() {
		return mode;
	}

	public RelightMode getRelightMode() {
		return relightMode;
	}

	public boolean inspectMissingLightOnly() {
		return relightMode.inspectMissingLightOnly();
	}

	public ChunkPos pollNextChunk() {
		return pendingChunks.pollFirst();
	}

	public void returnChunks(Collection<ChunkPos> chunks) {
		if (chunks == null || chunks.isEmpty()) {
			return;
		}

		List<ChunkPos> returned = new ArrayList<>(chunks);
		for (int index = returned.size() - 1; index >= 0; index--) {
			pendingChunks.addFirst(returned.get(index));
		}
	}

	public boolean isComplete() {
		return pendingChunks.isEmpty();
	}

	public long getProcessedChunks() {
		return relitChunks + skippedChunks + failedChunks;
	}

	public long getRelitChunks() {
		return relitChunks;
	}

	public long getSkippedChunks() {
		return skippedChunks;
	}

	public long getFailedChunks() {
		return failedChunks;
	}

	public long getTotalChunks() {
		return totalChunks;
	}

	public long getRemainingChunks() {
		return pendingChunks.size();
	}

	public void recordRelit(int count, long nowMillis) {
		if (count <= 0) {
			return;
		}
		advanceProgress(count, nowMillis);
		relitChunks += count;
	}

	public void recordSkipped(int count, long nowMillis) {
		if (count <= 0) {
			return;
		}
		advanceProgress(count, nowMillis);
		skippedChunks += count;
	}

	public void recordFailure(ChunkPos chunkPos, String message, long nowMillis) {
		advanceProgress(1, nowMillis);
		failedChunks++;
		lastFailedChunk = chunkPos;
		lastFailureMessage = message;
	}

	public double getAverageChunksPerSecond(long nowMillis) {
		double elapsedSeconds = Math.max((nowMillis - startedAtMillis) / 1000.0D, 0.001D);
		return getProcessedChunks() / elapsedSeconds;
	}

	public double getRecentChunksPerSecond(long nowMillis) {
		return recentChunksPerSecond > 0.0D ? recentChunksPerSecond : getAverageChunksPerSecond(nowMillis);
	}

	public long getElapsedSeconds(long nowMillis) {
		return Math.max((nowMillis - startedAtMillis) / 1000L, 1L);
	}

	public long getStartedAtMillis() {
		return startedAtMillis;
	}

	public long getLastProgressAtMillis() {
		return lastProgressAtMillis;
	}

	public ChunkPos getLastFailedChunk() {
		return lastFailedChunk;
	}

	public String getLastFailureMessage() {
		return lastFailureMessage;
	}

	public List<int[]> snapshotRemainingChunks() {
		List<int[]> remaining = new ArrayList<>(pendingChunks.size());
		for (ChunkPos chunkPos : pendingChunks) {
			remaining.add(new int[] {chunkPos.x(), chunkPos.z()});
		}
		return remaining;
	}

	private void advanceProgress(int count, long nowMillis) {
		if (count <= 0) {
			return;
		}
		double elapsedSeconds = Math.max((nowMillis - lastProgressAtMillis) / 1000.0D, 0.05D);
		double instantRate = count / elapsedSeconds;
		recentChunksPerSecond = recentChunksPerSecond == 0.0D
			? instantRate
			: recentChunksPerSecond * 0.7D + instantRate * 0.3D;
		lastProgressAtMillis = nowMillis;
	}
}
