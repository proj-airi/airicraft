package ai.moeru.airicraft.airi;

import com.google.gson.JsonObject;

/**
 * The identity this client announces. {@code instanceId} changes per game run and {@code extensionId} stays stable.
 */
public record AiriModuleIdentity(String name, String instanceId, String extensionId) {
	JsonObject toJson() {
		JsonObject extension = new JsonObject();
		extension.addProperty("id", extensionId);
		JsonObject identity = new JsonObject();
		identity.addProperty("id", instanceId);
		identity.add("extension", extension);
		return identity;
	}

	JsonObject sourceJson() {
		JsonObject plugin = new JsonObject();
		plugin.addProperty("id", extensionId);
		JsonObject source = toJson();
		source.addProperty("kind", "plugin");
		source.add("plugin", plugin);
		return source;
	}

	boolean matches(JsonObject identity) {
		if (identity == null || !identity.has("id") || !identity.get("id").isJsonPrimitive()) {
			return false;
		}
		return instanceId.equals(identity.get("id").getAsString());
	}
}
