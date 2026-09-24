package dev.absorbaholic.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import dev.absorbaholic.net.DiscoveredPayload;
import dev.absorbaholic.net.NetCodecs;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** The join-time discovered set is split into payloads that fit the network list limit, without losing an id. */
class WorldStateSyncTest {
	private static List<Identifier> ids(int n) {
		return IntStream.range(0, n).mapToObj(i -> Identifier.fromNamespaceAndPath("test", "s" + i)).toList();
	}

	@Test
	void emptySetIsOneReplacingPayload() {
		List<DiscoveredPayload> out = WorldStateSync.discoveredPayloads(List.of());
		assertEquals(1, out.size());
		assertTrue(out.getFirst().replace(), "the first payload replaces the client's set");
		assertTrue(out.getFirst().sources().isEmpty());
	}

	@Test
	void smallSetIsOnePayload() {
		List<Identifier> all = ids(128);
		List<DiscoveredPayload> out = WorldStateSync.discoveredPayloads(all);
		assertEquals(1, out.size());
		assertTrue(out.getFirst().replace());
		assertEquals(all, out.getFirst().sources());
	}

	@Test
	void exactlyTheLimitIsOnePayload() {
		assertEquals(1, WorldStateSync.discoveredPayloads(ids(NetCodecs.MAX_LIST)).size());
	}

	@Test
	void hugeSetIsSplitInOrderWithinTheLimit() {
		List<Identifier> all = ids(NetCodecs.MAX_LIST * 2 + 7);
		List<DiscoveredPayload> out = WorldStateSync.discoveredPayloads(all);
		assertEquals(3, out.size());
		assertTrue(out.getFirst().replace(), "first replaces");
		List<Identifier> joined = new ArrayList<>();
		for (int i = 0; i < out.size(); i++) {
			DiscoveredPayload p = out.get(i);
			if (i > 0) assertFalse(p.replace(), "later payloads add");
			assertTrue(p.sources().size() <= NetCodecs.MAX_LIST, "payload " + i + " within the limit");
			joined.addAll(p.sources());
		}
		assertEquals(all, joined);
	}
}
