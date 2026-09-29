package ai.moeru.airicraft.rules;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * One rule module's source. {@code origin} names where it came from, for example {@code bundled:attention/default.js}
 * or a config file path; {@code hook} is the hook point it serves (spec 4.12).
 */
public record RuleModule(String origin, String source, Hook hook) {
	public static final int MAX_SOURCE_CHARS = 32_768;
	public static final String BUNDLED_ATTENTION = "bundled:attention/default.js";
	public static final String BUNDLED_SALIENCE = "bundled:salience/default.js";

	/** {@code attention}: events to wake decisions; {@code salience}: perception candidates to percepts. */
	public enum Hook { ATTENTION, SALIENCE }

	public RuleModule {
		Objects.requireNonNull(origin, "origin");
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(hook, "hook");
	}

	public RuleModule(String origin, String source) {
		this(origin, source, Hook.ATTENTION);
	}

	public static RuleModule bundledAttention() {
		return new RuleModule(BUNDLED_ATTENTION, resource("attention/default.js"), Hook.ATTENTION);
	}

	public static RuleModule bundledSalience() {
		return new RuleModule(BUNDLED_SALIENCE, resource("salience/default.js"), Hook.SALIENCE);
	}

	public static RuleModule bundled(Hook hook) {
		return hook == Hook.SALIENCE ? bundledSalience() : bundledAttention();
	}

	public boolean bundled() {
		return origin.startsWith("bundled:");
	}

	/** A short content hash, so a changed override is a different module. */
	public String sha() {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	static String resource(String name) {
		try (InputStream stream = RuleModule.class.getResourceAsStream("/airicraft/rules/" + name)) {
			if (stream == null) throw new IllegalStateException("missing bundled rule resource " + name);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			throw new IllegalStateException("cannot read bundled rule resource " + name, exception);
		}
	}
}
