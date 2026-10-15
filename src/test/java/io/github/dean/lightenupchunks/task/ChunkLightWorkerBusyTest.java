package io.github.dean.lightenupchunks.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ChunkLightWorkerBusyTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void busyWhileAnyNeighbourInFlightUnlessForcedAndIdle() {
		ChunkPos pos = new ChunkPos(0, 0);
		Set<ChunkPos> inFlight = Set.of(new ChunkPos(1, 1));
		assertTrue(ChunkLightWorker.isBusy(pos, inFlight::contains, n -> false, false));
		assertFalse(ChunkLightWorker.isBusy(pos, inFlight::contains, n -> false, true)); // forced: only mid-relight neighbours block
		assertTrue(ChunkLightWorker.isBusy(pos, inFlight::contains, n -> true, true));
		assertFalse(ChunkLightWorker.isBusy(pos, n -> n.equals(new ChunkPos(2, 0)), n -> true, false)); // outside the 3x3
	}
}
