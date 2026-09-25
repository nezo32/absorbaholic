package dev.absorbaholic.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ColorMixTest {
	@Test
	void mix() {
		assertEquals(0xFF0000, ColorMix.mix(new int[] {0xFF0000}, new double[] {3}));
		assertEquals(ColorMix.DEFAULT, ColorMix.mix(new int[] {}, new double[] {}));
		assertEquals(ColorMix.DEFAULT, ColorMix.mix(new int[] {0xFF0000}, new double[] {0}));
		int mixed = ColorMix.mix(new int[] {0xFF0000, 0x0000FF}, new double[] {1, 1});
		assertEquals(0xB400B4, mixed, "linear-light average of red and blue");
	}

	@Test
	void parseHex() {
		assertEquals(0x3B2754, ColorMix.parseHex("#3B2754"));
		assertEquals(0x3B2754, ColorMix.parseHex("#3b2754"));
		assertEquals(-1, ColorMix.parseHex("3B2754"));
		assertEquals(-1, ColorMix.parseHex("#3B27"));
		assertEquals(-1, ColorMix.parseHex("#GGGGGG"));
		assertEquals(-1, ColorMix.parseHex(null));
	}
}
