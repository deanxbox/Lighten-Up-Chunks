package io.github.dean.lightenupchunks.mixin.accessor;

import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkAccess.class)
public interface ChunkAccessAccessor {
	@Accessor("unsaved")
	void lightenupchunks$setUnsaved(boolean unsaved);
}
