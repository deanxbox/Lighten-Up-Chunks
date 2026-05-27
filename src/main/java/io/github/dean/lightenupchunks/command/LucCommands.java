package io.github.dean.lightenupchunks.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import io.github.dean.lightenupchunks.CommandFeedback;
import io.github.dean.lightenupchunks.LucConfig;
import io.github.dean.lightenupchunks.LucConfigManager;
import io.github.dean.lightenupchunks.LucDimensions;
import io.github.dean.lightenupchunks.ServerLevels;
import io.github.dean.lightenupchunks.TextComponents;
import io.github.dean.lightenupchunks.task.LucTaskManager;
import io.github.dean.lightenupchunks.task.RelightMode;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

public final class LucCommands {
	private static final String[] CONFIG_KEYS = new String[] {
		"keepRunningWhilePaused",
		"defaultRelightMode",
		"maxInFlightChunks",
		"maxRelightsPerTick",
		"saveFlushIntervalSeconds",
		"enableVoxyCompat",
		"bossBarShowPercentage",
		"bossBarShowCounts",
		"bossBarShowRemaining",
		"bossBarShowCurrentChunk",
		"bossBarShowRate",
		"bossBarShowEta"
	};
	private static final String[] RELIGHT_MODE_SUGGESTIONS = new String[] {
		RelightMode.MISSING_ONLY.serializedName(),
		RelightMode.FULL.serializedName(),
		RelightMode.FULL_2_PASS.serializedName()
	};
	private static final String[] BOOLEAN_SUGGESTIONS = new String[] {"true", "false"};
	private static final DynamicCommandExceptionType INVALID_DIMENSION = new DynamicCommandExceptionType(value ->
		TextComponents.literal("Invalid dimension id: " + value)
	);
	private static final DynamicCommandExceptionType INVALID_CONFIG_KEY = new DynamicCommandExceptionType(value ->
		TextComponents.literal("Unknown config key: " + value)
	);
	private static final DynamicCommandExceptionType INVALID_RELIGHT_MODE = new DynamicCommandExceptionType(value ->
		TextComponents.literal("Unknown relight mode: " + value)
	);

	private LucCommands() {
	}

	public static void register(
		CommandDispatcher<CommandSourceStack> dispatcher,
		CommandBuildContext buildContext,
		Commands.CommandSelection environment
	) {
		var root = literal("luc")
			.requires(LucCommands::hasRequiredPermission)
			.executes(context -> taskManager(context.getSource()).overview(context.getSource()))
			.then(literal("status")
				.executes(context -> taskManager(context.getSource()).status(context.getSource())))
			.then(literal("dimensions")
				.executes(context -> taskManager(context.getSource()).dimensions(context.getSource())))
			.then(literal("reset")
				.executes(context -> taskManager(context.getSource()).resetSelection(context.getSource())))
			.then(literal("selection")
				.executes(context -> taskManager(context.getSource()).selection(context.getSource())))
			.then(buildRelightModeCommand())
			.then(buildConfigCommand())
			.then(literal("world")
				.executes(context -> taskManager(context.getSource()).configureWorld(context.getSource(), currentDimension(context.getSource())))
				.then(dimensionArgument("dimension")
					.executes(context -> taskManager(context.getSource()).configureWorld(
						context.getSource(),
						parseDimension(context, "dimension")
					))))
			.then(literal("radius")
				.then(argument("radius", IntegerArgumentType.integer(0))
					.executes(context -> {
						BlockCoordinate center = defaultCenter(context.getSource());
						return taskManager(context.getSource()).configureRadius(
							context.getSource(),
							IntegerArgumentType.getInteger(context, "radius"),
							center.x,
							center.z
						);
					})
					.then(argument("centerX", IntegerArgumentType.integer())
						.then(argument("centerZ", IntegerArgumentType.integer())
							.executes(context -> taskManager(context.getSource()).configureRadius(
								context.getSource(),
								IntegerArgumentType.getInteger(context, "radius"),
								IntegerArgumentType.getInteger(context, "centerX"),
								IntegerArgumentType.getInteger(context, "centerZ")
							))))))
			.then(literal("region")
				.then(cornersArguments(context -> taskManager(context.getSource()).configureRegion(
					context.getSource(),
					IntegerArgumentType.getInteger(context, "x1"),
					IntegerArgumentType.getInteger(context, "z1"),
					IntegerArgumentType.getInteger(context, "x2"),
					IntegerArgumentType.getInteger(context, "z2")
				))))
			.then(buildPreviewCommand("preview"))
			.then(buildPreviewCommand("estimate"))
			.then(buildStartCommand())
			.then(literal("pause")
				.executes(context -> taskManager(context.getSource()).pause(context.getSource())))
			.then(literal("resume")
				.executes(context -> taskManager(context.getSource()).resume(context.getSource())))
			.then(literal("cancel")
				.executes(context -> taskManager(context.getSource()).cancel(context.getSource())));
		dispatcher.register(root);
	}

