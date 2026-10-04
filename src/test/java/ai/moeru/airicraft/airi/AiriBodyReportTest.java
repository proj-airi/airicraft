package ai.moeru.airicraft.airi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiriBodyReportTest {
	private final AtomicInteger ids = new AtomicInteger();
	private final AiriBodyReport report = new AiriBodyReport(() -> "id-" + ids.incrementAndGet());

	private static AiriBodyReport.BodyFacts at(int x) {
		return new AiriBodyReport.BodyFacts(true, false, "minecraft:overworld", x, 64, -3, 18F, 20F, 17, true);
	}

	private static Map<String, Object> work(String id, String state, boolean foreground) {
		var payload = new HashMap<String, Object>();
		payload.put("workId", id);
		payload.put("parentWorkId", "");
		payload.put("state", state);
		payload.put("label", "MINE_BLOCKS");
		payload.put("foreground", foreground);
		payload.put("details", Map.of());
		return payload;
	}

	private static List<String> states(List<AiriBodyReport.Outgoing> events) {
		return events.stream().map(event -> event.type() + " " + event.data().get("state").getAsString()).toList();
	}

	@Test void statusGoesOutOnChangeWithAMinimumInterval() {
		var first = report.status(at(1), 0);
		assertEquals(1, first.size());
		assertEquals("context:update", first.get(0).type());
		assertEquals("status", first.get(0).data().get("lane").getAsString());
		assertEquals("replace-self", first.get(0).data().get("strategy").getAsString());
		assertEquals("The Minecraft body is in minecraft:overworld at 1 64 -3. Health 18 of 20, food 17 of 20. No work is running.",
			first.get(0).data().get("text").getAsString());

		assertEquals(List.of(), report.status(at(1), 1_000), "unchanged");
		assertEquals(List.of(), report.status(at(2), 1_000), "changed too soon");
		assertEquals(1, report.status(at(2), AiriBodyReport.STATUS_MIN_INTERVAL_MS).size());
		assertEquals(1, report.status(at(2), AiriBodyReport.STATUS_MIN_INTERVAL_MS + AiriBodyReport.STATUS_REFRESH_MS).size(), "refresh");
	}

	@Test void resendForcesTheNextStatus() {
		report.status(at(1), 0);
		report.resend();
		assertEquals(1, report.status(at(1), 1).size());
	}

	@Test void statusNamesTheCurrentWork() {
		report.event("work.changed", work("JOB:1", "RUNNING", true), 0);
		assertTrue(report.status(at(1), 0).get(0).data().get("text").getAsString().endsWith("Working on MINE_BLOCKS."));
		report.event("work.changed", work("JOB:1", "SUCCEEDED", false), 0);
		assertTrue(report.status(at(1), AiriBodyReport.STATUS_MIN_INTERVAL_MS).get(0).data().get("text").getAsString()
			.endsWith("No work is running."));
	}

	@Test void aCommandFollowsTheFirstWorkThatStartsAfterIt() {
		assertEquals(List.of("spark:emit queued"), states(report.commandAccepted("c1")));
		assertEquals(List.of("spark:emit working"), states(report.event("work.changed", work("JOB:1", "RUNNING", true), 0)));
		assertEquals(List.of(), report.event("work.changed", work("JOB:2", "RUNNING", true), 0));
		var done = report.event("work.changed", work("JOB:1", "SUCCEEDED", false), 0);
		assertEquals(List.of("spark:emit done"), states(done));
		assertEquals("c1", done.get(0).data().get("eventId").getAsString());
		assertEquals("[\"proj-airi:stage-*\"]", done.get(0).data().get("destinations").toString());
	}

	@Test void aFailedCommandWorkIsBlocked() {
		report.commandAccepted("c1");
		report.event("work.changed", work("JOB:1", "RUNNING", true), 0);
		var failed = work("JOB:1", "FAILED", false);
		failed.put("details", Map.of("failure", "no pickaxe"));

		var events = report.event("work.changed", failed, 0);

		assertEquals(List.of("spark:emit blocked"), states(events));
		assertEquals("Failed MINE_BLOCKS: no pickaxe.", events.get(0).data().get("note").getAsString());
	}

	@Test void aNewCommandDropsTheOpenOne() {
		report.commandAccepted("c1");
		var events = report.commandAccepted("c2");
		assertEquals(List.of("spark:emit dropped", "spark:emit queued"), states(events));
		assertEquals("c1", events.get(0).data().get("eventId").getAsString());
		assertEquals("c2", events.get(1).data().get("eventId").getAsString());
	}

	@Test void aRefusedCommandIsDropped() {
		var event = report.commandRefused("c1", "the planner is off");
		assertEquals("dropped", event.data().get("state").getAsString());
		assertEquals("The body cannot take commands now: the planner is off.", event.data().get("note").getAsString());
		assertEquals(List.of(), report.dropOpenCommand("reload"));
	}

	@Test void damageAlarmsAreThrottled() {
		var payload = Map.<String, Object>of("attackerName", "Zombie", "healthAfter", 12.0F);
		var first = report.event("combat.damage_taken", payload, 0);
		assertEquals("spark:notify", first.get(0).type());
		assertEquals("alarm", first.get(0).data().get("kind").getAsString());
		assertEquals("Source: Zombie. Health now: 12.", first.get(0).data().get("note").getAsString());
		assertEquals(List.of(), report.event("combat.damage_taken", payload, 1_000));
		assertEquals(1, report.event("combat.damage_taken", payload, AiriBodyReport.DAMAGE_MIN_INTERVAL_MS).size());
	}

	@Test void deathAndUnownedFailuresAreAlarms() {
		var died = report.event("player.died", Map.of("dimensionId", "minecraft:the_nether"), 0);
		assertEquals("immediate", died.get(0).data().get("urgency").getAsString());
		var failed = report.event("work.changed", work("JOB:9", "FAILED", true), 0);
		assertEquals("Work in Minecraft failed: MINE_BLOCKS.", failed.get(0).data().get("headline").getAsString());
	}

	@Test void playerChatGoesToTheChatLane() {
		var events = report.event("social.player_spoke", Map.of("player", "Steve", "message", "hello"), 0);
		assertEquals("chat", events.get(0).data().get("lane").getAsString());
		assertEquals("append-self", events.get(0).data().get("strategy").getAsString());
		assertEquals("Steve: hello", events.get(0).data().get("text").getAsString());
		assertEquals(List.of(), report.event("task.started", Map.of(), 0));
	}

	@Test void aForegroundFailureAlarmsAlsoWhenTheTerminalSnapshotIsNotForeground() {
		report.event("work.changed", work("JOB:4", "RUNNING", true), 0);
		var failed = report.event("work.changed", work("JOB:4", "FAILED", false), 0);
		assertEquals("Work in Minecraft failed: MINE_BLOCKS.", failed.get(0).data().get("headline").getAsString());
		assertEquals(List.of(), report.event("work.changed", work("JOB:5", "FAILED", false), 0));
	}

	@Test void addressedChatIsNotReportedTwice() {
		var payload = Map.of("player", "Steve", "message", "hello");
		assertEquals(1, report.event("social.player_spoke", payload, 0).size());
		assertEquals(List.of(), report.event("social.player_addressed_agent", payload, 0));
	}

	@Test void leavingTheWorldDropsTheOpenCommand() {
		report.commandAccepted("cmd-1");
		report.event("work.changed", work("JOB:1", "RUNNING", true), 0);
		assertEquals(List.of("spark:emit dropped"), states(report.worldLeft()));
		assertEquals(List.of(), report.worldLeft());
		assertTrue(report.statusText(at(1)).endsWith("No work is running."));
	}
}
