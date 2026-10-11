package ai.moeru.airicraft.airi;

import com.google.gson.JsonObject;

/** One decoded AIRI protocol event. {@code metadata} is empty when the sender gave none. */
public record AiriEvent(String type, JsonObject data, JsonObject metadata) {
}
