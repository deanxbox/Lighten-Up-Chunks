package io.github.dean.lightenupchunks;

import io.github.dean.lightenupchunks.task.RelightMode;

public final class LucConfig {
	public boolean keepRunningWhilePaused = true;
	public boolean calculateOnlyEmptyLightChunks = true;
	public String defaultRelightMode = RelightMode.MISSING_ONLY.serializedName();
	public int maxInFlightChunks = 0;
	public int maxRelightsPerTick = 0;
	public int saveFlushIntervalSeconds = 30;
	public boolean enableVoxyCompat = true;
	public boolean bossBarShowPercentage = true;
	public boolean bossBarShowCounts = true;
	public boolean bossBarShowRemaining = true;
	public boolean bossBarShowCurrentChunk = true;
	public boolean bossBarShowRate = true;
	public boolean bossBarShowEta = true;
}
