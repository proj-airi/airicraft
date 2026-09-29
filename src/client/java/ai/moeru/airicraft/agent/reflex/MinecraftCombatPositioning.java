package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.agent.navigation.LiveWorldTerrain;
import ai.moeru.airicraft.agent.navigation.MinecraftCellClassifier;
import ai.moeru.airicraft.agent.spatial.WorldTravelPolicy;
import ai.moeru.airicraft.navigation.BodyState;
import ai.moeru.airicraft.navigation.Box;
import ai.moeru.airicraft.navigation.CellInfo;
import ai.moeru.airicraft.navigation.GridPos;
import ai.moeru.airicraft.navigation.MovementPolicy;
import ai.moeru.airicraft.navigation.Moves;
import ai.moeru.airicraft.navigation.Step;
import ai.moeru.airicraft.navigation.TerrainView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-thread terrain observation over navigation-core's walking moves under a no-edits policy;
 * no terrain edits, global settings changes, or pathfinding effects.
 */
final class MinecraftCombatPositioning {
	static final int MAX_CELLS = 128;
	static final int RADIUS = 6;
	private static final int REPLAN_TICKS = 6;
	/** Core move indices: the four traverses, ascends and descends (descends include short drops). */
	private static final int[] MOVES = {0, 1, 2, 3, 8, 9, 10, 11, 12, 13, 14, 15};
	private final MinecraftCellClassifier classifier = MinecraftCellClassifier.forPlayer(null);
	private record Observation(Vec3 position, long tick) { }
	private final Map<String, Observation> previous = new HashMap<>();
	private CombatPositioning.Cell anchor;
	private String plannedFocus;
	private Vec3 plannedFocusVelocity = Vec3.ZERO;
	private boolean plannedAttackReady;
	private boolean plannedShielding;
	private long plannedTick = Long.MIN_VALUE;
	private CombatPositioning.Decision decision;
	private int terrainCells;
	private List<CombatPositioning.Splash> incomingSplashes = List.of();
	private long planningNanos;
	private CombatTraversal traversal;
	private Vec3 preciseWaypoint;
	private double desiredDistance = 2.6;
	private List<CombatPositioning.Threat> liveThreats;
	private List<CombatPositioning.Threat> plannedThreats;
	private boolean witchSprinting;

	CombatPositioning.Decision plan(Minecraft minecraft, List<LivingEntity> entities, LivingEntity focus, long tick, boolean shielding, double fightingDistance) {
		if (desiredDistance != fightingDistance) invalidate();
		desiredDistance = fightingDistance;
		List<CombatPositioning.Threat> threats = new ArrayList<>();
		CombatPositioning.Threat focusThreat = null;
		for (LivingEntity entity : entities) {
			var attribute = entity.getAttribute(Attributes.MOVEMENT_SPEED);
			double speed = Math.max(entity.getDeltaMovement().horizontalDistance(), attribute == null ? .1 : attribute.getValue() * 1.3);
			Observation old = previous.put(entity.getStringUUID(), new Observation(entity.position(), tick));
			if (old != null && tick > old.tick()) speed = Math.max(speed,
				entity.position().subtract(old.position()).horizontalDistance() / (tick - old.tick()));
			threats.add(new CombatPositioning.Threat(entity.getX(), entity.getY(), entity.getZ(), speed,
				2.4 + Math.max(0, (entity.getBbWidth() - .6) / 2), SurvivalReflexRuntime.isRangedThreat(entity), entity.getDeltaMovement().x, entity.getDeltaMovement().z));
			if (entity == focus) {
				var t = threats.getLast();
				focusThreat = new CombatPositioning.Threat(t.x(), t.y(), t.z(), t.blocksPerTick(), t.reach(), t.ranged(), t.velocityX(), t.velocityZ(), desiredDistance, (entities.size() > 1 || t.ranged()) && !shielding && desiredDistance <= 3);
			}
		}
		liveThreats = List.copyOf(threats);
		previous.keySet().retainAll(entities.stream().map(LivingEntity::getStringUUID).toList());
		if (traversal != null && traversal.destination() != null && decision != null) return decision;
		CombatPositioning.Cell origin = cell(feet(minecraft.player));
		if (anchor == null) anchor = origin;
		boolean attackReady = !shielding && minecraft.player.getAttackStrengthScale(0) >= .92F;
		incomingSplashes = predictSplashes(minecraft);
		if (incomingSplashes.isEmpty() && plannedThreats != null && !CombatPositioning.crowdMoved(plannedThreats, liveThreats) && decision != null && focus.getStringUUID().equals(plannedFocus) && attackReady == plannedAttackReady && shielding == plannedShielding && tick - plannedTick < REPLAN_TICKS
			&& focus.getDeltaMovement().subtract(plannedFocusVelocity).horizontalDistanceSqr() < .01
			&& !origin.equals(decision.nextStep())) return decision;
		long started = System.nanoTime();
		Moves moves = moves(minecraft);

		// Facing the pack uses walking/backpedaling, never a forward sprint away from it.
		double slowdown = (moves.policy().allowSprint() ? 1.3 : 1) * (shielding ? 5 : 1);
		Map<CombatPositioning.Cell, List<CombatPositioning.Edge>> graph = terrain(moves, origin, slowdown);
		terrainCells = graph.size();
		decision = CombatPositioning.choose(origin, graph, threats, decision == null ? null : decision.nextStep(), anchor, attackReady, focusThreat, incomingSplashes);
		plannedThreats = liveThreats;
		plannedTick = tick;
		plannedFocus = focus.getStringUUID();
		plannedFocusVelocity = focus.getDeltaMovement();
		plannedAttackReady = attackReady;
		plannedShielding = shielding;
		planningNanos = System.nanoTime() - started;
		return decision;
	}

