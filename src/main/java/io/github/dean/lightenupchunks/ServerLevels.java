package io.github.dean.lightenupchunks;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class ServerLevels {
	private ServerLevels() {
	}

	public static List<ServerLevel> all(MinecraftServer server) {
		List<ServerLevel> levels = new ArrayList<>();
		Class<?> type = server.getClass();
		while (type != null && type != Object.class) {
			for (Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				if (!Map.class.isAssignableFrom(field.getType())) {
					continue;
				}
				try {
					field.setAccessible(true);
					Object value = field.get(server);
					if (value instanceof Map<?, ?> map) {
						for (Object entry : map.values()) {
							if (entry instanceof ServerLevel level) {
								levels.add(level);
							}
						}
						if (!levels.isEmpty()) {
							return levels;
						}
					}
				} catch (IllegalAccessException ignored) {
				}
			}
			type = type.getSuperclass();
		}
		return levels;
	}
}
