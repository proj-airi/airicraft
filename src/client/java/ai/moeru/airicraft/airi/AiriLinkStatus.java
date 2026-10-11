package ai.moeru.airicraft.airi;

/** A snapshot of the AIRI link for status output. {@code lastError} is empty when there was no failure. */
public record AiriLinkStatus(AiriLink.State state, String url, String lastError) {
	public String describe() {
		String text = "AIRI link: " + state.wireValue();
		if (state != AiriLink.State.DISABLED) {
			text += " (" + url + ")";
		}
		if (!lastError.isEmpty()) {
			text += ", last error: " + lastError;
		}
		return text;
	}
}
