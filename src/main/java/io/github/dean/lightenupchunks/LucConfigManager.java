package io.github.dean.lightenupchunks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;

public final class LucConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("lighten-up-chunks.json");
	private static volatile LucConfig config;

	private LucConfigManager() {
	}

	public static LucConfig get() {
		LucConfig loaded = config;
		if (loaded != null) {
			return loaded;
		}

		synchronized (LucConfigManager.class) {
			if (config == null) {
				config = load();
			}
			return config;
		}
	}

	public static Path path() {
		return CONFIG_PATH;
	}

	public static LucConfig reload() {
		synchronized (LucConfigManager.class) {
			config = load();
			return config;
		}
	}

	public static LucConfig defaults() {
		return new LucConfig();
	}

	public static LucConfig update(Consumer<LucConfig> updater) throws IOException {
		synchronized (LucConfigManager.class) {
			LucConfig loaded = get();
			updater.accept(loaded);
			write(loaded);
			return loaded;
		}
	}

	public static LucConfig reset() throws IOException {
		synchronized (LucConfigManager.class) {
			LucConfig defaults = new LucConfig();
			write(defaults);
			config = defaults;
			return defaults;
		}
	}

	private static LucConfig load() {
		LucConfig defaults = new LucConfig();
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			if (!Files.exists(CONFIG_PATH)) {
				write(defaults);
				return defaults;
			}

			try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
				LucConfig loaded = GSON.fromJson(reader, LucConfig.class);
				if (loaded == null) {
					write(defaults);
					return defaults;
				}
				return loaded;
			}
		} catch (IOException exception) {
			LightenUpChunks.LOGGER.warn("Failed to read {}", CONFIG_PATH, exception);
			return defaults;
		}
	}

	private static void write(LucConfig config) throws IOException {
		try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
			GSON.toJson(config, writer);
		}
	}
}
