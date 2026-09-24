package ai.moeru.airicraft.agent.session;

import java.util.function.IntPredicate;

final class LanPortScan {
	static final int DEFAULT_PORT = 25565;
	static final int MAX_PORT = 65535;
	static final String PORT_PROPERTY = "airicraft.lanPort";

	private LanPortScan() {
	}

	static int openFirstAvailable(IntPredicate openAttempt) {
		Integer configured = configuredPort(System.getProperty(PORT_PROPERTY));
		// A hosted playtest forwards one known port; silently moving to another would strand remote testers.
		return configured == null
			? openFirstAvailable(DEFAULT_PORT, MAX_PORT, openAttempt)
			: openFirstAvailable(configured, configured, openAttempt);
	}

	static Integer configuredPort(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			int port = Integer.parseInt(value.strip());
			if (port >= 1 && port <= MAX_PORT) {
				return port;
			}
		}
		catch (NumberFormatException ignored) {
		}
		throw new LanPortUnavailableException("Invalid " + PORT_PROPERTY + ": " + value);
	}

	static int openFirstAvailable(int firstPort, int lastPort, IntPredicate openAttempt) {
		for (int port = firstPort; port <= lastPort; port++) {
			if (openAttempt.test(port)) {
				return port;
			}
		}
		throw new LanPortUnavailableException("No LAN port available from " + firstPort + " to " + lastPort);
	}

	static final class LanPortUnavailableException extends RuntimeException {
		LanPortUnavailableException(String message) {
			super(message);
		}
	}
}
