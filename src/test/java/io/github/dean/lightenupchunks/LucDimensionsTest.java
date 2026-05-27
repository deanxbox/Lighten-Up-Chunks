package io.github.dean.lightenupchunks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LucDimensionsTest {
	@Test
	void normalizeRecognizesVanillaAliases() {
		assertEquals(LucDimensions.OVERWORLD, LucDimensions.normalize("overworld"));
		assertEquals(LucDimensions.NETHER, LucDimensions.normalize("nether"));
		assertEquals(LucDimensions.END, LucDimensions.normalize("the_end"));
	}

	@Test
	void normalizeAddsMinecraftNamespaceWhenMissing() {
		assertEquals("minecraft:custom", LucDimensions.normalize("custom"));
	}

	@Test
	void normalizeRejectsMalformedIds() {
		assertThrows(IllegalArgumentException.class, () -> LucDimensions.normalize(":broken"));
		assertThrows(IllegalArgumentException.class, () -> LucDimensions.normalize("too:many:parts"));
	}

	@Test
	void asStringParsesCommonResourceKeyFormats() {
		assertEquals("minecraft:overworld", LucDimensions.asString("ResourceKey[minecraft:overworld]"));
		assertEquals("modded:moon", LucDimensions.asString("ResourceKey[minecraft:dimension / modded:moon]"));
		assertEquals("modded:sky", LucDimensions.asString("modded:sky"));
	}
}
