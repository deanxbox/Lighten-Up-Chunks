package io.github.dean.lightenupchunks.mixin.accessor;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.util.List;

@Mixin(ChunkMap.class)
public interface ChunkMapInvoker {
	@Invoker("save")
	boolean lightenupchunks$saveChunk(ChunkAccess chunkAccess);

	@Invoker("getPlayers")
	List<ServerPlayer> lightenupchunks$getPlayers(ChunkPos chunkPos, boolean onlyOnWatchDistanceEdge);

	@Invoker("markChunkPendingToSend")
	void lightenupchunks$markChunkPendingToSend(ServerPlayer player, LevelChunk levelChunk);
}
