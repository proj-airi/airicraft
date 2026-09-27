package ai.moeru.airicraft.agent.events;

import java.util.Set;

/** One declared event id, or a dynamic family when {@code prefix} is true. */
public record EventTypeSpec(String id, boolean prefix, EventFamily family, Set<String> producers,
	EventVisibility observeVisibility, EventRoutingProfile routing) { }
