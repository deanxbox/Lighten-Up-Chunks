package io.github.dean.lightenupchunks.mixin;

import io.github.dean.lightenupchunks.LucNoGeneration;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.level.chunk.ChunkAccess;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
	// Unreadable chunk data is replaced by an empty chunk; flag it so the worker never relights or saves over it.
	@Inject(method = "createEmptyChunk", at = @At("HEAD"))
	private void lightenupchunks$flagReplacement(ChunkPos pos, CallbackInfoReturnable<ChunkAccess> cir) {
		LucNoGeneration.vetoGeneration(this, pos);
	}
}
