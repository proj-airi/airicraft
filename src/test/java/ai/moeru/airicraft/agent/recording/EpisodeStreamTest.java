package ai.moeru.airicraft.agent.recording;

import ai.moeru.airicraft.agent.debug.AgentDebugTimelineEntry;
import ai.moeru.airicraft.agent.debug.AgentDebugTimelineQueryResult;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EpisodeStreamTest {
	@TempDir Path world;

	private static SemanticEventQueryResult events(long latest, SemanticEvent... events) {
		return new SemanticEventQueryResult(1, latest, false, List.of(events));
	}

	private static AgentDebugTimelineQueryResult timeline(long latest, AgentDebugTimelineEntry... entries) {
		return new AgentDebugTimelineQueryResult(1, latest, false, List.of(entries));
	}

	@Test void appendsOnlyNewRecordsAndKeepsThemAcrossInstances() throws Exception {
		var stream = new EpisodeStream(world);
		var first = new SemanticEvent(1, 10, 1000, "task.started", Map.of("id", "w1"));
		var second = new SemanticEvent(2, 20, 2000, "task.completed", Map.of("id", "w1"));
		var step = new AgentDebugTimelineEntry(1, 15, 1500, "tool", "call", "navigate_to", Map.of(), Map.of());
		stream.drain(since -> since == null ? events(1, first) : events(1), since -> timeline(1, step), "a");
		stream.drain(since -> since == null ? events(2, first, second) : events(2, second), since -> timeline(1), "b");
		assertEquals(3, Files.readAllLines(stream.file()).size());
		var reopened = new EpisodeStream(world);
		reopened.drain(since -> events(2), since -> timeline(1), "c");
		assertEquals(3, Files.readAllLines(stream.file()).size());
	}

	@Test void restartedSourceSequenceIsReadFromItsStart() throws Exception {
		var stream = new EpisodeStream(world);
		var late = new SemanticEvent(50, 500, 1000, "task.completed", Map.of());
		var restarted = new SemanticEvent(1, 5, 2000, "session.world_loaded", Map.of());
		stream.drain(since -> events(50, late), since -> timeline(0), "a");
		stream.drain(since -> since == null ? events(1, restarted) : events(1), since -> timeline(0), "b");
		var lines = Files.readAllLines(stream.file());
		assertEquals(2, lines.size());
		assertEquals(true, lines.getLast().contains("session.world_loaded"));
	}
}
