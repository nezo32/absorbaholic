package dev.absorbaholic.core;

/** Weighted mix of 0xRRGGBB colors (the aura color of a player: sources' colors weighted by trait level). Pure. */
public final class ColorMix {
	/** Neutral aura color when there is nothing to mix. */
	public static final int DEFAULT = 0xB9A6FF;

	private ColorMix() {}

	/** Weighted average per channel in linear light; weights &lt;= 0 are skipped; nothing left → {@link #DEFAULT}. */
	public static int mix(int[] colors, double[] weights) {
		double r = 0, g = 0, b = 0, total = 0;
		for (int i = 0; i < colors.length && i < weights.length; i++) {
			double w = weights[i];
			if (!(w > 0)) continue;
			r += w * toLinear((colors[i] >> 16) & 0xFF);
			g += w * toLinear((colors[i] >> 8) & 0xFF);
			b += w * toLinear(colors[i] & 0xFF);
			total += w;
		}
		if (total <= 0) return DEFAULT;
		return (toSrgb(r / total) << 16) | (toSrgb(g / total) << 8) | toSrgb(b / total);
	}

	/** Parses "#RRGGBB" (case-insensitive); -1 when malformed. */
	public static int parseHex(String hex) {
		if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') return -1;
		try {
			return Integer.parseInt(hex.substring(1), 16);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static double toLinear(int c) {
		double v = c / 255.0;
		return v * v;
	}

	private static int toSrgb(double linear) {
		return (int) Math.round(Math.sqrt(Math.clamp(linear, 0.0, 1.0)) * 255.0);
	}
}
