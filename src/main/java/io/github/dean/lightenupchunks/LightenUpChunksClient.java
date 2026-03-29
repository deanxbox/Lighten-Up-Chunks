package io.github.dean.lightenupchunks;

import net.fabricmc.api.ClientModInitializer;

public final class LightenUpChunksClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		LucConfigManager.get();
	}
}
