package ai.moeru.airicraft.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class OnboardingStateTest {
	@TempDir Path directory;

	@Test
	void completionSurvivesRestartButClearedProviderRequiresSetupAgain() throws Exception {
		var draft = SettingsDraft.open(directory);
		assertTrue(OnboardingState.required(draft));
		draft.set("agent.yml", "apiKey", "secret");
		draft.set("agent.yml", "model", "model");
		assertTrue(OnboardingState.required(draft));
		OnboardingState.complete(draft);
		draft.saveAndReload(() -> {});
		assertFalse(OnboardingState.required(SettingsDraft.open(directory)));
		draft = SettingsDraft.open(directory);
		draft.set("agent.yml", "apiKey", "");
		assertTrue(OnboardingState.required(draft));
	}

	@Test
	void localCodexDoesNotRequireAnApiKey() throws Exception {
		var draft = SettingsDraft.open(directory);
		draft.set("agent.yml", "plannerBackend", "codex-app-server");
		OnboardingState.complete(draft);
		assertFalse(OnboardingState.required(draft));
	}
}
