package io.github.dean.lightenupchunks;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.server.level.ServerLevel;

public final class LucDimensions {
	public static final String OVERWORLD = "minecraft:overworld";
	public static final String NETHER = "minecraft:the_nether";
	public static final String END = "minecraft:the_end";

	private LucDimensions() {
	}

	public static String normalize(String value) {
		if (value == null) {
			throw new IllegalArgumentException("Dimension id cannot be null");
		}

		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException("Dimension id cannot be empty");
		}
		if (trimmed.equals("overworld")) {
			return OVERWORLD;
		}
		if (trimmed.equals("nether") || trimmed.equals("the_nether")) {
			return NETHER;
		}
		if (trimmed.equals("end") || trimmed.equals("the_end")) {
			return END;
		}

		int separatorIndex = trimmed.indexOf(':');
		if (separatorIndex < 0) {
			return "minecraft:" + trimmed;
		}
		if (separatorIndex == 0 || separatorIndex == trimmed.length() - 1 || trimmed.indexOf(':', separatorIndex + 1) >= 0) {
			throw new IllegalArgumentException("Invalid dimension id: " + value);
		}

		return trimmed;
	}

	public static String asString(ServerLevel level) {
		return level.dimension().identifier().toString();
	}

	public static String asString(Object dimensionKey) {
		String raw = String.valueOf(dimensionKey);
		int separatorIndex = raw.indexOf(" / ");
		if (separatorIndex >= 0 && raw.endsWith("]")) {
			return raw.substring(separatorIndex + 3, raw.length() - 1);
		}
		if (raw.startsWith("ResourceKey[") && raw.endsWith("]")) {
			return raw.substring("ResourceKey[".length(), raw.length() - 1);
		}
		return raw;
	}

	public static String namespace(String dimensionId) {
		String normalized = normalize(dimensionId);
		int separatorIndex = normalized.indexOf(':');
		return normalized.substring(0, separatorIndex);
	}

	public static String path(String dimensionId) {
		String normalized = normalize(dimensionId);
		int separatorIndex = normalized.indexOf(':');
		return normalized.substring(separatorIndex + 1);
	}

	private static String findDimensionKeyString(ServerLevel level) {
		return findDimensionKeyStringFromFields(level);
	}

	private static String findDimensionKeyStringFromFields(Object target) {
		Class<?> type = target.getClass();
		while (type != null) {
			for (Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				try {
					field.setAccessible(true);
					String parsed = parseDimensionKey(field.get(target));
					if (parsed != null) {
						return parsed;
					}
				} catch (IllegalAccessException | RuntimeException ignored) {
				}
			}
			type = type.getSuperclass();
		}
		return null;
	}

	private static String parseDimensionKey(Object candidate) {
		if (candidate == null) {
			return null;
		}

		String raw = String.valueOf(candidate);
		int separatorIndex = raw.indexOf(" / ");
		if (separatorIndex >= 0 && raw.endsWith("]")) {
			String parsed = raw.substring(separatorIndex + 3, raw.length() - 1);
			return parsed.contains(":") ? parsed : null;
		}
		if (raw.startsWith("ResourceKey[") && raw.endsWith("]")) {
			String parsed = raw.substring("ResourceKey[".length(), raw.length() - 1);
			return parsed.contains(":") ? parsed : null;
		}
		return raw.contains(":") && !raw.contains(" ") ? raw : null;
	}
}
