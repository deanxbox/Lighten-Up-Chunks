package io.github.dean.lightenupchunks;

import io.github.dean.lightenupchunks.command.LucCommands;
import io.github.dean.lightenupchunks.task.LucTaskManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LightenUpChunks implements ModInitializer {
	public static final String MOD_ID = "lighten-up-chunks";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LucConfigManager.get();
		CommandRegistrationCallback.EVENT.register(LucCommands::register);
		ServerLifecycleEvents.SERVER_STARTED.register(LucTaskManager::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(LucTaskManager::onServerStopping);
		ServerTickEvents.END_SERVER_TICK.register(LucTaskManager::onEndServerTick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			var player = PlayerLookup.resolve(handler);
			if (player != null) {
				LucTaskManager.get(server).onPlayerJoin(player);
			}
		});
		LOGGER.info("Lighten Up, Chunks initialised");
	}
}
