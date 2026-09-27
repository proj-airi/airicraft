package ai.moeru.airicraft.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SettingsDraftTest {
	@TempDir Path directory;

	@Test
	void editingAndRevertingDoesNotWriteOrReload() throws Exception {
		Path file = directory.resolve("agent.yml");
		String original = "# Keep this\nmodel: original\n";
		Files.writeString(file, original);
		SettingsDraft draft = SettingsDraft.open(directory);
		draft.set("agent.yml", "model", "changed");
		assertEquals(original, Files.readString(file));
		draft.set("agent.yml", "model", "original");
		assertFalse(draft.saveAndReload(() -> fail("Unchanged settings must not reload")));
		assertEquals(original, Files.readString(file));
		assertFalse(Files.exists(directory.resolve("airicraft.yml")));
	}

	@Test
	void savesBothFilesBeforeReloadAndPreservesUnknownValuesAndComments() throws Exception {
		Files.writeString(directory.resolve("agent.yml"), "# My provider\nmodel: old # chosen model\napiKey: 'keep-secret'\ncustom: {nested: [a, b]}\ncodexAppServer:\n  executable: codex\n  customFlag: true\n");
		Files.writeString(directory.resolve("airicraft.yml"), "readSystemChatMessages: true\n");
		SettingsDraft draft = SettingsDraft.open(directory);
		draft.set("agent.yml", "model", "new:model");
		draft.set("agent.yml", "codexAppServer.model", "local-model");
		draft.set("airicraft.yml", "readSystemChatMessages", false);
		assertTrue(draft.saveAndReload(() -> {
			try {
				Map<String, Object> agent = new Yaml().load(Files.readString(directory.resolve("agent.yml")));
				assertEquals("new:model", agent.get("model"));
				assertEquals("keep-secret", agent.get("apiKey"));
				assertEquals(Map.of("nested", java.util.List.of("a", "b")), agent.get("custom"));
				assertEquals(Map.of("executable", "codex", "model", "local-model", "customFlag", true), agent.get("codexAppServer"));
				Map<String, Object> mod = new Yaml().load(Files.readString(directory.resolve("airicraft.yml")));
				assertEquals(false, mod.get("readSystemChatMessages"));
			} catch (Exception exception) { throw new AssertionError(exception); }
		}));
		String saved = Files.readString(directory.resolve("agent.yml"));
		assertTrue(saved.contains("# My provider"));
		assertTrue(saved.contains("# chosen model"));
	}

	@Test
	void invalidBackendDoesNotReplaceEitherFile() throws Exception {
		Files.writeString(directory.resolve("agent.yml"), "model: old\n");
		SettingsDraft draft = SettingsDraft.open(directory);
		draft.set("agent.yml", "plannerBackend", "unsupported");
		draft.set("airicraft.yml", "readSystemChatMessages", false);
		assertThrows(IllegalArgumentException.class, () -> draft.saveAndReload(() -> fail("Invalid settings must not reload")));
		assertEquals("model: old\n", Files.readString(directory.resolve("agent.yml")));
		assertFalse(Files.exists(directory.resolve("airicraft.yml")));
	}

	@Test
	void failedReloadRestoresOriginalFilesAndKeepsDraftForRetry() throws Exception {
		Files.writeString(directory.resolve("agent.yml"), "model: old\n");
		SettingsDraft draft = SettingsDraft.open(directory);
		draft.set("agent.yml", "model", "new");
		draft.set("airicraft.yml", "readSystemChatMessages", false);
		assertThrows(IllegalStateException.class, () -> draft.saveAndReload(() -> { throw new IllegalStateException("Reload failed"); }));
		assertEquals("model: old\n", Files.readString(directory.resolve("agent.yml")));
		assertFalse(Files.exists(directory.resolve("airicraft.yml")));
		assertEquals("new", draft.get("agent.yml", "model", ""));
		assertTrue(draft.saveAndReload(() -> {}));
	}

	@Test
	void detectsExternalEditsInsteadOfOverwritingThem() throws Exception {
		Path file = directory.resolve("agent.yml");
		Files.writeString(file, "model: old\n");
		SettingsDraft draft = SettingsDraft.open(directory);
		draft.set("agent.yml", "model", "from-menu");
		Files.writeString(file, "model: from-editor\n");
		assertThrows(java.io.IOException.class, () -> draft.saveAndReload(() -> fail("Conflict must not reload")));
		assertEquals("model: from-editor\n", Files.readString(file));
	}

	@Test
	void refusesMalformedYamlRatherThanOverwritingItWithDefaults() throws Exception {
		Files.writeString(directory.resolve("agent.yml"), "[not, a, mapping]\n");
		assertThrows(IllegalArgumentException.class, () -> SettingsDraft.open(directory));
	}
}
