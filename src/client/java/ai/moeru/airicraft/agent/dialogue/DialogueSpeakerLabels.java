package ai.moeru.airicraft.agent.dialogue;

public final class DialogueSpeakerLabels {
	public static final String AGENT = "agent";
	public static final String SAME_CLIENT_ADMIN = "developer/admin (same client, shares controls)";
	public static final String AIRI = "AIRI (your own self outside the game)";

	private DialogueSpeakerLabels() {
	}

	public static boolean isSameClientAdmin(String speaker) {
		return SAME_CLIENT_ADMIN.equals(speaker);
	}
}
