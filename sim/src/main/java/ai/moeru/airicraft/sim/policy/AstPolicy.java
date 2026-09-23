package ai.moeru.airicraft.sim.policy;

import ai.moeru.airicraft.sim.input.Intent;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Interpreted rule-list policy for structural search (GP). The optimizer ships
 * a JSON AST via `params` in POST /v1/episode; this class evaluates it once per
 * tick against the privileged observation.
 *
 * <pre>{@code
 * {"rules":[
 *   {"when": {"op":"ge","f":"mobCount","r":3.5,"v":3},
 *    "act":  {"mode":"flee","target":"centroid","sprint":true,"attack":"ready"}},
 *   {"act":  {"mode":"approach","target":"nearest","range":2.5,"slack":0.4,
 *             "sprintBeyond":4.0,"attack":"ready","attackRange":3.0}}
 * ]}}
 * </pre>
 *
 * Conditions: lt/le/gt/ge/eq on numeric features, and/or/not nesting, plus
 * {"op":"is","f":"nearestType","v":"skeleton"} substring type checks.
 * Count features take an optional radius arg "r".
 * Actions resolve a target entity ("nearest"|"lowestHp"|"ranged"|"melee"|
 * "farthest"|"centroid") and a movement mode ("approach"|"flee"|"orbit"|
 * "kite"|"hold") plus an attack rule ("ready"|"always"|"never").
 */
public final class AstPolicy implements CombatPolicy {
	private JsonArray rules = new JsonArray();
	private int tick;
	private int strafeSign = 1;

	private static boolean isRanged(String type) {
		return type.contains("skeleton") || type.contains("stray")
				|| type.contains("pillager") || type.contains("witch")
				|| type.contains("blaze") || type.contains("breeze");
	}

	@Override
	public String id() {
		return "ast";
	}

	@Override
	public void reset() {
		tick = 0;
		strafeSign = 1;
	}

	@Override
	public void configure(JsonObject params) {
		JsonElement ast = params.get("ast");
		if (ast != null && ast.isJsonObject()) {
			JsonElement rs = ast.getAsJsonObject().get("rules");
			if (rs != null && rs.isJsonArray()) {
				rules = rs.getAsJsonArray();
			}
		} else if (ast != null && ast.isJsonArray()) {
			rules = ast.getAsJsonArray();
		}
	}

	// ------------------------------------------------------------ features

	private static final class View {
		List<JsonObject> hostiles = new ArrayList<>();
		JsonObject player;
		JsonObject nearest;
		JsonObject lowestHp;
		JsonObject ranged;   // nearest ranged hostile
		JsonObject melee;    // nearest melee hostile
		JsonObject farthest;
		double cx, cz;       // hostile centroid

		static View of(JsonObject obs) {
			View v = new View();
			v.player = obs.getAsJsonObject("player");
			JsonArray entities = obs.getAsJsonArray("entities");
			double nD = Double.MAX_VALUE, hD = Double.MAX_VALUE;
			double rD = Double.MAX_VALUE, mD = Double.MAX_VALUE, fD = -1;
			for (JsonElement el : entities) {
				JsonObject e = el.getAsJsonObject();
				boolean hostile = e.has("hostile") && e.get("hostile").getAsBoolean();
				boolean targeting = e.has("targetingPlayer") && e.get("targetingPlayer").getAsBoolean();
				if (!e.has("health") || (!hostile && !targeting)) {
					continue;
				}
				v.hostiles.add(e);
				double d = e.get("dist").getAsDouble();
				v.cx += e.getAsJsonObject("pos").get("x").getAsDouble();
				v.cz += e.getAsJsonObject("pos").get("z").getAsDouble();
				if (d < nD) { nD = d; v.nearest = e; }
				if (d > fD) { fD = d; v.farthest = e; }
				if (e.get("health").getAsDouble() < hD) { hD = e.get("health").getAsDouble(); v.lowestHp = e; }
				if (isRanged(e.get("type").getAsString())) {
					if (d < rD) { rD = d; v.ranged = e; }
				} else if (d < mD) { mD = d; v.melee = e; }
			}
			if (!v.hostiles.isEmpty()) {
				v.cx /= v.hostiles.size();
				v.cz /= v.hostiles.size();
			}
			return v;
		}