	private static LiteralArgumentBuilder<CommandSourceStack> buildRelightModeCommand() {
		return literal("mode")
			.executes(context -> taskManager(context.getSource()).relightMode(context.getSource()))
			.then(relightModeArgument("relightMode")
				.executes(context -> taskManager(context.getSource()).configureRelightMode(
					context.getSource(),
					parseRelightMode(context, "relightMode")
				)));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> buildConfigCommand() {
		return literal("config")
			.executes(context -> showConfig(context.getSource()))
			.then(literal("show")
				.executes(context -> showConfig(context.getSource())))
			.then(literal("reload")
				.executes(context -> reloadConfig(context.getSource())))
			.then(literal("reset")
				.executes(context -> resetConfig(context.getSource())))
			.then(literal("get")
				.then(configKeyArgument("key")
					.executes(context -> getConfigValue(
						context.getSource(),
						StringArgumentType.getString(context, "key")
					))))
			.then(literal("set")
				.then(configKeyArgument("key")
					.then(argument("value", StringArgumentType.word())
						.suggests(LucCommands::suggestConfigValues)
						.executes(context -> setConfigValue(
							context.getSource(),
							StringArgumentType.getString(context, "key"),
							StringArgumentType.getString(context, "value")
						)))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> buildStartCommand() {
		return literal("start")
			.executes(context -> taskManager(context.getSource()).startConfigured(context.getSource()))
			.then(buildCurrentWorldStartCommand())
			.then(buildCurrentRadiusStartCommand())
			.then(literal("region").then(startRegionArguments()))
			.then(buildExplicitDimensionStartCommand());
	}

	private static LiteralArgumentBuilder<CommandSourceStack> buildPreviewCommand(String name) {
		return literal(name)
			.executes(context -> taskManager(context.getSource()).previewConfigured(context.getSource()))
			.then(buildCurrentWorldPreviewCommand())
			.then(buildCurrentRadiusPreviewCommand())
			.then(literal("region").then(previewRegionArguments()))
			.then(buildExplicitDimensionPreviewCommand());
	}

	private static LiteralArgumentBuilder<CommandSourceStack> buildCurrentWorldStartCommand() {
		return literal("world")
			.executes(context -> taskManager(context.getSource()).startWorld(
				context.getSource(),
				currentDimension(context.getSource())
			))
			.then(dimensionArgument("dimension")
				.executes(context -> taskManager(context.getSource()).startWorld(
					context.getSource(),
					parseDimension(context, "dimension")
				)));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> buildCurrentWorldPreviewCommand() {
		return literal("world")
			.executes(context -> taskManager(context.getSource()).previewWorld(
				context.getSource(),
				currentDimension(context.getSource())
			))
			.then(dimensionArgument("dimension")
				.executes(context -> taskManager(context.getSource()).previewWorld(
					context.getSource(),
					parseDimension(context, "dimension")
				)));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> buildCurrentRadiusStartCommand() {
		return literal("radius")
				.then(argument("radius", IntegerArgumentType.integer(0))
					.executes(context -> {
						BlockCoordinate center = defaultCenter(context.getSource());
						return taskManager(context.getSource()).startRadius(
							context.getSource(),
							currentDimension(context.getSource()),
						IntegerArgumentType.getInteger(context, "radius"),
						center.x,
						center.z
					);
				})
				.then(argument("centerX", IntegerArgumentType.integer())
					.then(argument("centerZ", IntegerArgumentType.integer())
						.executes(context -> taskManager(context.getSource()).startRadius(
							context.getSource(),
							currentDimension(context.getSource()),
							IntegerArgumentType.getInteger(context, "radius"),
							IntegerArgumentType.getInteger(context, "centerX"),
							IntegerArgumentType.getInteger(context, "centerZ")
						))
						.then(dimensionArgument("dimension")
							.executes(context -> taskManager(context.getSource()).startRadius(
								context.getSource(),
								parseDimension(context, "dimension"),
								IntegerArgumentType.getInteger(context, "radius"),
								IntegerArgumentType.getInteger(context, "centerX"),
								IntegerArgumentType.getInteger(context, "centerZ")
							))))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> buildCurrentRadiusPreviewCommand() {
		return literal("radius")
			.then(argument("radius", IntegerArgumentType.integer(0))
				.executes(context -> {
					BlockCoordinate center = defaultCenter(context.getSource());
					return taskManager(context.getSource()).previewRadius(
						context.getSource(),
						currentDimension(context.getSource()),
						IntegerArgumentType.getInteger(context, "radius"),
						center.x,
						center.z
					);
				})
				.then(argument("centerX", IntegerArgumentType.integer())
					.then(argument("centerZ", IntegerArgumentType.integer())
						.executes(context -> taskManager(context.getSource()).previewRadius(
							context.getSource(),
							currentDimension(context.getSource()),
							IntegerArgumentType.getInteger(context, "radius"),
							IntegerArgumentType.getInteger(context, "centerX"),
							IntegerArgumentType.getInteger(context, "centerZ")
						))
						.then(dimensionArgument("dimension")
							.executes(context -> taskManager(context.getSource()).previewRadius(
								context.getSource(),
								parseDimension(context, "dimension"),
								IntegerArgumentType.getInteger(context, "radius"),
								IntegerArgumentType.getInteger(context, "centerX"),
								IntegerArgumentType.getInteger(context, "centerZ")
							))))));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> buildExplicitDimensionStartCommand() {
		RequiredArgumentBuilder<CommandSourceStack, String> builder = dimensionArgument("dimension")
			.executes(context -> taskManager(context.getSource()).startWorld(
				context.getSource(),
				parseDimension(context, "dimension")
			));
		builder.then(literal("world")
			.executes(context -> taskManager(context.getSource()).startWorld(
				context.getSource(),
				parseDimension(context, "dimension")
			)));
		builder.then(buildExplicitDimensionRadiusCommand());
		builder.then(buildExplicitDimensionRegionCommand("region"));
		return builder;
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> buildExplicitDimensionPreviewCommand() {
		RequiredArgumentBuilder<CommandSourceStack, String> builder = dimensionArgument("dimension")
			.executes(context -> taskManager(context.getSource()).previewWorld(
				context.getSource(),
				parseDimension(context, "dimension")
			));
		builder.then(literal("world")
			.executes(context -> taskManager(context.getSource()).previewWorld(
				context.getSource(),
				parseDimension(context, "dimension")
			)));
		builder.then(buildExplicitDimensionPreviewRadiusCommand());
		builder.then(buildExplicitDimensionPreviewRegionCommand("region"));
		return builder;
	}

	private static LiteralArgumentBuilder<CommandSourceStack> buildExplicitDimensionRadiusCommand() {
		return literal("radius")
			.then(argument("radius", IntegerArgumentType.integer(0))
				.executes(context -> {
					BlockCoordinate center = defaultCenter(context.getSource());
					return taskManager(context.getSource()).startRadius(
						context.getSource(),
						parseDimension(context, "dimension"),
						IntegerArgumentType.getInteger(context, "radius"),
						center.x,
						center.z
					);
				})
				.then(argument("centerX", IntegerArgumentType.integer())
					.then(argument("centerZ", IntegerArgumentType.integer())
						.executes(context -> taskManager(context.getSource()).startRadius(
							context.getSource(),
							parseDimension(context, "dimension"),
							IntegerArgumentType.getInteger(context, "radius"),
							IntegerArgumentType.getInteger(context, "centerX"),
							IntegerArgumentType.getInteger(context, "centerZ")
						)))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> buildExplicitDimensionPreviewRadiusCommand() {
		return literal("radius")
			.then(argument("radius", IntegerArgumentType.integer(0))
				.executes(context -> {
					BlockCoordinate center = defaultCenter(context.getSource());
					return taskManager(context.getSource()).previewRadius(
						context.getSource(),
						parseDimension(context, "dimension"),
						IntegerArgumentType.getInteger(context, "radius"),
						center.x,
						center.z
					);
				})
				.then(argument("centerX", IntegerArgumentType.integer())
					.then(argument("centerZ", IntegerArgumentType.integer())
						.executes(context -> taskManager(context.getSource()).previewRadius(
							context.getSource(),
							parseDimension(context, "dimension"),
							IntegerArgumentType.getInteger(context, "radius"),
							IntegerArgumentType.getInteger(context, "centerX"),
							IntegerArgumentType.getInteger(context, "centerZ")
						)))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> buildExplicitDimensionRegionCommand(String name) {
		return literal(name)
			.then(cornersArguments(context -> taskManager(context.getSource()).startRegion(
				context.getSource(),
				parseDimension(context, "dimension"),
				IntegerArgumentType.getInteger(context, "x1"),
				IntegerArgumentType.getInteger(context, "z1"),
				IntegerArgumentType.getInteger(context, "x2"),
				IntegerArgumentType.getInteger(context, "z2")
			)));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> buildExplicitDimensionPreviewRegionCommand(String name) {
		return literal(name)
			.then(cornersArguments(context -> taskManager(context.getSource()).previewRegion(
				context.getSource(),
				parseDimension(context, "dimension"),
				IntegerArgumentType.getInteger(context, "x1"),
				IntegerArgumentType.getInteger(context, "z1"),
				IntegerArgumentType.getInteger(context, "x2"),
				IntegerArgumentType.getInteger(context, "z2")
			)));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, Integer> startRegionArguments() {
		return cornersArguments(context -> taskManager(context.getSource()).startRegion(
			context.getSource(),
			currentDimension(context.getSource()),
			IntegerArgumentType.getInteger(context, "x1"),
			IntegerArgumentType.getInteger(context, "z1"),
			IntegerArgumentType.getInteger(context, "x2"),
			IntegerArgumentType.getInteger(context, "z2")
		));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, Integer> previewRegionArguments() {
		return cornersArguments(context -> taskManager(context.getSource()).previewRegion(
			context.getSource(),
			currentDimension(context.getSource()),
			IntegerArgumentType.getInteger(context, "x1"),
			IntegerArgumentType.getInteger(context, "z1"),
			IntegerArgumentType.getInteger(context, "x2"),
			IntegerArgumentType.getInteger(context, "z2")
		));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, Integer> cornersArguments(CommandExecutor executor) {
		return argument("x1", IntegerArgumentType.integer())
			.then(argument("z1", IntegerArgumentType.integer())
				.then(argument("x2", IntegerArgumentType.integer())
					.then(argument("z2", IntegerArgumentType.integer())
						.executes(executor::run))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
		return LiteralArgumentBuilder.literal(name);
	}

	private static <T> RequiredArgumentBuilder<CommandSourceStack, T> argument(String name, ArgumentType<T> type) {
		return RequiredArgumentBuilder.argument(name, type);
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> dimensionArgument(String name) {
		return argument(name, StringArgumentType.word())
			.suggests((context, builder) -> SharedSuggestionProvider.suggest(dimensionSuggestions(context.getSource()), builder));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> configKeyArgument(String name) {
		return argument(name, StringArgumentType.word())
			.suggests((context, builder) -> SharedSuggestionProvider.suggest(CONFIG_KEYS, builder));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> relightModeArgument(String name) {
		return argument(name, StringArgumentType.word())
			.suggests((context, builder) -> SharedSuggestionProvider.suggest(RELIGHT_MODE_SUGGESTIONS, builder));
	}

	private static String parseDimension(CommandContext<CommandSourceStack> context, String argumentName) throws CommandSyntaxException {
		String raw = StringArgumentType.getString(context, argumentName);
		try {
			return LucDimensions.normalize(raw);
		} catch (RuntimeException exception) {
			throw INVALID_DIMENSION.create(raw);
		}
	}

	private static RelightMode parseRelightMode(CommandContext<CommandSourceStack> context, String argumentName) throws CommandSyntaxException {
		String raw = StringArgumentType.getString(context, argumentName);
		try {
			return RelightMode.fromSerializedName(raw);
		} catch (IllegalArgumentException exception) {
			throw INVALID_RELIGHT_MODE.create(raw);
		}
	}

	private static String currentDimension(CommandSourceStack source) {
		return LucDimensions.asString(source.getLevel());
	}

	private static BlockCoordinate defaultCenter(CommandSourceStack source) {
		Vec3 position = source.getPosition();
		return new BlockCoordinate((int) Math.floor(position.x), (int) Math.floor(position.z));
	}

	private static int showConfig(CommandSourceStack source) {
		LucConfig config = LucConfigManager.get();
		CommandFeedback.sendSuccess(
			source,
			"LUC config | path=" + LucConfigManager.path() +
				" | keepRunningWhilePaused=" + config.keepRunningWhilePaused +
				" | defaultRelightMode=" + config.defaultRelightMode +
				" | maxInFlightChunks=" + config.maxInFlightChunks +
				" | maxRelightsPerTick=" + config.maxRelightsPerTick +
				" | saveFlushIntervalSeconds=" + config.saveFlushIntervalSeconds +
				" | enableVoxyCompat=" + config.enableVoxyCompat +
				" | bossBarShowPercentage=" + config.bossBarShowPercentage +
				" | bossBarShowCounts=" + config.bossBarShowCounts +
				" | bossBarShowRemaining=" + config.bossBarShowRemaining +
				" | bossBarShowCurrentChunk=" + config.bossBarShowCurrentChunk +
				" | bossBarShowRate=" + config.bossBarShowRate +
				" | bossBarShowEta=" + config.bossBarShowEta,
			false
		);
		return 1;
	}

	private static int reloadConfig(CommandSourceStack source) {
		LucConfigManager.reload();
		CommandFeedback.sendSuccess(source, "Reloaded config from " + LucConfigManager.path() + ".", false);
		return 1;
	}

	private static int resetConfig(CommandSourceStack source) throws CommandSyntaxException {
		try {
			LucConfigManager.reset();
		} catch (IOException exception) {
			throw configError("Failed to reset config: " + exception.getMessage());
		}
		CommandFeedback.sendSuccess(source, "Reset config to defaults at " + LucConfigManager.path() + ".", true);
		return 1;
	}

	private static int setConfigValue(CommandSourceStack source, String key, String value) throws CommandSyntaxException {
		if (!isKnownConfigKey(key)) {
			throw INVALID_CONFIG_KEY.create(key);
		}
		LucConfig updated;
		try {
			updated = LucConfigManager.update(config -> applyConfigValue(config, key, value));
		} catch (IOException exception) {
			throw configError("Failed to save config: " + exception.getMessage());
		} catch (IllegalArgumentException exception) {
			throw configError("Invalid value for " + key + ": " + exception.getMessage());
		}
		CommandFeedback.sendSuccess(source, "Set " + key + "=" + configValue(updated, key) + " in " + LucConfigManager.path() + ".", true);
		return 1;
	}

	private static int getConfigValue(CommandSourceStack source, String key) throws CommandSyntaxException {
		if (!isKnownConfigKey(key)) {
			throw INVALID_CONFIG_KEY.create(key);
		}
		CommandFeedback.sendSuccess(source, "LUC config | " + key + "=" + configValue(LucConfigManager.get(), key), false);
		return 1;
	}

	private static void applyConfigValue(LucConfig config, String key, String rawValue) {
		switch (key) {
			case "keepRunningWhilePaused" -> config.keepRunningWhilePaused = parseBoolean(rawValue);
			case "defaultRelightMode" -> config.defaultRelightMode = RelightMode.fromSerializedName(rawValue).serializedName();
			case "maxInFlightChunks" -> config.maxInFlightChunks = parseNonNegativeInt(rawValue, key);
			case "maxRelightsPerTick" -> config.maxRelightsPerTick = parseNonNegativeInt(rawValue, key);
			case "saveFlushIntervalSeconds" -> config.saveFlushIntervalSeconds = parsePositiveInt(rawValue, key);
			case "enableVoxyCompat" -> config.enableVoxyCompat = parseBoolean(rawValue);
			case "bossBarShowPercentage" -> config.bossBarShowPercentage = parseBoolean(rawValue);
			case "bossBarShowCounts" -> config.bossBarShowCounts = parseBoolean(rawValue);
			case "bossBarShowRemaining" -> config.bossBarShowRemaining = parseBoolean(rawValue);
			case "bossBarShowCurrentChunk" -> config.bossBarShowCurrentChunk = parseBoolean(rawValue);
			case "bossBarShowRate" -> config.bossBarShowRate = parseBoolean(rawValue);
			case "bossBarShowEta" -> config.bossBarShowEta = parseBoolean(rawValue);
			default -> throw new IllegalArgumentException(key);
		}
	}

	private static String configValue(LucConfig config, String key) {
		return switch (key) {
			case "keepRunningWhilePaused" -> Boolean.toString(config.keepRunningWhilePaused);
			case "defaultRelightMode" -> config.defaultRelightMode;
			case "maxInFlightChunks" -> Integer.toString(config.maxInFlightChunks);
			case "maxRelightsPerTick" -> Integer.toString(config.maxRelightsPerTick);
			case "saveFlushIntervalSeconds" -> Integer.toString(config.saveFlushIntervalSeconds);
			case "enableVoxyCompat" -> Boolean.toString(config.enableVoxyCompat);
			case "bossBarShowPercentage" -> Boolean.toString(config.bossBarShowPercentage);
			case "bossBarShowCounts" -> Boolean.toString(config.bossBarShowCounts);
			case "bossBarShowRemaining" -> Boolean.toString(config.bossBarShowRemaining);
			case "bossBarShowCurrentChunk" -> Boolean.toString(config.bossBarShowCurrentChunk);
			case "bossBarShowRate" -> Boolean.toString(config.bossBarShowRate);
			case "bossBarShowEta" -> Boolean.toString(config.bossBarShowEta);
			default -> throw new IllegalArgumentException(key);
		};
	}

	private static LucTaskManager taskManager(CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		return LucTaskManager.get(server);
	}

	private static List<String> dimensionSuggestions(CommandSourceStack source) {
		Set<String> suggestions = new LinkedHashSet<>();
		suggestions.add("overworld");
		suggestions.add("nether");
		suggestions.add("end");
		for (ServerLevel level : ServerLevels.all(source.getServer())) {
			String dimension = LucDimensions.asString(level);
			if (!dimension.equals(LucDimensions.OVERWORLD) && !dimension.equals(LucDimensions.NETHER) && !dimension.equals(LucDimensions.END)) {
				suggestions.add(dimension);
			}
		}
		return new ArrayList<>(suggestions);
	}

	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestConfigValues(
		CommandContext<CommandSourceStack> context,
		com.mojang.brigadier.suggestion.SuggestionsBuilder builder
	) {
		String key = StringArgumentType.getString(context, "key");
		if (key.equals("defaultRelightMode")) {
			return SharedSuggestionProvider.suggest(RELIGHT_MODE_SUGGESTIONS, builder);
		}
		if (key.equals("maxInFlightChunks") || key.equals("maxRelightsPerTick")) {
			return SharedSuggestionProvider.suggest(new String[] {"0", "16", "32", "48", "80", "128", "256"}, builder);
		}
		if (key.equals("saveFlushIntervalSeconds")) {
			return SharedSuggestionProvider.suggest(new String[] {"10", "30", "60", "120"}, builder);
		}
		if (key.equals("enableVoxyCompat")) {
			return SharedSuggestionProvider.suggest(BOOLEAN_SUGGESTIONS, builder);
		}
		return SharedSuggestionProvider.suggest(BOOLEAN_SUGGESTIONS, builder);
	}

	private static boolean parseBoolean(String value) {
		if (value.equalsIgnoreCase("true")) {
			return true;
		}
		if (value.equalsIgnoreCase("false")) {
			return false;
		}
		throw new IllegalArgumentException("Expected true or false");
	}

	private static int parseNonNegativeInt(String value, String key) {
		int parsed = parseInt(value, key);
		if (parsed < 0) {
			throw new IllegalArgumentException(key + " must be >= 0");
		}
		return parsed;
	}

	private static int parsePositiveInt(String value, String key) {
		int parsed = parseInt(value, key);
		if (parsed <= 0) {
			throw new IllegalArgumentException(key + " must be > 0");
		}
		return parsed;
	}

	private static int parseInt(String value, String key) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("Expected an integer for " + key, exception);
		}
	}

	private static boolean isKnownConfigKey(String key) {
		for (String configKey : CONFIG_KEYS) {
			if (configKey.equals(key)) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasRequiredPermission(CommandSourceStack source) {
		Boolean direct = tryBooleanMethod(source, "hasPermission", 2);
		if (direct != null) {
			return direct;
		}

		Boolean legacy = tryBooleanMethod(source, "hasPermissionLevel", 2);
		if (legacy != null) {
			return legacy;
		}

		Object permissions = tryZeroArgMethod(source, "permissions");
		if (permissions != null) {
			Boolean permissionObjectResult = tryBooleanMethod(permissions, "hasPermission", 2);
			if (permissionObjectResult != null) {
				return permissionObjectResult;
			}
		}

		return true;
	}

	private static Object tryZeroArgMethod(Object target, String name) {
		try {
			Method method = target.getClass().getMethod(name);
			method.setAccessible(true);
			return method.invoke(target);
		} catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
			return null;
		}
	}

	private static Boolean tryBooleanMethod(Object target, String name, int value) {
		try {
			Method method = target.getClass().getMethod(name, int.class);
			if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) {
				return null;
			}
			method.setAccessible(true);
			Object result = method.invoke(target, value);
			return result instanceof Boolean bool ? bool : null;
		} catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
			return null;
		}
	}

	private static CommandSyntaxException configError(String message) {
		return new DynamicCommandExceptionType(value -> TextComponents.literal(String.valueOf(value))).create(message);
	}

	@FunctionalInterface
	private interface CommandExecutor {
		int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException;
	}

	private record BlockCoordinate(int x, int z) {
	}
}
