package io.github.dean.lightenupchunks.mixin.accessor;

import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelChunk.class)
public interface LevelChunkAccessor {
	@Accessor("unsavedListener")
	LevelChunk.UnsavedListener lightenupchunks$getUnsavedListener();
}
