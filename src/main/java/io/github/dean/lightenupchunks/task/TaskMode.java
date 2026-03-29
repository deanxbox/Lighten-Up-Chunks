package io.github.dean.lightenupchunks.task;

import java.util.Locale;

public enum TaskMode {
	WORLD,
	RADIUS,
	BOX;

	public String serializedName() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static TaskMode fromSerializedName(String value) {
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "world" -> WORLD;
			case "radius" -> RADIUS;
			case "box", "region" -> BOX;
			default -> throw new IllegalArgumentException("Unknown task mode: " + value);
		};
	}
}
