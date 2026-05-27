package io.github.dean.lightenupchunks.task;

import java.util.Locale;

public enum RelightMode {
	MISSING_ONLY,
	FULL,
	FULL_2_PASS;

	public String serializedName() {
		return switch (this) {
			case MISSING_ONLY -> "missing-only";
			case FULL -> "full";
			case FULL_2_PASS -> "full-2-pass";
		};
	}

	public boolean inspectMissingLightOnly() {
		return this == MISSING_ONLY;
	}

	public boolean requiresTwoPasses() {
		return this == FULL_2_PASS;
	}

	public static RelightMode fromSerializedName(String value) {
		if (value == null) {
			throw new IllegalArgumentException("Relight mode cannot be null");
		}

		return switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "missing-only", "missing_only", "missing" -> MISSING_ONLY;
			case "full" -> FULL;
			case "full-2-pass", "full_2_pass", "full2pass", "two-pass", "two_pass" -> FULL_2_PASS;
			default -> throw new IllegalArgumentException("Unknown relight mode: " + value);
		};
	}
}
