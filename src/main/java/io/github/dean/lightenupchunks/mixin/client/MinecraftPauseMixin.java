package io.github.dean.lightenupchunks.mixin.client;

import io.github.dean.lightenupchunks.LucConfigManager;
import io.github.dean.lightenupchunks.task.LucTaskManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftPauseMixin {
	@Inject(method = "isPaused", at = @At("HEAD"), cancellable = true)
	private void luc$keepRunningWhilePaused(CallbackInfoReturnable<Boolean> cir) {
		Minecraft minecraft = (Minecraft) (Object) this;
		if (!LucConfigManager.get().keepRunningWhilePaused || !minecraft.hasSingleplayerServer()) {
			return;
		}

		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server != null && LucTaskManager.get(server).hasActiveTask()) {
			cir.setReturnValue(false);
		}
	}
}
