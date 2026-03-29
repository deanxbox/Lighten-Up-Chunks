package io.github.dean.lightenupchunks.task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import net.minecraft.world.level.ChunkPos;

public final class LightTask {
	private final String dimensionId;
	private final TaskMode mode;
	private final boolean inspectMissingLightOnly;
	private final Deque<ChunkPos> pendingChunks;
	private final long totalChunks;
	private long processedChunks;
	private final long startedAtMillis;
	private long lastProgressAtMillis;
	private double recentChunksPerSecond;

	public LightTask(
		String dimensionId,
		TaskMode mode,
		boolean inspectMissingLightOnly,
		Deque<ChunkPos> pendingChunks,
		long totalChunks,
		long processedChunks,
		long startedAtMillis
	) {
		this.dimensionId = dimensionId;
		this.mode = mode;
		this.inspectMissingLightOnly = inspectMissingLightOnly;
		this.pendingChunks = pendingChunks;
		this.totalChunks = totalChunks;
		this.processedChunks = processedChunks;
		this.startedAtMillis = startedAtMillis;
		this.lastProgressAtMillis = startedAtMillis;
	}

	public String getDimensionId() {
		return dimensionId;
	}

	public TaskMode getMode() {
		return mode;
	}

	public boolean inspectMissingLightOnly() {
		return inspectMissingLightOnly;
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
		return processedChunks;
	}

	public long getTotalChunks() {
		return totalChunks;
	}

	public long getRemainingChunks() {
		return pendingChunks.size();
	}

	public void recordProcessed(int count, long nowMillis) {
		if (count <= 0) {
			return;
		}

		double elapsedSeconds = Math.max((nowMillis - lastProgressAtMillis) / 1000.0D, 0.05D);
		double instantRate = count / elapsedSeconds;
		recentChunksPerSecond = recentChunksPerSecond == 0.0D
			? instantRate
			: recentChunksPerSecond * 0.7D + instantRate * 0.3D;
		processedChunks += count;
		lastProgressAtMillis = nowMillis;
	}

	public double getAverageChunksPerSecond(long nowMillis) {
		double elapsedSeconds = Math.max((nowMillis - startedAtMillis) / 1000.0D, 0.001D);
		return processedChunks / elapsedSeconds;
	}

	public double getRecentChunksPerSecond(long nowMillis) {
		return recentChunksPerSecond > 0.0D ? recentChunksPerSecond : getAverageChunksPerSecond(nowMillis);
	}

	public List<int[]> snapshotRemainingChunks() {
		List<int[]> remaining = new ArrayList<>(pendingChunks.size());
		for (ChunkPos chunkPos : pendingChunks) {
			remaining.add(new int[] {chunkPos.x, chunkPos.z});
		}
		return remaining;
	}
}
