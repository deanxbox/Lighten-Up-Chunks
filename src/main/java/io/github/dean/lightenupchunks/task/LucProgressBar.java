package io.github.dean.lightenupchunks.task;

import io.github.dean.lightenupchunks.LucConfig;
import io.github.dean.lightenupchunks.LucConfigManager;
import io.github.dean.lightenupchunks.TextComponents;
import java.text.DecimalFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;

public final class LucProgressBar {
	private static final DecimalFormat RATE_FORMAT = new DecimalFormat("#,##0");
	private final ServerBossEvent bossBar;

	public LucProgressBar() {
		this.bossBar = new ServerBossEvent(
			UUID.randomUUID(),
			TextComponents.literal("Lighten Up, Chunks!"),
			enumValue(BossEvent.BossBarColor.class, "GREEN"),
			enumValue(BossEvent.BossBarOverlay.class, "PROGRESS")
		);
		bossBar.setProgress(0.0F);
	}

	public void start(MinecraftServer server) {
		bossBar.setVisible(true);
		bossBar.removeAllPlayers();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			bossBar.addPlayer(player);
		}
	}

	public void update(LightTask task, long nowMillis, int inFlightCount, ChunkPos currentChunk) {
		float progress = task.getTotalChunks() <= 0L
			? 0.0F
			: (float) Math.max(0.0D, Math.min(1.0D, task.getProcessedChunks() / (double) task.getTotalChunks()));
		double rate = task.getRecentChunksPerSecond(nowMillis);
		long remaining = task.getRemainingChunks() + inFlightCount;
		String eta = rate > 0.0D ? formatDuration(Duration.ofSeconds((long) Math.ceil(remaining / rate))) : "calculating";
		LucConfig config = LucConfigManager.get();
		List<String> parts = new ArrayList<>();
		if (config.bossBarShowPercentage) {
			parts.add(String.format(Locale.ROOT, "%.1f%%", progress * 100.0F));
		}
		if (config.bossBarShowCounts) {
			parts.add(task.getProcessedChunks() + "/" + task.getTotalChunks());
			parts.add("relit " + task.getRelitChunks());
			if (task.getSkippedChunks() > 0L) {
				parts.add("skip " + task.getSkippedChunks());
			}
			if (task.getFailedChunks() > 0L) {
				parts.add("fail " + task.getFailedChunks());
			}
		}
		if (config.bossBarShowRemaining) {
			parts.add(remaining + " left");
		}
		if (config.bossBarShowCurrentChunk && currentChunk != null) {
			parts.add("chunk " + currentChunk.x() + ", " + currentChunk.z());
		}
		if (config.bossBarShowRate) {
			parts.add(RATE_FORMAT.format(rate) + " c/s");
		}
		if (config.bossBarShowEta) {
			parts.add("ETA " + eta);
		}
		bossBar.setProgress(progress);
		String suffix = parts.isEmpty() ? "" : " -- " + String.join(" | ", parts);
		bossBar.setName(TextComponents.literal("Lighten Up, Chunks!" + suffix));
	}

	public void stop() {
		bossBar.removeAllPlayers();
		bossBar.setProgress(0.0F);
		bossBar.setName(TextComponents.literal("Lighten Up, Chunks!"));
		bossBar.setVisible(false);
	}

	public void addPlayer(ServerPlayer player) {
		bossBar.addPlayer(player);
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

	private static <E extends Enum<E>> E enumValue(Class<E> enumClass, String name) {
		try {
			return Enum.valueOf(enumClass, name);
		} catch (IllegalArgumentException exception) {
			E[] constants = enumClass.getEnumConstants();
			if (constants != null && constants.length > 0) {
				return constants[0];
			}
			throw exception;
		}
	}
}
