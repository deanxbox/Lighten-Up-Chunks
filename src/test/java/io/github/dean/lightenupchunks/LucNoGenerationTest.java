package io.github.dean.lightenupchunks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LucNoGenerationTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void vetoesOnlyClaimedChunksAndClearsOnRelease() {
		Object map = new Object();
		ChunkPos claimed = new ChunkPos(3, -4);
		ChunkPos other = new ChunkPos(9, 9);

		assertFalse(LucNoGeneration.vetoGeneration(map, claimed));
		LucNoGeneration.claim(map, List.of(claimed));
		assertFalse(LucNoGeneration.vetoGeneration(map, other));
		assertTrue(LucNoGeneration.vetoGeneration(map, claimed));
		assertTrue(LucNoGeneration.anyBlocked(map, List.of(other, claimed)));

		LucNoGeneration.release(map, List.of(claimed));
		assertFalse(LucNoGeneration.anyBlocked(map, List.of(claimed)));
		assertFalse(LucNoGeneration.vetoGeneration(map, claimed));
	}

	@Test
	void overlappingClaimsSurviveSingleRelease() {
		Object map = new Object();
		ChunkPos shared = new ChunkPos(0, 0);
		LucNoGeneration.claim(map, List.of(shared));
		LucNoGeneration.claim(map, List.of(shared));
		LucNoGeneration.release(map, List.of(shared));
		assertTrue(LucNoGeneration.vetoGeneration(map, shared));
		LucNoGeneration.release(map, List.of(shared));
		assertFalse(LucNoGeneration.vetoGeneration(map, shared));
	}
}
