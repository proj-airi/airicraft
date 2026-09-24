package ai.moeru.airicraft.agent.session;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LanPortScanTest {
	@Test
	void startsAtDefaultPortAndIncrementsUntilOpenSucceeds() {
		List<Integer> attempts = new ArrayList<>();

		int port = LanPortScan.openFirstAvailable(25565, 25568, candidate -> {
			attempts.add(candidate);
			return candidate == 25567;
		});

		assertEquals(25567, port);
		assertEquals(List.of(25565, 25566, 25567), attempts);
	}

	@Test
	void failsAfterTryingFullRange() {
		List<Integer> attempts = new ArrayList<>();

		assertThrows(LanPortScan.LanPortUnavailableException.class, () ->
			LanPortScan.openFirstAvailable(25565, 25567, candidate -> {
				attempts.add(candidate);
				return false;
			})
		);
		assertEquals(List.of(25565, 25566, 25567), attempts);
	}

	@Test
	void configuredPortIsOptionalAndValidated() {
		assertNull(LanPortScan.configuredPort(null));
		assertNull(LanPortScan.configuredPort(" "));
		assertEquals(25570, LanPortScan.configuredPort(" 25570 "));
		for (String invalid : new String[]{"0", "65536", "-1", "port"}) {
			assertThrows(LanPortScan.LanPortUnavailableException.class, () -> LanPortScan.configuredPort(invalid));
		}
	}

	@Test
	void configuredPortIsTheOnlyAttempt() {
		List<Integer> attempts = new ArrayList<>();
		String previous = System.getProperty(LanPortScan.PORT_PROPERTY);
		System.setProperty(LanPortScan.PORT_PROPERTY, "25570");
		try {
			assertThrows(LanPortScan.LanPortUnavailableException.class, () ->
				LanPortScan.openFirstAvailable(candidate -> {
					attempts.add(candidate);
					return false;
				})
			);
			assertEquals(List.of(25570), attempts);
		}
		finally {
			if (previous == null) System.clearProperty(LanPortScan.PORT_PROPERTY);
			else System.setProperty(LanPortScan.PORT_PROPERTY, previous);
		}
	}
}
