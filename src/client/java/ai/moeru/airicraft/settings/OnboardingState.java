package ai.moeru.airicraft.settings;

final class OnboardingState {
	static boolean required(SettingsDraft draft) {
		if (!draft.get("agent.yml", "onboarding.completed", false)) return true;
		if (draft.get("agent.yml", "plannerBackend", "openai-compatible").equals("codex-app-server")) {
			return draft.get("agent.yml", "codexAppServer.executable", "codex").isBlank();
		}
		return draft.get("agent.yml", "providerBaseUrl", "https://api.openai.com/v1").isBlank()
			|| draft.get("agent.yml", "apiKey", "").isBlank() || draft.get("agent.yml", "model", "").isBlank();
	}
	static void complete(SettingsDraft draft) { draft.set("agent.yml", "onboarding.completed", true); }
}
