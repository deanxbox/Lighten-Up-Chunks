package io.github.dean.lightenupchunks.client;

import io.github.dean.lightenupchunks.LucConfig;
import io.github.dean.lightenupchunks.LucConfigManager;
import io.github.dean.lightenupchunks.TextComponents;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class LucConfigScreen extends Screen {
	private static final int BUTTON_WIDTH = 280;
	private static final int BUTTON_HEIGHT = 20;
	private static final int ROW_SPACING = 24;

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
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		renderTransparentBackground(guiGraphics);
		guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
		guiGraphics.drawCenteredString(this.font, TextComponents.literal(LucConfigManager.path().toString()), this.width / 2, 34, 0xA0A0A0);
		super.render(guiGraphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(parent);
		}
	}

	protected void rebuildWidgets() {
		clearWidgets();

		LucConfig config = LucConfigManager.get();
		List<ToggleEntry> entries = new ArrayList<>();
		entries.add(toggle("Keep Running While Paused", () -> config.keepRunningWhilePaused, value -> update(c -> c.keepRunningWhilePaused = value)));
		entries.add(toggle("Calculate Only Empty Light Chunks", () -> config.calculateOnlyEmptyLightChunks, value -> update(c -> c.calculateOnlyEmptyLightChunks = value)));
		entries.add(toggle("Boss Bar Percentage", () -> config.bossBarShowPercentage, value -> update(c -> c.bossBarShowPercentage = value)));
		entries.add(toggle("Boss Bar Counts", () -> config.bossBarShowCounts, value -> update(c -> c.bossBarShowCounts = value)));
		entries.add(toggle("Boss Bar Remaining", () -> config.bossBarShowRemaining, value -> update(c -> c.bossBarShowRemaining = value)));
		entries.add(toggle("Boss Bar Current Chunk", () -> config.bossBarShowCurrentChunk, value -> update(c -> c.bossBarShowCurrentChunk = value)));
		entries.add(toggle("Boss Bar Rate", () -> config.bossBarShowRate, value -> update(c -> c.bossBarShowRate = value)));
		entries.add(toggle("Boss Bar ETA", () -> config.bossBarShowEta, value -> update(c -> c.bossBarShowEta = value)));

		int left = (this.width - BUTTON_WIDTH) / 2;
		int top = 52;
		for (int index = 0; index < entries.size(); index++) {
			ToggleEntry entry = entries.get(index);
			int y = top + index * ROW_SPACING;
			addRenderableWidget(Button.builder(entry.message(), button -> {
				entry.toggle();
				rebuildWidgets();
			}).bounds(left, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
		}

		int footerY = Math.min(this.height - 28, top + entries.size() * ROW_SPACING + 8);
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

	private static ToggleEntry toggle(String label, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		return new ToggleEntry(label, getter, setter);
	}

	private void update(Consumer<LucConfig> updater) {
		try {
			LucConfigManager.update(updater);
		} catch (IOException ignored) {
		}
	}

	private record ToggleEntry(String label, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		Component message() {
			return TextComponents.literal(label + ": " + (getter.get() ? "ON" : "OFF"));
		}

		void toggle() {
			setter.accept(!getter.get());
		}
	}
}
