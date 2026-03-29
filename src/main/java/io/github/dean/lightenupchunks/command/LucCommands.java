package io.github.dean.lightenupchunks.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
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
import io.github.dean.lightenupchunks.TextComponents;
import io.github.dean.lightenupchunks.task.LucTaskManager;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

public final class LucCommands {
	private static final String[] DIMENSION_SUGGESTIONS = new String[] {
		LucDimensions.OVERWORLD,
		"nether",
		"end",
		LucDimensions.NETHER,
		LucDimensions.END
	};
	private static final String[] CONFIG_KEYS = new String[] {
		"keepRunningWhilePaused",
		"calculateOnlyEmptyLightChunks",
		"bossBarShowPercentage",
		"bossBarShowCounts",
		"bossBarShowRemaining",
		"bossBarShowCurrentChunk",
		"bossBarShowRate",
		"bossBarShowEta"
	};
	private static final DynamicCommandExceptionType INVALID_DIMENSION = new DynamicCommandExceptionType(value ->
		TextComponents.literal("Invalid dimension id: " + value)
	);
	private static final DynamicCommandExceptionType INVALID_CONFIG_KEY = new DynamicCommandExceptionType(value ->
		TextComponents.literal("Unknown config key: " + value)
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
			.then(literal("reset")
				.executes(context -> taskManager(context.getSource()).resetSelection(context.getSource())))
			.then(literal("selection")
				.executes(context -> taskManager(context.getSource()).selection(context.getSource())))
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
			.then(literal("corners")
				.then(cornersArguments(context -> taskManager(context.getSource()).configureRegion(
					context.getSource(),
					IntegerArgumentType.getInteger(context, "x1"),
					IntegerArgumentType.getInteger(context, "z1"),
					IntegerArgumentType.getInteger(context, "x2"),
					IntegerArgumentType.getInteger(context, "z2")
				))))
			.then(buildStartCommand())
			.then(literal("pause")
				.executes(context -> taskManager(context.getSource()).pause(context.getSource())))
			.then(literal("continue")
				.executes(context -> taskManager(context.getSource()).resume(context.getSource())))
			.then(literal("resume")
				.executes(context -> taskManager(context.getSource()).resume(context.getSource())))
			.then(literal("cancel")
				.executes(context -> taskManager(context.getSource()).cancel(context.getSource())));
		dispatcher.register(root);
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
			.then(literal("set")
				.then(configKeyArgument("key")
					.then(argument("value", BoolArgumentType.bool())
						.executes(context -> setConfigValue(
							context.getSource(),
							StringArgumentType.getString(context, "key"),
							BoolArgumentType.getBool(context, "value")
						)))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> buildStartCommand() {
		return literal("start")
			.executes(context -> taskManager(context.getSource()).startConfigured(context.getSource()))
			.then(buildCurrentWorldStartCommand())
			.then(buildCurrentRadiusStartCommand())
			.then(literal("region").then(startRegionArguments()))
			.then(literal("corners").then(startRegionArguments()))
			.then(buildExplicitDimensionStartCommand());
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
		builder.then(buildExplicitDimensionRegionCommand("corners"));
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
			.suggests((context, builder) -> SharedSuggestionProvider.suggest(DIMENSION_SUGGESTIONS, builder));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> configKeyArgument(String name) {
		return argument(name, StringArgumentType.word())
			.suggests((context, builder) -> SharedSuggestionProvider.suggest(CONFIG_KEYS, builder));
	}

	private static String parseDimension(CommandContext<CommandSourceStack> context, String argumentName) throws CommandSyntaxException {
		String raw = StringArgumentType.getString(context, argumentName);
		try {
			return LucDimensions.normalize(raw);
		} catch (RuntimeException exception) {
			throw INVALID_DIMENSION.create(raw);
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
				" | calculateOnlyEmptyLightChunks=" + config.calculateOnlyEmptyLightChunks +
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

	private static int setConfigValue(CommandSourceStack source, String key, boolean value) throws CommandSyntaxException {
		try {
			LucConfigManager.update(config -> applyConfigValue(config, key, value));
		} catch (IllegalArgumentException exception) {
			throw INVALID_CONFIG_KEY.create(key);
		} catch (IOException exception) {
			throw configError("Failed to save config: " + exception.getMessage());
		}
		CommandFeedback.sendSuccess(source, "Set " + key + "=" + value + " in " + LucConfigManager.path() + ".", true);
		return 1;
	}

	private static void applyConfigValue(LucConfig config, String key, boolean value) {
		switch (key) {
			case "keepRunningWhilePaused" -> config.keepRunningWhilePaused = value;
			case "calculateOnlyEmptyLightChunks" -> config.calculateOnlyEmptyLightChunks = value;
			case "bossBarShowPercentage" -> config.bossBarShowPercentage = value;
			case "bossBarShowCounts" -> config.bossBarShowCounts = value;
			case "bossBarShowRemaining" -> config.bossBarShowRemaining = value;
			case "bossBarShowCurrentChunk" -> config.bossBarShowCurrentChunk = value;
			case "bossBarShowRate" -> config.bossBarShowRate = value;
			case "bossBarShowEta" -> config.bossBarShowEta = value;
			default -> throw new IllegalArgumentException(key);
		}
	}

	private static LucTaskManager taskManager(CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		return LucTaskManager.get(server);
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
