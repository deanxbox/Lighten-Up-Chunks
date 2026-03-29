package io.github.dean.lightenupchunks;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.network.chat.Component;

public final class TextComponents {
	private TextComponents() {
	}

	public static Component literal(String text) {
		for (Method method : Component.class.getDeclaredMethods()) {
			if (!Modifier.isStatic(method.getModifiers()) || !Component.class.isAssignableFrom(method.getReturnType())) {
				continue;
			}
			if (method.getParameterCount() != 1) {
				continue;
			}
			Class<?> parameterType = method.getParameterTypes()[0];
			if (!parameterType.isAssignableFrom(String.class) && parameterType != String.class && parameterType != CharSequence.class) {
				continue;
			}
			try {
				method.setAccessible(true);
				Object value = method.invoke(null, text);
				if (value instanceof Component component) {
					return component;
				}
			} catch (IllegalAccessException | InvocationTargetException ignored) {
			}
		}
		throw new IllegalStateException("Could not construct a chat component from plain text");
	}
}
