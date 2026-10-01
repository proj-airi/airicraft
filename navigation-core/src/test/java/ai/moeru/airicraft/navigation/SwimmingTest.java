package ai.moeru.airicraft.navigation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Swimming pose, flooded one-block tunnels, and planning around the air supply. */
class SwimmingTest {
	private static final CellInfo STONE = AsciiTerrain.STONE;
	private static final CellInfo WATER = CellInfo.WATER;
	private static final CellInfo AIR = CellInfo.AIR;
	private static final MovementPolicy WALK_ONLY = MovementPolicy.defaults().noEdits();

	/** A solid block of stone in which rooms and tunnels are carved. */
	private static final class Rock {
		private final MapTerrain terrain;

		Rock(int width, int height, int depth) {
			terrain = new MapTerrain(STONE, new Box(0, 0, 0, width - 1, height - 1, depth - 1));
		}

		Rock carve(int x1, int y1, int z1, int x2, int y2, int z2, CellInfo cell) {
			for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) terrain.set(x, y, z, cell);
			return this;
		}
	}

	private static SearchResult search(Rock rock, MovementPolicy policy, GridPos start, Goal goal) {
		return PathSearch.search(rock.terrain, policy, start, goal, SearchBudget.defaults().withHeuristicWeight(1.0), () -> false);
	}

	private static MovementPolicy withAir(int air) {
		return WALK_ONLY.withBreath(MovementPolicy.Breath.of(air, MovementPolicy.Breath.VANILLA_MAX));
	}

	/** Lake room on the left, a flooded tunnel {@code length} long and one block high, a chamber on the right. */
	private static Rock tunnelWorld(int length) {
		int end = 4 + length;
		return new Rock(end + 6, 8, 5)
			.carve(1, 1, 1, 3, 5, 3, WATER)
			.carve(4, 2, 2, end - 1, 2, 2, WATER)
			.carve(end, 1, 1, end + 3, 5, 3, WATER);
	}

	@Test
	void reachesAChamberThroughAFloodedOneBlockTunnel() {
		Rock rock = tunnelWorld(10);
		Path path = found(search(rock, WALK_ONLY, new GridPos(2, 2, 2), new Goal.Block(16, 2, 2)));

		assertTrue(path.steps().stream().anyMatch(step -> step.to().x() == 10 && step.to().y() == 2), "goes through the tunnel");
	}

	@Test
	void followerSwimsTheTunnelInTheSwimmingPose() {
		Rock rock = tunnelWorld(10);
		GridPos start = new GridPos(2, 2, 2);
		Goal goal = new Goal.Block(16, 2, 2);
		Path path = found(search(rock, WALK_ONLY, start, goal));

		SimBody body = new SimBody(rock.terrain, start);
		PathFollower follower = new PathFollower(path);
		boolean swam = false;
		int tick = 0;
		for (; tick < 1200; tick++) {
			BodyState state = body.state();
			if (goal.isGoal(state.feet()) && state.supported()) break;
			PathFollower.Tick next = follower.tick(state, rock.terrain, WALK_ONLY);
			assertFalse(next.status() == PathFollower.Status.REPLAN || next.status() == PathFollower.Status.FAILED,
				() -> "follower gave up: " + next.status() + " " + next.detail() + " at " + state);
			body.apply(next.intent());
			swam |= body.swimming;
			if (next.status() == PathFollower.Status.ARRIVED) break;
		}
		assertTrue(swam, "entered the swimming pose");
		assertTrue(goal.isGoal(body.state().feet()), () -> "ended at " + body.state());
		// 16 blocks of treading would take about 150 ticks; swimming should be well under that.
		assertTrue(tick < 130, "took " + tick + " ticks");
	}

	@Test
	void doesNotCrawlThroughDryOneBlockGaps() {
		Rock rock = new Rock(14, 6, 5).carve(1, 1, 1, 3, 3, 3, AIR).carve(4, 1, 2, 9, 1, 2, AIR).carve(10, 1, 1, 12, 3, 3, AIR);
		SearchResult result = search(rock, WALK_ONLY, new GridPos(2, 1, 2), new Goal.Block(11, 1, 2));

		assertFalse(result instanceof SearchResult.Found, "a dry one-block gap needs crawling, which is not planned");
	}

	@Test
	void anAirBudgetStopsAPlanThatWouldDrown() {
		Rock rock = tunnelWorld(40);
		GridPos start = new GridPos(2, 2, 2);
		Goal goal = new Goal.Block(46, 2, 2);

		SearchResult starved = search(rock, withAir(100), start, goal);
		assertFalse(starved instanceof SearchResult.Found, "forty blocks take about 220 ticks under water");
		SearchResult.Reason reason = starved instanceof SearchResult.Partial partial ? partial.reason()
			: ((SearchResult.Unreachable) starved).reason();
		assertEquals(SearchResult.Reason.AIR_BUDGET, reason);

		assertInstanceOf(SearchResult.Found.class, search(rock, withAir(300), start, goal));
		assertInstanceOf(SearchResult.Found.class, search(rock, WALK_ONLY, start, goal));
	}

	@Test
	void aPartialPathUnderAnAirBudgetNeverEndsUnderWater() {
		// A dry shelf, a pool, then a tunnel too long for the air: the approach stops at the pool's surface.
		Rock rock = tunnelWorld(60).carve(1, 4, 1, 3, 5, 3, AIR);
		SearchResult result = search(rock, withAir(300), new GridPos(2, 4, 2), new Goal.Block(66, 2, 2));

		if (result instanceof SearchResult.Partial partial) {
			GridPos end = partial.path().end();
			assertFalse(new Moves(rock.terrain, withAir(300)).headWet(end.x(), end.y(), end.z()), "ends dry-headed at " + end);
			assertEquals(SearchResult.Reason.AIR_BUDGET, partial.reason());
		}
		else {
			assertInstanceOf(SearchResult.Unreachable.class, result);
		}
	}

	@Test
	void anAirPocketOnTheWayMakesALongTunnelPossible() {
		// Air above the tunnel in the middle: a swimmer's head clears the water there and the supply refills.
		Rock rock = tunnelWorld(40).carve(22, 3, 2, 24, 3, 2, AIR);
		Path path = found(search(rock, withAir(150), new GridPos(2, 2, 2), new Goal.Block(46, 2, 2)));

		assertTrue(path.steps().stream().anyMatch(step -> step.to().x() >= 22 && step.to().x() <= 24 && step.to().y() == 2),
			"passes the pocket");
	}

	@Test
	void breathableFindsTheNearestAirNotJustUp() {
		// A flooded shaft capped with stone; the way to air is a side tunnel to a pocket.
		Rock rock = new Rock(24, 12, 5)
			.carve(2, 1, 2, 2, 9, 2, WATER)
			.carve(3, 3, 2, 12, 3, 2, WATER)
			.carve(13, 3, 2, 14, 3, 2, WATER)
			.carve(13, 4, 2, 14, 4, 2, AIR);
		GridPos start = new GridPos(2, 1, 2);
		Path path = found(search(rock, withAir(250), start, new Goal.Breathable(rock.terrain)));

		GridPos end = path.end();
		assertTrue(end.x() >= 13 && end.y() == 3, "ends in the pocket: " + end);
	}

	@Test
	void breathableIsTrivialWhenTheHeadIsAlreadyOut() {
		Rock rock = new Rock(8, 8, 5).carve(1, 1, 1, 6, 3, 3, WATER).carve(1, 4, 1, 6, 5, 3, AIR);
		Path path = found(search(rock, withAir(300), new GridPos(3, 3, 2), new Goal.Breathable(rock.terrain)));

		assertEquals(0, path.steps().size());
	}

	@Test
	void breathableWithNoAirReportsTheBudget() {
		// 40 blocks of flooded tunnel before any air.
		Rock rock = tunnelWorld(40).carve(46, 3, 2, 46, 5, 2, AIR);
		SearchResult result = search(rock, withAir(60), new GridPos(20, 2, 2), new Goal.Breathable(rock.terrain));

		assertFalse(result instanceof SearchResult.Found, "20 blocks away needs more than 60 ticks of air: " + result.outcome());
	}

	@Test
	void dryLandIsTheNearestShore() {
		Rock rock = new Rock(14, 8, 5).carve(1, 1, 1, 8, 4, 3, WATER).carve(1, 5, 1, 12, 6, 3, AIR)
			.carve(9, 1, 1, 12, 4, 3, AIR);
		Path path = found(search(rock, withAir(300), new GridPos(2, 4, 2), new Goal.DryLand(rock.terrain, WALK_ONLY)));

		assertTrue(path.end().x() >= 8, "swims to the shore: " + path.end());
	}

	private static Path found(SearchResult result) {
		assertInstanceOf(SearchResult.Found.class, result, () -> "got " + result.outcome()
			+ (result instanceof SearchResult.Partial p ? " " + p.reason() : result instanceof SearchResult.Unreachable u ? " " + u.reason() : ""));
		return ((SearchResult.Found) result).path();
	}

	@SuppressWarnings("unused")
	private static List<String> types(Path path) {
		return path.steps().stream().map(step -> step.type().name()).toList();
	}
}
