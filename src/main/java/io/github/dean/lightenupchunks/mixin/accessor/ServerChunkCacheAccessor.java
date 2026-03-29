package io.github.dean.lightenupchunks.mixin.accessor;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccessor {
	@Accessor("chunkMap")
	ChunkMap lightenupchunks$getChunkMap();
}