	private static List<CombatPositioning.Splash> predictSplashes(Minecraft minecraft) {
		var result = new ArrayList<CombatPositioning.Splash>();
		for (var potion : minecraft.level.getEntitiesOfClass(net.minecraft.world.entity.projectile.AbstractThrownPotion.class,
			minecraft.player.getBoundingBox().inflate(16), e -> e.isAlive() && e.getOwner() != minecraft.player)) {
			Vec3 position = potion.position(), velocity = potion.getDeltaMovement();
			for (int tick = 1; tick <= 24; tick++) {
				// Vanilla 1.21.8 applies gravity and drag before moving the thrown entity.
				if (!potion.isNoGravity()) velocity = velocity.add(0, -.05, 0);
				boolean water = minecraft.level.getFluidState(BlockPos.containing(position)).is(net.minecraft.tags.FluidTags.WATER);
				velocity = velocity.scale(water ? .8F : .99F);
				Vec3 next = position.add(velocity);
				var hit = minecraft.level.clip(new net.minecraft.world.level.ClipContext(position, next,
					net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, potion));
				if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
					result.add(new CombatPositioning.Splash(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z, tick));
					break;
				}
				var contact = minecraft.player.getBoundingBox().inflate(.3).clip(position, next);
				if (contact.isPresent()) {
					Vec3 point = contact.get();
					result.add(new CombatPositioning.Splash(point.x, point.y, point.z, tick));
					break;
				}
				position = next;
			}
		}
		return List.copyOf(result);
	}

	Map<String, Object> evidence() {
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("decision", decision);
		evidence.put("traversal", traversal == null ? Map.of("phase", "idle") : traversal.evidence());
		evidence.put("preciseWaypoint", preciseWaypoint == null ? null : new double[]{preciseWaypoint.x, preciseWaypoint.y, preciseWaypoint.z});
		evidence.put("incomingSplashes", incomingSplashes);
		evidence.put("anchor", anchor);
		evidence.put("focusUuid", plannedFocus);
		evidence.put("attackReady", plannedAttackReady);
		evidence.put("desiredDistance", desiredDistance);
		evidence.put("crowd", liveThreats);
		evidence.put("witchSprinting", witchSprinting);
		evidence.put("terrainCells", terrainCells);
		evidence.put("planningMicros", planningNanos / 1000);
		evidence.put("plannedTick", plannedTick);
		evidence.put("threatCount", previous.size());
		return evidence;
	}

	/** Fresh validation prevents a cached escape hop surviving a block change or knockback. */
	boolean canStepTo(CombatPositioning.Cell target) {
		if (traversal != null && target.equals(traversal.destination())) return true;
		Minecraft minecraft = Minecraft.getInstance();
		var origin = cell(feet(minecraft.player));
		if (origin.equals(target)) return true;
		return edges(moves(minecraft), origin, 1).stream().anyMatch(edge -> edge.destination().equals(target));
	}

	void invalidate() { plannedTick = Long.MIN_VALUE; decision = null; }

	/** Quantized keys must not cut a corner or step sideways off the validated route. */
	CombatPositioning.Steering steering(Minecraft minecraft, CombatPositioning.Cell target, Vec3 waypoint, Vec3 facing) {
		var player = minecraft.player;
		Vec3 forward = new Vec3(facing.x - player.getX(), 0, facing.z - player.getZ()).normalize();
		Vec3 left = new Vec3(forward.z, 0, -forward.x);
		if (player.position().subtract(waypoint).horizontalDistanceSqr() < .0064) return new CombatPositioning.Steering(false, false, false, false);
		Vec3 desired = new Vec3(waypoint.x - player.getX(), 0, waypoint.z - player.getZ()).normalize();
		var preferred = CombatPositioning.steering(player.getX(), player.getZ(), waypoint.x, waypoint.z, facing.x, facing.z);
		CombatPositioning.Steering best = new CombatPositioning.Steering(false, false, false, false);
		double bestProgress = 0;
		for (int f = -1; f <= 1; f++) for (int l = -1; l <= 1; l++) {
			if (f == 0 && l == 0) continue;
			Vec3 motion = forward.scale(f).add(left.scale(l)).normalize();
			var keys = new CombatPositioning.Steering(f > 0, f < 0, l > 0, l < 0);
			double progress = motion.dot(desired) + (keys.equals(preferred) ? .001 : 0);
			if (progress <= bestProgress) continue;
			Vec3 probe = player.position().add(motion.scale(.35));
			BlockPos cell = BlockPos.containing(probe);
			if ((cell.getX() != player.getBlockX() || cell.getZ() != player.getBlockZ())
				&& (cell.getX() != target.x() || cell.getZ() != target.z())) continue;
			double rise = Math.max(0, target.y() - player.getY());
			if (!minecraft.level.noCollision(player, player.getBoundingBox().move(motion.x * .35, rise, motion.z * .35))) continue;
			best = keys;
			bestProgress = progress;
		}
		return best;
	}

	CombatTraversal.Control control(Minecraft minecraft, CombatPositioning.Cell target, Vec3 facing, Vec3 focus, long tick) {
		witchSprinting = false;
		Moves moves = moves(minecraft);
		if (traversal == null) traversal = new CombatTraversal();
		if (traversal.destination() == null && target.y() != minecraft.player.getBlockY() && minecraft.player.onGround()) {
			GridPos source = feet(minecraft.player);
			for (int move : MOVES) {
				Step step = moves.step(move, source);
				if (step != null && step.to().x() == target.x() && step.to().y() == target.y() && step.to().z() == target.z()
					&& !step.edits() && step.doors().isEmpty()) {
					traversal.start(step, target, tick);
					break;
				}
			}
		}
		if (traversal.destination() != null) {
			preciseWaypoint = null;
			var control = traversal.tick(body(minecraft.player), moves.terrain(), moves.policy(), facing.x, facing.z, tick);
			if (traversal.destination() == null) invalidate();
			return control;
		}
		preciseWaypoint = preciseWaypoint(minecraft, moves.terrain(), target, focus);
		return new CombatTraversal.Control(steering(minecraft, target, preciseWaypoint, facing), false, false);
	}

	record SprintStrafe(Vec3 facing, CombatPositioning.Steering steering) { }

	SprintStrafe witchSprint(Minecraft minecraft, CombatPositioning.Cell target, Vec3 focus) {
		var player = minecraft.player;
		if (!player.onGround() || target.y() != player.getBlockY() || preciseWaypoint == null
			|| traversal != null && traversal.destination() != null || player.getFoodData().getFoodLevel() <= 6) return null;
		double distance = player.position().distanceTo(focus);
		if (distance < 2 || distance > 4) return null;
		Vec3 direction = preciseWaypoint.subtract(player.position()).multiply(1, 0, 1).normalize();
		Vec3 radial = focus.subtract(player.position()).multiply(1, 0, 1).normalize();
		if (direction.lengthSqr() < .5 || Math.abs(direction.dot(radial)) > .65) return null;
		TerrainView terrain = new LiveWorldTerrain(minecraft.level, classifier);
		// Sweep three sprint ticks, including support and nearby melee clearance.
		for (double travel : new double[]{.3, .6, .9}) {
			Vec3 point = player.position().add(direction.scale(travel));
			BlockPos cell = BlockPos.containing(point);
			if ((cell.getX() != player.getBlockX() || cell.getZ() != player.getBlockZ())
				&& (cell.getX() != target.x() || cell.getZ() != target.z())) return null;
			if (!dryAndSafe(terrain, cell.getX(), cell.getY(), cell.getZ())
				|| !minecraft.level.noCollision(player, player.getBoundingBox().move(point.subtract(player.position())))
				|| CombatPositioning.crowdClearancePenalty(point.x, point.z, liveThreats) > 0
				|| point.distanceTo(focus) < 2) return null;
		}
		Vec3 left = new Vec3(direction.z, 0, -direction.x);
		double side = left.dot(radial) >= 0 ? 1 : -1;
		Vec3 facing = player.position().add(direction.add(left.scale(side)).normalize().scale(8));
		var keys = steering(minecraft, target, preciseWaypoint, facing);
		if (!keys.forward() || keys.back() || keys.left() == keys.right()) return null;
		witchSprinting = true;
		return new SprintStrafe(facing, keys);
	}

	private Vec3 preciseWaypoint(Minecraft minecraft, TerrainView terrain, CombatPositioning.Cell target, Vec3 focus) {
		var player = minecraft.player;
		Vec3 best = player.position();
		double bestScore = Double.POSITIVE_INFINITY;
		for (double ox : new double[]{.32, .5, .68}) for (double oz : new double[]{.32, .5, .68}) {
			Vec3 point = new Vec3(target.x() + ox, target.y(), target.z() + oz);
			if (!dryAndSafe(terrain, target.x(), target.y(), target.z())) continue;
			if (!minecraft.level.noCollision(player, player.getBoundingBox().move(point.subtract(player.position())))) continue;
			double score = CombatPositioning.preciseScore(point.x, point.y, point.z, focus.x, focus.y, focus.z, incomingSplashes, desiredDistance, liveThreats);
			score += point.distanceTo(player.position()) * .1;
			if (score < bestScore) { best = point; bestScore = score; }
		}
		return best;
	}

	private static Map<CombatPositioning.Cell, List<CombatPositioning.Edge>> terrain(
		Moves moves, CombatPositioning.Cell origin, double slowdown) {
		var graph = new LinkedHashMap<CombatPositioning.Cell, List<CombatPositioning.Edge>>();
		var pending = new ArrayDeque<CombatPositioning.Cell>();
		graph.put(origin, List.of());
		pending.add(origin);
		while (!pending.isEmpty()) {
			var from = pending.removeFirst();
			var accepted = new ArrayList<CombatPositioning.Edge>();
			for (var edge : edges(moves, from, slowdown)) {
				var to = edge.destination();
				if (Math.abs(to.x() - origin.x()) > RADIUS || Math.abs(to.z() - origin.z()) > RADIUS
					|| Math.abs(to.y() - origin.y()) > 3) continue;
				if (!graph.containsKey(to)) {
					if (graph.size() >= MAX_CELLS) continue;
					graph.put(to, List.of());
					pending.add(to);
				}
				accepted.add(edge);
			}
			graph.put(from, List.copyOf(accepted));
		}
		return Map.copyOf(graph);
	}

	private static List<CombatPositioning.Edge> edges(Moves moves, CombatPositioning.Cell from, double slowdown) {
		var edges = new ArrayList<CombatPositioning.Edge>();
		int kind = moves.probe(from.x(), from.y(), from.z());
		double base = moves.probeBase();
		if (kind == Moves.KIND_INVALID) {
			// Knocked back or mid-jump: step as if standing on the cell.
			kind = Moves.KIND_STAND;
			base = from.y();
		}
		var result = new Moves.Result();
		for (int move : MOVES) {
			// A no-edits policy never breaks, places or opens a door; unloaded cells are impassable.
			if (!moves.evaluate(move, from.x(), from.y(), from.z(), kind, base, result)) continue;
			if (result.cost <= 0 || result.cost > CombatPositioning.HORIZON_TICKS
				|| result.y - from.y() > 1 || from.y() - result.y > 3) continue;
			if (!dryAndSafe(moves.terrain(), result.x, result.y, result.z)) continue;
			edges.add(new CombatPositioning.Edge(new CombatPositioning.Cell(result.x, result.y, result.z), result.cost * slowdown));
		}
		return List.copyOf(edges);
	}

	static boolean dryAndSafe(TerrainView terrain, int x, int y, int z) {
		for (int dy = -1; dy <= 1; dy++) {
			CellInfo cell = terrain.cell(x, y + dy, z);
			if (!cell.loaded() || cell.fluid() != CellInfo.Fluid.NONE || cell.hazards() != 0) return false;
			// Direct steering cannot open a door as the full movement executor can.
			if (dy >= 0 && cell.hasCollision()) return false;
		}
		return true;
	}

	/** Walking moves over the live world; travel bounds apply, terrain edits never do. */
	private Moves moves(Minecraft minecraft) {
		MovementPolicy policy = MovementPolicy.defaults().noEdits()
			.withSprint(minecraft.player.getFoodData().getFoodLevel() > 6);
		WorldTravelPolicy.Limit limit = WorldTravelPolicy.limit(minecraft.level);
		TerrainView terrain = new LiveWorldTerrain(minecraft.level, classifier);
		if (limit.closed()) terrain = (x, y, z) -> CellInfo.UNLOADED;
		else if (limit.bounds() != null) {
			var bounds = limit.bounds();
			policy = policy.withTravelBounds(new Box(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ()));
		}
		return new Moves(terrain, policy);
	}

	private static GridPos feet(LocalPlayer player) {
		return body(player).feet();
	}

	private static BodyState body(LocalPlayer player) {
		return new BodyState(player.getX(), player.getY(), player.getZ(), player.getDeltaMovement().y, player.onGround(),
			player.isInWater(), player.onClimbable(), player.horizontalCollision, 0);
	}

	private static CombatPositioning.Cell cell(GridPos pos) { return new CombatPositioning.Cell(pos.x(), pos.y(), pos.z()); }

}
