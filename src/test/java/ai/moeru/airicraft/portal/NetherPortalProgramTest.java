package ai.moeru.airicraft.portal;

import ai.moeru.airicraft.blueprint.BlueprintConstructionProgram;
import ai.moeru.airicraft.blueprint.ConstructionEscape;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetherPortalProgramTest {
	private static final JsonElement OK = JsonParser.parseString("{\"ok\":true}");
	private static final NetherPortalFrame FRAME = new NetherPortalFrame(0, 64, 0, NetherPortalFrame.Axis.X);

	/** Flat ground at y=63; a block can only be placed against an existing solid neighbour, like place_block. */
	private static final class Sim implements BlueprintConstructionProgram.Environment, NetherPortalProgram.World {
		final Map<ConstructionEscape.Position, String> blocks = new HashMap<>();
		boolean validFrameLights = true, playerInOpening, navigationFails, noEntryStance, insideWhenLit;
		int walkTicksNeeded = 5, teleportAfterInside = 80, steps, insideSteps;
		boolean stopped;
		String dimension = "minecraft:overworld";
		final List<String> tools = new ArrayList<>();

		String at(ConstructionEscape.Position p) { return p.y() <= 63 ? "minecraft:stone" : blocks.getOrDefault(p, "minecraft:air"); }
		boolean solid(ConstructionEscape.Position p) { String s = at(p); return !s.equals("minecraft:air") && !s.startsWith("minecraft:fire") && !s.startsWith(NetherPortalFrame.PORTAL); }
		boolean supported(ConstructionEscape.Position p) {
			for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}})
				if (solid(new ConstructionEscape.Position(p.x() + d[0], p.y() + d[1], p.z() + d[2]))) return true;
			return false;
		}
		static ConstructionEscape.Position pos(BlueprintConstructionProgram.Cell c) { return new ConstructionEscape.Position(c.x(), c.y(), c.z()); }
		static ConstructionEscape.Position pos(JsonObject a) { return new ConstructionEscape.Position(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt()); }

		@Override public String state(BlueprintConstructionProgram.Cell cell) { return at(pos(cell)); }
		@Override public boolean eligible(BlueprintConstructionProgram.Cell cell) { return at(pos(cell)).equals("minecraft:air") && supported(pos(cell)); }
		@Override public boolean immediatelyPlaceable(BlueprintConstructionProgram.Cell cell) { return eligible(cell); }
		@Override public boolean blocked(BlueprintConstructionProgram.Cell cell) { var s = at(pos(cell)); return !s.equals("minecraft:air") && !s.equals(cell.state()); }
		@Override public double distanceSquared(BlueprintConstructionProgram.Cell cell) { return 4; }
		@Override public String state(ConstructionEscape.Position position) { return at(position); }
		@Override public ConstructionEscape.Position lightingStance() { return new ConstructionEscape.Position(1, 64, 2); }
		@Override public boolean playerInOpening() { return playerInOpening; }
		@Override public ConstructionEscape.Position entryStance() { return noEntryStance ? null : lightingStance(); }
		@Override public void close() { }
		@Override public NetherPortalProgram.Entry enterStep() {
			if (!dimension.equals("minecraft:overworld")) return NetherPortalProgram.Entry.DIMENSION_CHANGED;
			if (++steps <= walkTicksNeeded) return NetherPortalProgram.Entry.MOVING;
			if (++insideSteps >= teleportAfterInside) dimension = "minecraft:the_nether";
			return NetherPortalProgram.Entry.INSIDE;
		}
		@Override public void stopMoving() { stopped = true; }
		@Override public String dimension() { return dimension; }

		JsonElement apply(JsonObject step) {
			var call = step.getAsJsonObject("value").getAsJsonObject("arguments");
			String name = call.get("name").getAsString();
			var args = call.getAsJsonObject("args");
			tools.add(name);
			switch (name) {
				case "place_block" -> {
					var p = pos(args);
					if (!at(p).equals("minecraft:air") || !supported(p)) return JsonParser.parseString("{\"ok\":false,\"error\":\"support_not_found\"}");
					blocks.put(p, args.get("itemId").getAsString());
				}
				case "break_blocks" -> {
					for (var target : args.getAsJsonArray("targets")) blocks.remove(pos(target.getAsJsonObject()));
				}
				case "use_block" -> {
					assertEquals(FRAME.ignitionCell(), pos(args));
					assertEquals("down", args.get("facePreference").getAsString());
					boolean valid = FRAME.positions(NetherPortalFrame.Role.OBSIDIAN).stream().allMatch(p -> at(p).equals(NetherPortalFrame.OBSIDIAN))
						&& FRAME.positions(NetherPortalFrame.Role.INTERIOR).stream().allMatch(p -> at(p).equals("minecraft:air"));
					if (valid && validFrameLights) {
						FRAME.positions(NetherPortalFrame.Role.INTERIOR).forEach(p -> blocks.put(p, "minecraft:nether_portal[axis=x]"));
						playerInOpening |= insideWhenLit;
					}
					else blocks.put(FRAME.ignitionCell(), "minecraft:fire[age=0]");
				}
				case "navigate_to" -> {
					if (navigationFails) return JsonParser.parseString("{\"ok\":false,\"error\":\"no_path\"}");
					playerInOpening = false;
				}
				default -> { }
			}
			return OK;
		}
	}

	/** Drives the program like PolicyRuntime: resume after each effect, tick while a step is pending. */
	private static JsonObject run(NetherPortalProgram program, Sim sim) {
		CompletableFuture<JsonElement> pending = program.resume(JsonNull.INSTANCE);
		for (int i = 0; i < 2000; i++) {
			int ticks = 0;
			while (!pending.isDone() && ticks++ < 1000) program.tick();
			var step = pending.join().getAsJsonObject();
			if (step.get("done").getAsBoolean()) return step.getAsJsonObject("value");
			// Fire burns out between ticks when the frame is invalid.
			sim.blocks.values().removeIf(s -> s.startsWith("minecraft:fire"));
			pending = program.resume(sim.apply(step));
			sim.blocks.values().removeIf(s -> s.startsWith("minecraft:fire"));
		}
		throw new AssertionError("program did not finish: " + sim.tools);
	}

	private static NetherPortalProgram program(Sim sim, String cornerItem, Map<ConstructionEscape.Position, String> corners) {
		var cells = FRAME.constructionCells(cornerItem, corners);
		return new NetherPortalProgram(FRAME, new BlueprintConstructionProgram(cells, sim), sim, "minecraft:flint_and_steel", true);
	}

	@Test void buildsWholeFrameIncludingTopBarThenLightsAndVerifies() {
		var sim = new Sim();
		var result = run(program(sim, "minecraft:cobblestone", Map.of()), sim);
		assertEquals("lit", result.get("portal").getAsString(), sim.tools.toString());
		for (var p : FRAME.positions(NetherPortalFrame.Role.OBSIDIAN)) assertEquals(NetherPortalFrame.OBSIDIAN, sim.at(p), "frame cell " + p);
		for (var p : FRAME.positions(NetherPortalFrame.Role.CORNER)) assertEquals("minecraft:cobblestone", sim.at(p));
		// The top bar is the playtest failure: both lintel cells must be placed.
		assertEquals(NetherPortalFrame.OBSIDIAN, sim.at(FRAME.at(1, 4)));
		assertEquals(NetherPortalFrame.OBSIDIAN, sim.at(FRAME.at(2, 4)));
		assertTrue(sim.tools.indexOf("use_block") > sim.tools.lastIndexOf("place_block"), sim.tools.toString());
		assertEquals(1, result.get("ignitions").getAsInt());
	}

	@Test void completesThePlaytestFrameThatLackedItsTopBar() {
		var sim = new Sim();
		// Pillars and base from the playtest: everything but the lintel and the top corners.
		for (var slot : FRAME.slots()) if (slot.role() != NetherPortalFrame.Role.INTERIOR && slot.position().y() < FRAME.y() + 4)
			sim.blocks.put(slot.position(), NetherPortalFrame.OBSIDIAN);
		var corners = new HashMap<ConstructionEscape.Position, String>();
		corners.put(FRAME.at(0, 0), NetherPortalFrame.OBSIDIAN);
		corners.put(FRAME.at(3, 0), NetherPortalFrame.OBSIDIAN);
		var result = run(program(sim, "minecraft:dirt", corners), sim);
		assertEquals("lit", result.get("portal").getAsString(), sim.tools.toString());
		assertEquals("minecraft:dirt", sim.at(FRAME.at(0, 4)));
		assertEquals(4, sim.tools.stream().filter("place_block"::equals).count(), sim.tools.toString());
	}

	@Test void clearsTheOpeningBeforeLighting() {
		var sim = new Sim();
		sim.blocks.put(FRAME.at(2, 2), "minecraft:dirt");
		var result = run(program(sim, "minecraft:cobblestone", Map.of()), sim);
		assertEquals("lit", result.get("portal").getAsString(), sim.tools.toString());
		assertTrue(sim.tools.contains("break_blocks"));
	}

	@Test void alreadyLitPortalSucceedsWithoutEffects() {
		var sim = new Sim();
		for (var slot : FRAME.slots()) sim.blocks.put(slot.position(), switch (slot.role()) {
			case INTERIOR -> "minecraft:nether_portal[axis=x]";
			default -> NetherPortalFrame.OBSIDIAN;
		});
		var result = run(program(sim, "minecraft:cobblestone", Map.of()), sim);
		assertEquals("already_lit", result.get("portal").getAsString());
		assertTrue(sim.tools.isEmpty(), sim.tools.toString());
	}

	@Test void frameThatWillNotLightFailsWithoutClaimingSuccess() {
		var sim = new Sim();
		sim.validFrameLights = false;
		var error = assertThrows(CompletionException.class, () -> run(program(sim, "minecraft:cobblestone", Map.of()), sim));
		assertTrue(error.getCause().getMessage().startsWith("portal_not_lit"), error.getCause().getMessage());
		assertTrue(error.getCause().getMessage().contains("frameCells=all_correct"), error.getCause().getMessage());
		assertEquals(NetherPortalProgram.MAX_IGNITIONS, sim.tools.stream().filter("use_block"::equals).count());
	}

	@Test void diagnosisNamesWrongFrameCells() {
		var sim = new Sim();
		sim.blocks.put(FRAME.at(1, 0), "minecraft:cobblestone");
		var program = new NetherPortalProgram(FRAME, new BlueprintConstructionProgram(FRAME.constructionCells("minecraft:dirt", Map.of()), sim), sim, "minecraft:flint_and_steel", true);
		String diagnosis = program.diagnosis();
		assertTrue(diagnosis.contains("1,64,0=minecraft:cobblestone(needs obsidian)"), diagnosis);
		assertTrue(diagnosis.contains("1,68,0=minecraft:air(needs obsidian)"), diagnosis);
	}

	@Test void stepsOutOfTheOpeningAfterLighting() {
		var sim = new Sim();
		sim.insideWhenLit = true;
		var result = run(program(sim, "minecraft:cobblestone", Map.of()), sim);
		assertEquals("lit", result.get("portal").getAsString());
		assertEquals("navigate_to", sim.tools.getLast());
	}

	@Test void failingToStepOutOfTheLitPortalFails() {
		var sim = new Sim();
		sim.insideWhenLit = true;
		sim.navigationFails = true;
		var error = assertThrows(CompletionException.class, () -> run(program(sim, "minecraft:cobblestone", Map.of()), sim));
		assertTrue(error.getCause().getMessage().startsWith("portal_exit_failed"), error.getCause().getMessage());
	}

	@Test void standingInsideWithNoOutsideStanceStillEnters() {
		var sim = new Sim();
		for (var slot : FRAME.slots()) sim.blocks.put(slot.position(), slot.role() == NetherPortalFrame.Role.INTERIOR
			? "minecraft:nether_portal[axis=x]" : NetherPortalFrame.OBSIDIAN);
		sim.playerInOpening = true;
		sim.noEntryStance = true;
		var result = run(entering(sim), sim);
		assertEquals("entered", result.get("portal").getAsString());
	}

	@Test void unlitBuildStopsAfterConstruction() {
		var sim = new Sim();
		var program = new NetherPortalProgram(FRAME, new BlueprintConstructionProgram(FRAME.constructionCells("minecraft:dirt", Map.of()), sim), sim, null, false);
		var result = run(program, sim);
		assertEquals("built_unlit", result.get("portal").getAsString());
		assertFalse(sim.tools.contains("use_block"));
	}

	private static NetherPortalProgram entering(Sim sim) {
		return new NetherPortalProgram(FRAME, new BlueprintConstructionProgram(FRAME.constructionCells("minecraft:dirt", Map.of()), sim), sim,
			"minecraft:flint_and_steel", true, true);
	}

	@Test void entersThePortalAndSucceedsOnTheDimensionChange() {
		var sim = new Sim();
		var program = entering(sim);
		JsonObject result = null;
		CompletableFuture<JsonElement> pending = program.resume(JsonNull.INSTANCE);
		for (int i = 0; i < 2000 && result == null; i++) {
			int ticks = 0;
			while (!pending.isDone() && ticks++ < 1000) program.tick();
			var step = pending.join().getAsJsonObject();
			if (step.get("done").getAsBoolean()) result = step.getAsJsonObject("value");
			else pending = program.resume(sim.apply(step));
		}
		assertNotNull(result, sim.tools.toString());
		assertEquals("entered", result.get("portal").getAsString());
		assertEquals("minecraft:overworld", result.get("fromDimension").getAsString());
		assertEquals("minecraft:the_nether", result.get("dimension").getAsString());
		assertTrue(result.get("leftArrivalPortal").getAsBoolean());
		assertTrue(sim.stopped);
		// The approach is a normal walk to the stance; the step into portal blocks is the program's own.
		assertEquals("navigate_to", sim.tools.getLast());
	}

	@Test void enteringAnAlreadyLitPortalBuildsNothing() {
		var sim = new Sim();
		for (var slot : FRAME.slots()) sim.blocks.put(slot.position(), slot.role() == NetherPortalFrame.Role.INTERIOR
			? "minecraft:nether_portal[axis=x]" : NetherPortalFrame.OBSIDIAN);
		var result = run(entering(sim), sim);
		assertEquals("entered", result.get("portal").getAsString());
		assertEquals(List.of("navigate_to"), sim.tools);
	}

	@Test void standingInTheArrivalPortalWalksOutAndBackWithoutNavigation() {
		var sim = new Sim();
		for (var slot : FRAME.slots()) sim.blocks.put(slot.position(), slot.role() == NetherPortalFrame.Role.INTERIOR
			? "minecraft:nether_portal[axis=x]" : NetherPortalFrame.OBSIDIAN);
		sim.playerInOpening = true;
		var result = run(entering(sim), sim);
		assertEquals("entered", result.get("portal").getAsString());
		assertTrue(sim.tools.isEmpty(), sim.tools.toString());
	}

	@Test void blockedWalkFailsInsteadOfWaitingForever() {
		var sim = new Sim();
		sim.walkTicksNeeded = Integer.MAX_VALUE;
		var error = assertThrows(CompletionException.class, () -> run(entering(sim), sim));
		assertTrue(error.getCause().getMessage().startsWith("portal_entry_blocked"), error.getCause().getMessage());
		assertTrue(sim.stopped);
	}

	@Test void portalThatNeverTeleportsFails() {
		var sim = new Sim();
		sim.teleportAfterInside = Integer.MAX_VALUE;
		var error = assertThrows(CompletionException.class, () -> run(entering(sim), sim));
		assertTrue(error.getCause().getMessage().startsWith("portal_did_not_teleport"), error.getCause().getMessage());
	}

	@Test void lightingRequiresAnIgniter() {
		var sim = new Sim();
		var error = assertThrows(IllegalArgumentException.class, () -> new NetherPortalProgram(FRAME,
			new BlueprintConstructionProgram(FRAME.constructionCells("minecraft:dirt", Map.of()), sim), sim, null, true));
		assertEquals("portal_igniter_missing", error.getMessage());
	}
}
