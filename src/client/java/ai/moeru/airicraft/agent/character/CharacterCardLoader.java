package ai.moeru.airicraft.agent.character;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.ConfigLoadException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Loads {@code config/airicraft/character.json} when present, otherwise the built-in card.
 * {@code character.json.example} is rewritten to match the built-in card, so edits belong in {@code character.json}.
 */
public final class CharacterCardLoader {
	static final String EXAMPLE_FILENAME = "character.json.example";
	static final String CONFIG_FILENAME = "character.json";

	private CharacterCardLoader() {
	}

	public static CharacterCard load() {
		try {
			return loadInternal();
		}
		catch (ConfigLoadException exception) {
			Airicraft.LOGGER.warn("Failed to load character card; using the built-in character", exception);
			return CharacterCard.defaults();
		}
	}

	public static CharacterCard loadStrict() throws ConfigLoadException {
		return loadInternal();
	}

	private static CharacterCard loadInternal() throws ConfigLoadException {
		Path configDir = FabricLoader.getInstance().getConfigDir().resolve("airicraft");
		Path configPath = configDir.resolve(CONFIG_FILENAME);
		try {
			Files.createDirectories(configDir);
			refreshExample(configDir.resolve(EXAMPLE_FILENAME));
			if (Files.notExists(configPath)) return CharacterCard.defaults();
			return CharacterCards.parse(Files.readString(configPath, StandardCharsets.UTF_8), configPath.toString());
		}
		catch (IOException | RuntimeException exception) {
			String detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
			throw new ConfigLoadException(configPath, "Failed to load %s: %s".formatted(configPath.getFileName(), detail), exception);
		}
	}

	private static void refreshExample(Path path) throws IOException {
		byte[] builtIn;
		try (InputStream stream = CharacterCardLoader.class.getResourceAsStream(CharacterCards.BUILT_IN_RESOURCE)) {
			if (stream == null) throw new IOException("Missing embedded character card: " + CharacterCards.BUILT_IN_RESOURCE);
			builtIn = stream.readAllBytes();
		}
		if (Files.exists(path) && Arrays.equals(Files.readAllBytes(path), builtIn)) return;
		Files.write(path, builtIn);
	}
}
