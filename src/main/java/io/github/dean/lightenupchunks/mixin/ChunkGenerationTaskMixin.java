package io.github.dean.lightenupchunks.mixin;

import io.github.dean.lightenupchunks.LucNoGeneration;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.GeneratingChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkGenerationTask.class)
public abstract class ChunkGenerationTaskMixin {
	@Shadow
	@Final
	private GeneratingChunkMap chunkMap;

	@Shadow
	@Final
	private ChunkPos pos;

	@Shadow
	public abstract void markForCancellation();

	// Every generation path (partial/missing/corrupt chunk, missing neighbours) goes through scheduleLayer(.., needsGeneration=true).
	@Inject(method = "scheduleLayer", at = @At("HEAD"), cancellable = true)
	private void lightenupchunks$vetoGeneration(ChunkStatus status, boolean needsGeneration, CallbackInfo ci) {
		if (needsGeneration && LucNoGeneration.vetoGeneration(this.chunkMap, this.pos)) {
			this.markForCancellation();
			ci.cancel();
		}
	}
}
