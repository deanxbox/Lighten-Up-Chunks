package io.github.dean.lightenupchunks.client;

import io.github.dean.lightenupchunks.LucConfig;
import io.github.dean.lightenupchunks.LucConfigManager;
import io.github.dean.lightenupchunks.TextComponents;
import io.github.dean.lightenupchunks.task.RelightMode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class LucConfigScreen extends Screen {
	private static final int BUTTON_WIDTH = 300;
	private static final int BUTTON_HEIGHT = 20;
	private static final int ROW_SPACING = 22;
	private static final int[] IN_FLIGHT_OPTIONS = new int[] {0, 16, 32, 48, 80, 128, 256};
	private static final int[] RELIGHTS_PER_TICK_OPTIONS = new int[] {0, 4, 8, 16, 32, 48, 72};
	private static final int[] SAVE_FLUSH_OPTIONS = new int[] {10, 30, 60, 120};

	private final Screen parent;

	public LucConfigScreen(Screen parent) {
		super(TextComponents.literal("Lighten Up, Chunks! Config"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		super.init();
		rebuildWidgets();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		this.extractPanorama(graphics, partialTick);
		this.extractBlurredBackground(graphics);
		this.extractMenuBackground(graphics);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(parent);
		}
	}

	protected void rebuildWidgets() {
		clearWidgets();

		addCenteredLabel(this.title, 20);
		addCenteredLabel(TextComponents.literal(LucConfigManager.path().toString()), 34);

		LucConfig config = LucConfigManager.get();
		List<SettingEntry> entries = new ArrayList<>();
		entries.add(cycle("Default Relight Mode", () -> humanRelightMode(config.defaultRelightMode), () ->
			update(c -> c.defaultRelightMode = nextRelightMode(c.defaultRelightMode))));
		entries.add(cycle("Max In-Flight Chunks", () -> formatOverride(config.maxInFlightChunks), () ->
			update(c -> c.maxInFlightChunks = nextIntOption(c.maxInFlightChunks, IN_FLIGHT_OPTIONS))));
		entries.add(cycle("Max Relights Per Tick", () -> formatOverride(config.maxRelightsPerTick), () ->
			update(c -> c.maxRelightsPerTick = nextIntOption(c.maxRelightsPerTick, RELIGHTS_PER_TICK_OPTIONS))));
		entries.add(cycle("Save Flush Interval", () -> config.saveFlushIntervalSeconds + "s", () ->
			update(c -> c.saveFlushIntervalSeconds = nextIntOption(c.saveFlushIntervalSeconds, SAVE_FLUSH_OPTIONS))));
		entries.add(toggle("Keep Running While Paused", () -> config.keepRunningWhilePaused, value -> update(c -> c.keepRunningWhilePaused = value)));
		entries.add(toggle("Enable Voxy Integration", () -> config.enableVoxyCompat, value -> update(c -> c.enableVoxyCompat = value)));
		entries.add(toggle("Boss Bar Percentage", () -> config.bossBarShowPercentage, value -> update(c -> c.bossBarShowPercentage = value)));
		entries.add(toggle("Boss Bar Counts", () -> config.bossBarShowCounts, value -> update(c -> c.bossBarShowCounts = value)));
		entries.add(toggle("Boss Bar Remaining", () -> config.bossBarShowRemaining, value -> update(c -> c.bossBarShowRemaining = value)));
		entries.add(toggle("Boss Bar Current Chunk", () -> config.bossBarShowCurrentChunk, value -> update(c -> c.bossBarShowCurrentChunk = value)));
		entries.add(toggle("Boss Bar Rate", () -> config.bossBarShowRate, value -> update(c -> c.bossBarShowRate = value)));
		entries.add(toggle("Boss Bar ETA", () -> config.bossBarShowEta, value -> update(c -> c.bossBarShowEta = value)));

		int left = (this.width - BUTTON_WIDTH) / 2;
		int top = 48;
		for (int index = 0; index < entries.size(); index++) {
			SettingEntry entry = entries.get(index);
			int y = top + index * ROW_SPACING;
			addRenderableWidget(Button.builder(entry.message(), button -> {
				entry.activate();
				rebuildWidgets();
			}).bounds(left, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
		}

		int footerY = Math.min(this.height - 28, top + entries.size() * ROW_SPACING + 6);
		int smallWidth = 90;
		int gap = 8;
		int totalWidth = smallWidth * 3 + gap * 2;
		int footerLeft = (this.width - totalWidth) / 2;
		addRenderableWidget(Button.builder(TextComponents.literal("Reload"), button -> {
			LucConfigManager.reload();
			rebuildWidgets();
		}).bounds(footerLeft, footerY, smallWidth, BUTTON_HEIGHT).build());
		addRenderableWidget(Button.builder(TextComponents.literal("Reset"), button -> {
			try {
				LucConfigManager.reset();
			} catch (IOException ignored) {
			}
			rebuildWidgets();
		}).bounds(footerLeft + smallWidth + gap, footerY, smallWidth, BUTTON_HEIGHT).build());
		addRenderableWidget(Button.builder(TextComponents.literal("Done"), button -> onClose())
			.bounds(footerLeft + (smallWidth + gap) * 2, footerY, smallWidth, BUTTON_HEIGHT)
			.build());
	}

	private static SettingEntry toggle(String label, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		return new SettingEntry(
			() -> TextComponents.literal(label + ": " + (getter.get() ? "ON" : "OFF")),
			() -> setter.accept(!getter.get())
		);
	}

	private static SettingEntry cycle(String label, Supplier<String> valueSupplier, Runnable action) {
		return new SettingEntry(() -> TextComponents.literal(label + ": " + valueSupplier.get()), action);
	}

	private void addCenteredLabel(Component message, int y) {
		int labelWidth = this.font.width(message);
		addRenderableWidget(new StringWidget((this.width - labelWidth) / 2, y, labelWidth, 9, message, this.font));
	}

	private void update(Consumer<LucConfig> updater) {
		try {
			LucConfigManager.update(updater);
		} catch (IOException ignored) {
		}
	}

	private static String humanRelightMode(String value) {
		return switch (RelightMode.fromSerializedName(value)) {
			case MISSING_ONLY -> "Missing Only";
			case FULL -> "Full";
			case FULL_2_PASS -> "Full 2 Pass";
		};
	}

	private static String nextRelightMode(String current) {
		RelightMode mode = RelightMode.fromSerializedName(current);
		return switch (mode) {
			case MISSING_ONLY -> RelightMode.FULL.serializedName();
			case FULL -> RelightMode.FULL_2_PASS.serializedName();
			case FULL_2_PASS -> RelightMode.MISSING_ONLY.serializedName();
		};
	}

	private static String formatOverride(int value) {
		return value <= 0 ? "Adaptive" : Integer.toString(value);
	}

	private static int nextIntOption(int current, int[] options) {
		for (int index = 0; index < options.length; index++) {
			if (options[index] == current) {
				return options[(index + 1) % options.length];
			}
		}
		return options[0];
	}

	private record SettingEntry(Supplier<Component> messageSupplier, Runnable action) {
		Component message() {
			return messageSupplier.get();
		}

		void activate() {
			action.run();
		}
	}
}