		static double dist(JsonObject e) {
			return e == null ? Double.MAX_VALUE : e.get("dist").getAsDouble();
		}

		double feature(JsonObject c) {
			String f = c.get("f").getAsString();
			double r = c.has("r") ? c.get("r").getAsDouble() : 3.5;
			return switch (f) {
				case "nearestDist" -> dist(nearest);
				case "rangedDist" -> dist(ranged);
				case "meleeDist" -> dist(melee);
				case "farthestDist" -> dist(farthest);
				case "nearestHp" -> nearest == null ? 0 : nearest.get("health").getAsDouble();
				case "lowestHp" -> lowestHp == null ? 0 : lowestHp.get("health").getAsDouble();
				case "mobCount" -> count(r, null);
				case "meleeCount" -> count(r, false);
				case "rangedCount" -> count(r, true);
				case "selfHp" -> player.get("health").getAsDouble();
				case "cooldown" -> player.get("lastAttackedTicks").getAsInt();
				case "cdFrac" -> player.get("attackCooldown").getAsDouble();
				case "food" -> player.get("food").getAsInt();
				default -> 0;
			};
		}

		double count(double r, Boolean rangedOnly) {
			double n = 0;
			for (JsonObject e : hostiles) {
				if (e.get("dist").getAsDouble() >= r) continue;
				if (rangedOnly == null
						|| rangedOnly == isRanged(e.get("type").getAsString())) {
					n++;
				}
			}
			return n;
		}
	}

	private boolean evalCond(JsonElement el, View v) {
		if (el == null || !el.isJsonObject()) return true;
		JsonObject c = el.getAsJsonObject();
		String op = c.get("op").getAsString();
		return switch (op) {
			case "and" -> {
				for (JsonElement a : c.getAsJsonArray("args")) {
					if (!evalCond(a, v)) yield false;
				}
				yield true;
			}
			case "or" -> {
				for (JsonElement a : c.getAsJsonArray("args")) {
					if (evalCond(a, v)) yield true;
				}
				yield false;
			}
			case "not" -> !evalCond(c.get("arg"), v);
			case "is" -> {
				String which = c.get("f").getAsString();
				JsonObject t = switch (which) {
					case "nearestType" -> v.nearest;
					case "rangedType" -> v.ranged;
					case "meleeType" -> v.melee;
					case "lowestHpType" -> v.lowestHp;
					default -> null;
				};
				yield t != null && t.get("type").getAsString()
						.contains(c.get("v").getAsString());
			}
			case "true" -> true;
			case "false" -> false;
			default -> {
				double fv = v.feature(c);
				double tv = c.has("v") ? c.get("v").getAsDouble() : 0;
				yield switch (op) {
					case "lt" -> fv < tv;
					case "le" -> fv <= tv;
					case "gt" -> fv > tv;
					case "ge" -> fv >= tv;
					case "eq" -> fv == tv;
					case "ne" -> fv != tv;
					default -> false;
				};
			}
		};
	}

	// ------------------------------------------------------------- actions

	private static JsonObject pick(View v, String target) {
		return switch (target) {
			case "lowestHp" -> v.lowestHp;
			case "ranged" -> v.ranged != null ? v.ranged : v.nearest;
			case "melee" -> v.melee != null ? v.melee : v.nearest;
			case "farthest" -> v.farthest;
			default -> v.nearest;
		};
	}

	private static double num(JsonObject a, String k, double d) {
		return a.has(k) && a.get(k).isJsonPrimitive() ? a.get(k).getAsDouble() : d;
	}

