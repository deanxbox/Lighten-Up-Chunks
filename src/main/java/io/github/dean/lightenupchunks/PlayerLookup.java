package io.github.dean.lightenupchunks;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.server.level.ServerPlayer;

public final class PlayerLookup {
	private PlayerLookup() {
	}

	public static ServerPlayer resolve(Object target) {
		if (target == null) {
			return null;
		}
		if (target instanceof ServerPlayer player) {
			return player;
		}

		Class<?> type = target.getClass();
		while (type != null) {
			for (Method method : type.getDeclaredMethods()) {
				if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) {
					continue;
				}
				if (!ServerPlayer.class.isAssignableFrom(method.getReturnType())) {
					continue;
				}
				try {
					method.setAccessible(true);
					Object value = method.invoke(target);
					if (value instanceof ServerPlayer player) {
						return player;
					}
				} catch (IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
				}
			}
			type = type.getSuperclass();
		}

		type = target.getClass();
		while (type != null) {
			for (Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || !ServerPlayer.class.isAssignableFrom(field.getType())) {
					continue;
				}
				try {
					field.setAccessible(true);
					Object value = field.get(target);
					if (value instanceof ServerPlayer player) {
						return player;
					}
				} catch (IllegalAccessException | RuntimeException ignored) {
				}
			}
			type = type.getSuperclass();
		}

		return null;
	}
}
