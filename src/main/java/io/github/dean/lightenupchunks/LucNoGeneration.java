package io.github.dean.lightenupchunks;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;

/**
 * Hard guard against world generation. Chunks claimed here are vetoed by ChunkGenerationTaskMixin the moment
 * vanilla decides they need generation (missing, partial, unreadable data or missing dependencies), so
 * relighting can only ever use chunks that vanilla loads straight from disk.
 * Keyed per ChunkMap instance (one per dimension).
 */
public final class LucNoGeneration {
	private static final Map<Object, Map<Long, AtomicInteger>> CLAIMED = new ConcurrentHashMap<>();
	private static final Map<Object, Set<Long>> REPLACED = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
	private static final Map<Object, Set<Long>> BLOCKED = new ConcurrentHashMap<>();

	private LucNoGeneration() {
	}

	private static long key(ChunkPos pos) {
		return ((long) pos.x << 32) | (pos.z & 0xFFFFFFFFL);
	}

	public static synchronized void claim(Object chunkMap, Collection<ChunkPos> positions) {
		Map<Long, AtomicInteger> claims = CLAIMED.computeIfAbsent(chunkMap, ignored -> new ConcurrentHashMap<>());
		for (ChunkPos pos : positions) {
			claims.computeIfAbsent(key(pos), ignored -> new AtomicInteger()).incrementAndGet();
		}
	}

	public static synchronized void release(Object chunkMap, Collection<ChunkPos> positions) {
		Map<Long, AtomicInteger> claims = CLAIMED.get(chunkMap);
		if (claims == null) {
			return;
		}
		Set<Long> blocked = BLOCKED.get(chunkMap);
		for (ChunkPos pos : positions) {
			long key = key(pos);
			claims.computeIfPresent(key, (ignored, count) -> {
				if (count.decrementAndGet() > 0) {
					return count;
				}
				if (blocked != null) {
					blocked.remove(key);
				}
				return null;
			});
		}
		cleanup(chunkMap);
	}

	/** Called from the generation mixin; true means generation must not start for this chunk. */
	public static synchronized boolean vetoGeneration(Object chunkMap, ChunkPos pos) {
		Map<Long, AtomicInteger> claims = CLAIMED.get(chunkMap);
		if (claims == null || !claims.containsKey(key(pos))) {
			return false;
		}
		BLOCKED.computeIfAbsent(chunkMap, ignored -> ConcurrentHashMap.newKeySet()).add(key(pos));
		return true;
	}

	/** Records that vanilla swapped unreadable data for an empty chunk; tracked for every position, claimed or not. */
	public static void markReplaced(Object chunkMap, ChunkPos pos) {
		REPLACED.computeIfAbsent(chunkMap, ignored -> ConcurrentHashMap.newKeySet()).add(key(pos));
	}

	public static boolean wasReplaced(Object chunkMap, ChunkPos pos) {
		Set<Long> replaced = REPLACED.get(chunkMap);
		return replaced != null && replaced.contains(key(pos));
	}

	public static synchronized boolean anyBlocked(Object chunkMap, Collection<ChunkPos> positions) {
		Set<Long> blocked = BLOCKED.get(chunkMap);
		if (blocked == null || blocked.isEmpty()) {
			return false;
		}
		for (ChunkPos pos : positions) {
			if (blocked.contains(key(pos))) {
				return true;
			}
		}
		return false;
	}

	// Drop per-ChunkMap state once nothing is claimed so closed dimensions are not retained.
	static synchronized void cleanup(Object chunkMap) {
		Map<Long, AtomicInteger> claims = CLAIMED.get(chunkMap);
		if (claims != null && claims.isEmpty()) {
			CLAIMED.remove(chunkMap, claims);
			BLOCKED.remove(chunkMap);
		}
	}
}
