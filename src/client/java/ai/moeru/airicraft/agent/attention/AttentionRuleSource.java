package ai.moeru.airicraft.agent.attention;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.ConfigLoadException;
import ai.moeru.airicraft.rules.RuleEngine;
import ai.moeru.airicraft.rules.RuleException;
import ai.moeru.airicraft.rules.RuleModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Chooses the attention rule module: {@code config/airicraft/rules/attention.js} when present, otherwise the bundled
 * module. Reload validates an override strictly; startup never fails on one.
 */
public final class AttentionRuleSource {
	public static final String RELATIVE_PATH = "rules/attention.js";
	static final Duration VALIDATE_TIMEOUT = Duration.ofSeconds(30);

	private AttentionRuleSource() {
	}

	/** Startup: an unreadable override logs a warning and uses the bundled module; the policy reverts one that fails to load. */
	public static RuleModule load() {
		return load(configDir());
	}

	/** Reload: an override must compile and step an empty input, or reload fails and the old runtime keeps running. */
	public static RuleModule loadStrict() throws ConfigLoadException {
		return loadStrict(configDir(), VALIDATE_TIMEOUT);
	}

	static RuleModule load(Path configDir) {
		Path path = configDir.resolve(RELATIVE_PATH);
		try {
			return read(path);
		}
		catch (IOException exception) {
			Airicraft.LOGGER.warn("Failed to read {}; using the bundled attention rules", path, exception);
			return RuleModule.bundledAttention();
		}
	}

	static RuleModule loadStrict(Path configDir, Duration timeout) throws ConfigLoadException {
		Path path = configDir.resolve(RELATIVE_PATH);
		RuleModule module;
		try {
			module = read(path);
		}
		catch (IOException exception) {
			throw new ConfigLoadException(path, "Failed to read %s: %s".formatted(RELATIVE_PATH, exception.getMessage()), exception);
		}
		if (module.bundled()) return module;
		try {
			RuleEngine.validate(module, timeout);
		}
		catch (RuleException exception) {
			throw new ConfigLoadException(path, "Invalid %s (%s): %s".formatted(RELATIVE_PATH, exception.code(), exception.getMessage()), exception);
		}
		return module;
	}

	private static RuleModule read(Path path) throws IOException {
		if (Files.notExists(path)) return RuleModule.bundledAttention();
		return new RuleModule("config:" + RELATIVE_PATH, Files.readString(path, StandardCharsets.UTF_8));
	}

	private static Path configDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("airicraft");
	}
}