	@Override
	public Intent decide(JsonObject obs) {
		tick++;
		View v = View.of(obs);
		if (v.nearest == null) return Intent.IDLE;

		JsonObject act = null;
		for (JsonElement rel : rules) {
			if (!rel.isJsonObject()) continue;
			JsonObject rule = rel.getAsJsonObject();
			if (!rule.has("when") || evalCond(rule.get("when"), v)) {
				act = rule.has("act") && rule.get("act").isJsonObject()
						? rule.getAsJsonObject("act") : null;
				break;
			}
		}
		if (act == null) return Intent.IDLE;

		int flipTicks = (int) num(act, "flipTicks", 30);
		if (flipTicks > 0 && tick % flipTicks == 0) strafeSign = -strafeSign;
		if (act.has("strafe")) strafeSign = (int) num(act, "strafe", 1) >= 0 ? 1 : -1;

		String target = act.has("target") ? act.get("target").getAsString() : "nearest";
		JsonObject t = pick(v, target);
		double px = v.player.getAsJsonObject("pos").get("x").getAsDouble();
		double pz = v.player.getAsJsonObject("pos").get("z").getAsDouble();

		double tx, tz, tdist;
		Integer lookId = null;
		if ("centroid".equals(target)) {
			tx = v.cx; tz = v.cz;
			tdist = Math.hypot(tx - px, tz - pz);
		} else {
			if (t == null) t = v.nearest;
			tx = t.getAsJsonObject("pos").get("x").getAsDouble();
			tz = t.getAsJsonObject("pos").get("z").getAsDouble();
			tdist = t.get("dist").getAsDouble();
			lookId = t.get("id").getAsInt();
		}
		if (lookId == null && v.nearest != null) lookId = v.nearest.get("id").getAsInt();

		double dx = tx - px, dz = tz - pz;
		double len = Math.hypot(dx, dz);
		if (len > 1e-6) { dx /= len; dz /= len; }

		double range = num(act, "range", 2.5);
		double slack = num(act, "slack", 0.4);
		String mode = act.has("mode") ? act.get("mode").getAsString() : "approach";

		Intent.Builder b = Intent.builder();
		if (lookId != null) b.lookEntity(lookId);

		switch (mode) {
			case "flee" -> {
				b.moveDir(-dx, -dz);
				b.sprint(act.has("sprint") ? act.get("sprint").getAsBoolean() : true);
			}
			case "orbit" -> {
				if (len > 1e-6) b.moveDir(-dz * strafeSign, dx * strafeSign);
			}
			case "kite" -> {
				if (tdist < range - slack) {
					b.moveDir(-dx, -dz);
					b.sprint(true);
				} else if (tdist > range + slack) {
					// drift toward tangential orbit while gently closing in
					b.moveDir(-dz * strafeSign * 0.7 + dx * 0.4,
							dx * strafeSign * 0.7 + dz * 0.4);
				} else {
					b.moveDir(-dz * strafeSign, dx * strafeSign);
				}
			}
			case "hold" -> { /* no move */ }
			default -> { // approach
				if (tdist > range + slack) {
					b.moveDir(dx, dz);
					double sprintBeyond = num(act, "sprintBeyond", 4.0);
					b.sprint(act.has("sprint") ? act.get("sprint").getAsBoolean()
							: tdist > sprintBeyond);
				} else if (tdist < range - slack) {
					b.moveDir(-dz * strafeSign - dx * 0.4, dx * strafeSign - dz * 0.4);
				} else {
					b.moveDir(-dz * strafeSign, dx * strafeSign);
				}
			}
		}

		String attack = act.has("attack") ? act.get("attack").getAsString() : "ready";
		double attackRange = num(act, "attackRange", 3.0);
		int readyTicks = (int) num(act, "readyTicks", 10);
		boolean inRange = tdist <= attackRange;
		boolean ready = v.player.get("lastAttackedTicks").getAsInt() >= readyTicks;
		b.attack(switch (attack) {
			case "always" -> inRange;
			case "never" -> false;
			default -> inRange && ready;
		});
		return b.build();
	}
}
