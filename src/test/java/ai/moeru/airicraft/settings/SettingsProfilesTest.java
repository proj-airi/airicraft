package ai.moeru.airicraft.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SettingsProfilesTest {
	@TempDir Path directory;
	private SettingsDraft open() throws Exception {
		SettingsDraft draft = SettingsDraft.open(directory);
		draft.get("agent.yml", "model", "default-model");
		draft.get("agent.yml", "apiKey", "");
		draft.get("agent.yml", "providerBaseUrl", "https://example.com/v1");
		draft.get("agent.yml", "idleCooldownSeconds", 30);
		return draft;
	}
	@Test void switchesCredentialsWithoutSwitchingBehaviourAndPersists() throws Exception {
		Files.writeString(directory.resolve("agent.yml"), "model: first\napiKey: first-secret\n");
		SettingsDraft draft = open();
		SettingsProfiles profiles = new SettingsProfiles(draft);
		profiles.duplicate("Second.provider");
		draft.set("agent.yml", "model", "second");
		draft.set("agent.yml", "apiKey", "second-secret");
		draft.set("agent.yml", "idleCooldownSeconds", 90);
		profiles.select("Default");
		assertEquals("first", draft.get("agent.yml", "model", ""));
		assertEquals("first-secret", draft.get("agent.yml", "apiKey", ""));
		assertEquals(90, draft.get("agent.yml", "idleCooldownSeconds", 0));
		profiles.select("Second.provider");
		profiles.stage();
		assertTrue(draft.saveAndReload(() -> {}));
		SettingsDraft reopened = open();
		SettingsProfiles restored = new SettingsProfiles(reopened);
		assertEquals("Second.provider", restored.active());
		restored.stage();
		assertFalse(reopened.isDirty(), "Opening saved profiles must not dirty settings");
		assertEquals("second-secret", reopened.get("agent.yml", "apiKey", ""));
		restored.select("Default");
		assertEquals("first", reopened.get("agent.yml", "model", ""));
	}
	@Test void openAndCancelDoNotPersistAndNamesAreValidated() throws Exception {
		SettingsDraft draft = open();
		SettingsProfiles profiles = new SettingsProfiles(draft);
		profiles.stage();
		assertFalse(draft.isDirty());
		assertThrows(IllegalArgumentException.class, () -> profiles.rename(" "));
		assertThrows(IllegalArgumentException.class, () -> profiles.duplicate("Default"));
		assertThrows(IllegalArgumentException.class, profiles::delete);
		profiles.duplicate("Other");
		profiles.rename("Renamed");
		assertEquals("Renamed", profiles.active());
		profiles.delete();
		assertEquals("Default", profiles.active());
		assertFalse(Files.exists(directory.resolve("agent.yml")));
	}
	@Test void failedReloadRollsBackProfilesAndActiveSettingsTogether() throws Exception {
		Files.writeString(directory.resolve("agent.yml"), "model: old\n");
		SettingsDraft draft = open();
		SettingsProfiles profiles = new SettingsProfiles(draft);
		profiles.duplicate("New");
		draft.set("agent.yml", "model", "new");
		profiles.stage();
		assertThrows(IllegalStateException.class, () -> draft.saveAndReload(() -> {throw new IllegalStateException();}));
		assertEquals("model: old\n", Files.readString(directory.resolve("agent.yml")));
		assertTrue(draft.saveAndReload(() -> {}));
	}
	@Test void manualEditsToActiveYamlAreKeptWhenSwitchingAway() throws Exception {
		SettingsDraft draft = open();
		SettingsProfiles profiles = new SettingsProfiles(draft);
		profiles.duplicate("Other");
		profiles.stage();
		draft.saveAndReload(() -> {});
		Path file = directory.resolve("agent.yml");
		org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
		java.util.Map<String, Object> values = yaml.load(Files.readString(file));
		values.put("model", "externally-edited");
		Files.writeString(file, yaml.dump(values));
		SettingsDraft reopened = open();
		SettingsProfiles restored = new SettingsProfiles(reopened);
		restored.select("Default");
		assertEquals("default-model", reopened.get("agent.yml", "model", ""));
		restored.select("Other");
		assertEquals("externally-edited", reopened.get("agent.yml", "model", ""));
	}

}
