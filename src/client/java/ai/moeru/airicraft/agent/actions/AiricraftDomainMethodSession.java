package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.modded.ItemGroups;
import ai.moeru.airicraft.agent.tasks.ResourceGatheringCatalog;
import ai.moeru.airicraft.agent.tasks.SmeltingFuelCost;
import ai.moeru.actionplan.ResolutionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class AiricraftDomainMethodSession {
	private static final int DEFAULT_SMELT_COOK_TICKS = 200;
	private static final int FUEL_TICKS_PLANKS = 300;
	private static final int FUEL_TICKS_LOGS = 300;
	private static final int FUEL_TICKS_STICKS = 100;
	private static final int FUEL_TICKS_COAL = 1600;
	private static final Set<ActionFactProvenance> GUARD_USABLE_PROVENANCE = Set.of(
		ActionFactProvenance.OBSERVED,
		ActionFactProvenance.EXECUTOR_REPORTED,
		ActionFactProvenance.INFERRED
	);

	private final ActionFactStore facts;
	private final BlockAcquisitionIndex blockAcquisitions;
	private final NearbyBlockAvailability nearbyBlockAvailability;
	private final ActionResolverContext context;
	private final Map<String, List<ActionFact>> craftRecipesByOutput;
	private final Map<String, List<ActionFact>> smeltRecipesByOutput;
	private final ResolutionContext advisoryContext;

	static ActionResolveResult resolveResourceProvider(
		AiricraftPlanningSnapshot snapshot,
		ActionGoal goal,
		ResolutionContext advisoryContext
	) {
		AiricraftDomainMethodSession session = providerSession(snapshot, advisoryContext);
		return session.resolveWithProvider(goal, session::resolveResourceProviderGoal);
	}

	static ActionResolveResult resolveRecipeProvider(
		AiricraftPlanningSnapshot snapshot,
		ActionGoal goal,
		ResolutionContext advisoryContext
	) {
		AiricraftDomainMethodSession session = providerSession(snapshot, advisoryContext);
		return session.resolveWithProvider(goal, session::resolveRecipeProviderGoal);
	}

	static ActionResolveResult resolveSmeltingProvider(
		AiricraftPlanningSnapshot snapshot,
		ActionGoal goal,
		ResolutionContext advisoryContext
	) {
		AiricraftDomainMethodSession session = providerSession(snapshot, advisoryContext);
		return session.resolveWithProvider(goal, session::resolveSmeltingProviderGoal);
	}

	static ActionResolveResult resolveMiningProvider(
		AiricraftPlanningSnapshot snapshot,
		ActionGoal goal,
		ResolutionContext advisoryContext
	) {
		AiricraftDomainMethodSession session = providerSession(snapshot, advisoryContext);
		return session.resolveWithProvider(goal, session::resolveMiningProviderGoal);
	}

	private static AiricraftDomainMethodSession providerSession(
		AiricraftPlanningSnapshot snapshot,
		ResolutionContext advisoryContext
	) {
		return new AiricraftDomainMethodSession(snapshot, advisoryContext);
	}

	private ActionResolveResult resolveWithProvider(ActionGoal goal, TopLevelProviderResolver provider) {
		ArrayList<ActionTraceEvent> trace = new ArrayList<>();
		if (goalSatisfied(goal, trace)) {
			return ActionResolveResult.success(ActionRoute.empty(), trace);
		}
		Optional<ProviderCandidate> candidate = provider.resolve(goal, trace);
		if (candidate.isPresent()) {
			return ActionResolveResult.success(candidate.get().route(), trace);
		}
		return ActionResolveResult.failure("no_route", "provider has no route", trace);
	}

	private AiricraftDomainMethodSession(
		AiricraftPlanningSnapshot snapshot,
		ResolutionContext advisoryContext
	) {
		Objects.requireNonNull(snapshot, "snapshot");
		this.facts = new ActionFactStore(snapshot.facts());
		this.blockAcquisitions = snapshot.blockAcquisitions();
		this.nearbyBlockAvailability = snapshot.nearbyBlockAvailability();
		this.context = snapshot.context();
		this.advisoryContext = Objects.requireNonNull(advisoryContext, "advisoryContext");
		this.craftRecipesByOutput = providerFactsByOutput(ActionFactType.CRAFT_RECIPE, "outputItemId", "recipeId");
		this.smeltRecipesByOutput = providerFactsByOutput(ActionFactType.SMELT_RECIPE, "outputItemId", "optionId");
	}

	private Optional<ActionRoute> resolveGoal(ActionGoal goal) {
		return advisoryContext.resolve(AiricraftPlanConversions.toGoal(goal))
			.map(route -> AiricraftPlanConversions.toActionRoute(route, context));
	}

	private Optional<ProviderCandidate> resolveResourceProviderGoal(
		ActionGoal goal,
		List<ActionTraceEvent> trace
	) {
		if (goal.factType() != ActionFactType.INVENTORY_RESOURCE) {
			return Optional.empty();
		}
		String resourceKind = goal.keys().getOrDefault("resourceKind", "");
		Optional<ResourceGatheringCatalog.ResourceEntry> entry = ResourceGatheringCatalog.entry(resourceKind);
		if (entry.isEmpty()) {
			trace.add(event(
				"route_candidate_rejected",
				"resource_provider",
				resourceKind,
				"",
				Map.of("goal", goal.normalizedKey(), "reason", "unsupported_resource_kind")
			));
			return Optional.empty();
		}
		int targetCount = goal.minimum("countAtLeast", 1);
		int deficitCount = Math.max(0, targetCount - existingGoalCount(goal));
		if (deficitCount <= 0) {
			return Optional.of(new ProviderCandidate(ActionRoute.empty(), "resource_provider", resourceKind));
		}
		if (!entry.get().aggregate()) {
			String itemId = entry.get().primaryItemId();
			if (itemId.isBlank()) {
				return Optional.empty();
			}
			trace.add(event(
				"route_candidate_built",
				"resource_provider",
				resourceKind,
				"",
				Map.of("goal", goal.normalizedKey(), "cost", 35, "resourceKind", resourceKind, "itemId", itemId)
			));
			Optional<ProviderCandidate> miningRoute = resolveMiningProviderGoal(
				ActionGoal.inventoryItem(itemId, targetCount),
				trace
			);
			if (miningRoute.isEmpty()) {
				trace.add(event(
					"route_candidate_rejected",
					"resource_provider",
					resourceKind,
					"",
					Map.of("goal", goal.normalizedKey(), "reason", "mining_route_unavailable", "itemId", itemId)
				));
				return Optional.empty();
			}
			return Optional.of(new ProviderCandidate(miningRoute.get().route(), "resource_provider", resourceKind));
		}
		int resourceCost = estimateAggregateResourceCost(entry.get(), deficitCount);
		trace.add(event(
			"route_candidate_built",
			"resource_provider",
			resourceKind,
			"",
			Map.of("goal", goal.normalizedKey(), "cost", resourceCost, "resourceKind", resourceKind)
		));
		LinkedHashMap<String, Object> args = new LinkedHashMap<>();
		args.put("resourceKind", resourceKind);
		args.put("quantity", deficitCount);
		ActionPlanStep step = new ActionPlanStep(ActionStepKind.PRIMITIVE, "resource_provider", resourceKind, "collect_resource", "collect_resource", args);
		trace.add(event("primitive_planned", "resource_provider", resourceKind, "collect_resource", Map.of("primitive", "collect_resource", "resourceKind", resourceKind)));
		return Optional.of(new ProviderCandidate(
			new ActionRoute(List.of(step), resourceCost),
			"resource_provider",
			resourceKind
		));
	}

	private Optional<ProviderCandidate> resolveRecipeProviderGoal(
		ActionGoal goal,
		List<ActionTraceEvent> trace
	) {
		if (goal.factType() != ActionFactType.INVENTORY_ITEM) {
			return Optional.empty();
		}
		String outputItemId = goal.keys().getOrDefault("itemId", "");
		if (outputItemId.isBlank()) {
			return Optional.empty();
		}
		int targetCount = goal.minimum("countAtLeast", 1);
		int deficitCount = Math.max(0, targetCount - existingGoalCount(goal));
		if (deficitCount <= 0) {
			return Optional.of(new ProviderCandidate(ActionRoute.empty(), "recipe_provider", outputItemId));
		}

		ActionRoute bestRoute = null;
		String bestRecipeId = "";
		for (ActionFact recipe : craftRecipesByOutput.getOrDefault(outputItemId, List.of())) {
			String recipeId = recipe.identity().keys().getOrDefault("recipeId", "");
			Map<String, Integer> inputCounts = recipeInputCounts(recipe);
			if (inputCounts.isEmpty()) {
				continue;
			}
			int outputCount = Math.max(1, intPayload(recipe, "outputCount", 1));
			int craftTimes = Math.max(1, (int) Math.ceil(deficitCount / (double) outputCount));
			String gridKind = scalar(recipe.payload().get("gridKind"), "");
			Map<String, Integer> effectiveInputCounts = effectiveRecipeInputCounts(outputItemId, inputCounts, gridKind, craftTimes);
			ArrayList<ActionPlanStep> steps = new ArrayList<>();
			int routeCost = 0;
			boolean inputsResolved = true;
			for (Map.Entry<String, Integer> input : effectiveInputCounts.entrySet()) {
				int requiredCount = input.getValue();
				if (requiredCount <= 0) {
					continue;
				}
				Optional<ActionRoute> subRoute = resolveRecipeInputGoal(input.getKey(), requiredCount, trace);
				if (subRoute.isEmpty()) {
					inputsResolved = false;
					break;
				}
				steps.addAll(subRoute.get().steps());
				routeCost = saturatingAdd(routeCost, subRoute.get().cost());
			}
			if (!inputsResolved) {
				continue;
			}

			LinkedHashMap<String, Object> args = new LinkedHashMap<>();
			args.put("itemId", outputItemId);
			args.put("recipeId", recipeId);
			args.put("quantity", deficitCount);
			steps.add(new ActionPlanStep(ActionStepKind.PRIMITIVE, "recipe_provider", recipeId, "craft_item", "craft_item", args));
			trace.add(event("primitive_planned", "recipe_provider", recipeId, "craft_item", Map.of("primitive", "craft_item", "itemId", outputItemId)));
			ActionRoute candidateRoute = new ActionRoute(steps, routeCost);
			trace.add(event(
				"route_candidate_built",
				"recipe_provider",
				recipeId,
				"",
				Map.of(
					"goal", goal.normalizedKey(),
					"cost", routeCost,
					"outputItemId", outputItemId,
					"intrinsicCost", 0
				)
			));
			if (bestRoute == null || candidateRoute.cost() < bestRoute.cost()) {
				bestRoute = candidateRoute;
				bestRecipeId = recipeId;
			}
		}

		if (bestRoute == null) {
			return Optional.empty();
		}
		return Optional.of(new ProviderCandidate(bestRoute, "recipe_provider", bestRecipeId));
	}

	private Map<String, Integer> effectiveRecipeInputCounts(
		String outputItemId, Map<String, Integer> inputCounts, String gridKind, int craftTimes
	) {
		LinkedHashMap<String, Integer> effective = new LinkedHashMap<>();
		inputCounts.forEach((item, count) -> effective.put(item, count * craftTimes));
		boolean nearbyWorkbench = facts.query(ActionFactType.WORLD_SITE,
			Map.of("worldId", context.worldId(), "dimension", context.dimension())).stream()
			.anyMatch(fact -> "crafting_table".equals(fact.payload().get("kind"))
				&& context.actorId().equals(fact.payload().get("availableToActor")));
		if ("WORKBENCH_3X3".equals(gridKind)
			&& !"minecraft:crafting_table".equals(outputItemId)
			&& existingGoalCount(ActionGoal.inventoryItem("minecraft:crafting_table", 1)) < 1
			&& !nearbyWorkbench) {
			// One portable table serves the entire batch, not one table per recipe run.
			effective.merge(workbenchSetupPlankItemId(inputCounts), 4, Integer::sum);
		}
		return effective;
	}

	private String workbenchSetupPlankItemId(Map<String, Integer> inputCounts) {
		for (String plankItemId : ActionGraphDomainKnowledge.plankItemIds()) {
			if (inputCounts.containsKey(plankItemId)) {
				return plankItemId;
			}
		}
		for (String plankItemId : ActionGraphDomainKnowledge.plankItemIds()) {
			int count = existingGoalCount(ActionGoal.inventoryItem(plankItemId, 1));
			if (count >= 4) {
				return plankItemId;
			}
		}
		String bestObservedLogPlank = "";
		int bestObservedLogCount = 0;
		for (String logItemId : ActionGraphDomainKnowledge.logItemIds()) {
			int count = existingGoalCount(ActionGoal.inventoryItem(logItemId, 1));
			String plank = ItemGroups.plankFor(logItemId);
			if (count > bestObservedLogCount && !plank.isEmpty()) {
				bestObservedLogPlank = plank;
				bestObservedLogCount = count;
			}
		}
		if (!bestObservedLogPlank.isBlank()) {
			return bestObservedLogPlank;
		}
		String bestObservedPlank = "";
		int bestObservedPlankCount = 0;
		for (String plankItemId : ActionGraphDomainKnowledge.plankItemIds()) {
			int count = existingGoalCount(ActionGoal.inventoryItem(plankItemId, 1));
			if (count > bestObservedPlankCount) {
				bestObservedPlank = plankItemId;
				bestObservedPlankCount = count;
			}
		}
		if (!bestObservedPlank.isBlank()) {
			return bestObservedPlank;
		}
		return "minecraft:oak_planks";
	}

	private Optional<ActionRoute> resolveRecipeInputGoal(
		String itemId,
		int requiredCount,
		List<ActionTraceEvent> trace
	) {
		if ("minecraft:stick".equals(itemId)) {
			Optional<ProviderCandidate> craftedSticks = resolveRecipeProviderGoal(
				ActionGoal.inventoryItem(itemId, requiredCount),
				trace
			);
			if (craftedSticks.isPresent()) {
				return Optional.of(craftedSticks.get().route());
			}
		}
		if (ActionGraphDomainKnowledge.plankItemIds().contains(itemId)) {
			return resolveRecipePlankInputGoal(itemId, requiredCount, trace);
		}
		if (!ActionGraphDomainKnowledge.logItemIds().contains(itemId)) {
			return resolveGoal(ActionGoal.inventoryItem(itemId, requiredCount));
		}
		int exactCount = existingGoalCount(ActionGoal.inventoryItem(itemId, requiredCount));
		if (exactCount >= requiredCount) {
			return Optional.of(ActionRoute.empty());
		}
		int totalWoodLogs = existingGoalCount(ActionGoal.resourceCollection("WOOD_LOGS", 1));
		if (exactCount <= 0 && totalWoodLogs > 0) {
			trace.add(event(
				"route_candidate_blocked",
				"resource_provider",
				itemId,
				"",
				Map.of("goal", ActionGoal.inventoryItem(itemId, requiredCount).normalizedKey(), "reason", "missing_observed_log_variant")
			));
			return Optional.empty();
		}
		int missingExactLogs = requiredCount - exactCount;
		return resolveGoal(ActionGoal.resourceCollection("WOOD_LOGS", totalWoodLogs + missingExactLogs));
	}

	private Optional<ActionRoute> resolveRecipePlankInputGoal(
		String itemId,
		int requiredCount,
		List<ActionTraceEvent> trace
	) {
		int exactCount = existingGoalCount(ActionGoal.inventoryItem(itemId, requiredCount));
		if (exactCount >= requiredCount) {
			return Optional.of(ActionRoute.empty());
		}
		String logItemId = logItemForPlank(itemId);
		int logCount = logItemId.isBlank() ? 0 : existingGoalCount(ActionGoal.inventoryItem(logItemId, 1));
		if (logCount > 0) {
			return resolveGoal(ActionGoal.inventoryItem(itemId, requiredCount));
		}
		int missingLogs = Math.max(1, (int) Math.ceil((requiredCount - exactCount) / 4.0));
		if (exactCount > 0 || !observedAnyWoodMaterial()) {
			int totalWoodLogs = existingGoalCount(ActionGoal.resourceCollection("WOOD_LOGS", 1));
			return resolveGoal(ActionGoal.resourceCollection("WOOD_LOGS", totalWoodLogs + missingLogs));
		}
		trace.add(event(
			"route_candidate_blocked",
			"recipe_provider",
			itemId,
			"",
			Map.of("goal", ActionGoal.inventoryItem(itemId, requiredCount).normalizedKey(), "reason", "missing_observed_plank_variant")
		));
		return Optional.empty();
	}

	private static String logItemForPlank(String plankItemId) {
		return ItemGroups.logFor(plankItemId);
	}

	private boolean observedAnyWoodMaterial() {
		if (existingGoalCount(ActionGoal.resourceCollection("WOOD_LOGS", 1)) > 0) {
			return true;
		}
		for (String logItemId : ActionGraphDomainKnowledge.logItemIds()) {
			if (existingGoalCount(ActionGoal.inventoryItem(logItemId, 1)) > 0) {
				return true;
			}
		}
		for (String plankItemId : ActionGraphDomainKnowledge.plankItemIds()) {
			if (existingGoalCount(ActionGoal.inventoryItem(plankItemId, 1)) > 0) {
				return true;
			}
		}
		return false;
	}

	private Optional<ProviderCandidate> resolveMiningProviderGoal(
		ActionGoal goal,
		List<ActionTraceEvent> trace
	) {
		if (goal.factType() != ActionFactType.INVENTORY_ITEM) {
			return Optional.empty();
		}
		String itemId = goal.keys().getOrDefault("itemId", "");
		if (itemId.isBlank()) {
			return Optional.empty();
		}
		int targetCount = goal.minimum("countAtLeast", 1);
		int deficitCount = Math.max(0, targetCount - existingGoalCount(goal));
		if (deficitCount <= 0) {
			return Optional.of(new ProviderCandidate(ActionRoute.empty(), "mining_provider", itemId));
		}
		List<BlockAcquisitionRule> acquisitionRules = blockAcquisitions.rulesForOutput(itemId);
		if (acquisitionRules.isEmpty()) {
			return Optional.empty();
		}
		ActionRoute bestRoute = null;
		String bestOptionId = "";
		for (BlockAcquisitionRule rule : acquisitionRules.stream()
			.sorted(Comparator
				.comparing(BlockAcquisitionRule::blockId)
				.thenComparing(BlockAcquisitionRule::lootTableId))
			.toList()) {
			if (unobservedCraftableBlock(rule, itemId)) {
				trace.add(event(
					"route_candidate_rejected",
					"mining_provider",
					rule.blockId(),
					"",
					Map.of("goal", goal.normalizedKey(), "reason", "unobserved_craftable_block", "itemId", itemId)
				));
				continue;
			}
			if (!rule.dropEstimateKnown() || rule.expectedDropsPerBreak() <= 0.0) {
				trace.add(event(
					"route_candidate_rejected",
					"mining_provider",
					rule.blockId(),
					"",
					Map.of("goal", goal.normalizedKey(), "reason", "unknown_drop_estimate", "itemId", itemId)
				));
				continue;
			}
			int expectedBreakCount = expectedBreakCount(deficitCount, rule.expectedDropsPerBreak());
			int availabilityPenalty = availabilityPenalty(rule, expectedBreakCount);
			MiningToolPlan toolPlan = resolveMiningToolPlan(
				rule,
				expectedBreakCount,
				availabilityPenalty,
				trace,
				goal,
				itemId
			);
			if (!toolPlan.available()) {
				continue;
			}
			List<String> blockIds = List.of(rule.blockId());
			String optionId = rule.blockId() + ":" + toolPlan.normalizedToolKey();
			int routeCost = saturatingAdd(toolPlan.route().cost(), toolPlan.miningWorkCost());
			ArrayList<ActionPlanStep> steps = new ArrayList<>(toolPlan.route().steps());
			LinkedHashMap<String, Object> args = new LinkedHashMap<>();
			args.put("itemId", itemId);
			args.put("blockIds", blockIds);
			args.put("matchingItemIds", List.of(itemId));
			args.put("requiredToolItemIds", toolPlan.requiredToolItemIds());
			args.put("quantity", deficitCount);
			args.put("targetCount", targetCount);
			args.put("estimatedBreakCount", expectedBreakCount);
			steps.add(new ActionPlanStep(ActionStepKind.PRIMITIVE, "mining_provider", optionId, "mine_block", "mine_block", args));
			ActionRoute candidate = new ActionRoute(List.copyOf(steps), routeCost);
			trace.add(event(
				"route_candidate_built",
				"mining_provider",
				optionId,
				"",
				Map.ofEntries(
					Map.entry("goal", goal.normalizedKey()),
					Map.entry("cost", routeCost),
					Map.entry("itemId", itemId),
					Map.entry("blockIds", blockIds),
					Map.entry("requiredToolItemIds", args.get("requiredToolItemIds")),
					Map.entry("dropProbability", rule.dropProbability()),
					Map.entry("expectedDropsPerBreak", rule.expectedDropsPerBreak()),
					Map.entry("expectedBreakCount", expectedBreakCount),
					Map.entry("breakTicks", toolPlan.breakTicks()),
					Map.entry("availabilityObserved", nearbyBlockAvailability.observed()),
					Map.entry("nearbyBlockCount", nearbyBlockAvailability.count(rule.blockId())),
					Map.entry("availabilityPenalty", availabilityPenalty),
					Map.entry("baseMiningCost", MiningCostModel.BASE_MINING_COST),
					Map.entry("breakTickCost", MiningCostModel.breakTickCost(toolPlan.breakTicks())),
					Map.entry("miningWorkCost", toolPlan.miningWorkCost())
				)
			));
			if (bestRoute == null
				|| candidate.cost() < bestRoute.cost()
				|| (candidate.cost() == bestRoute.cost() && optionId.compareTo(bestOptionId) < 0)) {
				bestRoute = candidate;
				bestOptionId = optionId;
			}
		}
		if (bestRoute == null) {
			return Optional.empty();
		}
		ActionPlanStep selected = bestRoute.steps().getLast();
		trace.add(event("primitive_planned", "mining_provider", bestOptionId, "mine_block", Map.of(
			"primitive", "mine_block",
			"itemId", itemId,
			"blockIds", selected.args().get("blockIds"),
			"requiredToolItemIds", selected.args().get("requiredToolItemIds")
		)));
		return Optional.of(new ProviderCandidate(bestRoute, "mining_provider", bestOptionId));
	}

	private boolean unobservedCraftableBlock(BlockAcquisitionRule rule, String itemId) {
		return rule.blockId().equals(itemId)
			&& craftRecipesByOutput.containsKey(itemId)
			&& nearbyBlockAvailability.count(rule.blockId()) <= 0;
	}

	private MiningToolPlan resolveMiningToolPlan(
		BlockAcquisitionRule rule,
		int expectedBreakCount,
		int availabilityPenalty,
		List<ActionTraceEvent> trace,
		ActionGoal goal,
		String outputItemId
	) {
		MiningToolPlan bestPlan = null;
		if (rule.emptyHandAllowed()) {
			int handTicks = normalizedBreakTicks(rule.emptyHandBreakTicks());
			bestPlan = MiningToolPlan.available(
				ActionRoute.empty(),
				List.of(),
				handTicks,
				MiningCostModel.workCost(handTicks, expectedBreakCount, availabilityPenalty)
			);
			for (Map.Entry<String, Integer> tool : rule.breakTicksByToolItemId().entrySet()) {
				if (existingGoalCount(ActionGoal.inventoryItem(tool.getKey(), 1)) < 1) {
					continue;
				}
				int breakTicks = normalizedBreakTicks(tool.getValue());
				MiningToolPlan candidate = MiningToolPlan.available(
					ActionRoute.empty(),
					List.of(tool.getKey()),
					breakTicks,
					MiningCostModel.workCost(breakTicks, expectedBreakCount, availabilityPenalty)
				);
				bestPlan = cheaperMiningToolPlan(bestPlan, candidate);
			}
		}
		else {
			for (String toolItemId : rule.usableToolItemIds()) {
				ActionRoute toolRoute;
				if (existingGoalCount(ActionGoal.inventoryItem(toolItemId, 1)) >= 1) {
					toolRoute = ActionRoute.empty();
				}
				else {
					Optional<ActionRoute> candidate = resolveGoal(ActionGoal.inventoryItem(toolItemId, 1));
					if (candidate.isEmpty()) {
						continue;
					}
					toolRoute = candidate.get();
				}
				int breakTicks = normalizedBreakTicks(rule.breakTicksByToolItemId().getOrDefault(toolItemId, rule.emptyHandBreakTicks()));
				MiningToolPlan candidate = MiningToolPlan.available(
					toolRoute,
					List.of(toolItemId),
					breakTicks,
					MiningCostModel.workCost(breakTicks, expectedBreakCount, availabilityPenalty)
				);
				bestPlan = cheaperMiningToolPlan(bestPlan, candidate);
			}
		}
		if (bestPlan != null) {
			return bestPlan;
		}
		trace.add(event(
			"route_candidate_rejected",
			"mining_provider",
			outputItemId,
			"",
			Map.of(
				"goal", goal.normalizedKey(),
				"reason", "missing_tool_prerequisite",
				"toolItemIds", rule.usableToolItemIds()
			)
		));
		return MiningToolPlan.unavailable();
	}

	private Optional<ProviderCandidate> resolveSmeltingProviderGoal(
		ActionGoal goal,
		List<ActionTraceEvent> trace
	) {
		if (goal.factType() != ActionFactType.INVENTORY_ITEM) {
			return Optional.empty();
		}
		String outputItemId = goal.keys().getOrDefault("itemId", "");
		if (outputItemId.isBlank()) {
			return Optional.empty();
		}
		int targetCount = goal.minimum("countAtLeast", 1);
		int deficitCount = Math.max(0, targetCount - existingGoalCount(goal));
		if (deficitCount <= 0) {
			return Optional.of(new ProviderCandidate(ActionRoute.empty(), "smelting_provider", outputItemId));
		}

		ActionRoute bestRoute = null;
		String bestOptionId = "";
		int bestProvenanceRank = Integer.MAX_VALUE;
		for (ActionFact recipe : smeltRecipesByOutput.getOrDefault(outputItemId, List.of())) {
			String optionId = recipe.identity().keys().getOrDefault("optionId", "");
			String inputItemId = scalar(recipe.payload().get("inputItemId"), "");
			if (inputItemId.isBlank()) {
				continue;
			}
			int outputCount = Math.max(1, intPayload(recipe, "outputCount", 1));
			int inputQuantity = Math.max(1, (int) Math.ceil(deficitCount / (double) outputCount));
			int maxInputQuantity = Math.max(1, intPayload(recipe, "maxInputQuantity", inputQuantity));
			if (inputQuantity > maxInputQuantity) {
				continue;
			}
			ArrayList<ActionPlanStep> steps = new ArrayList<>();
			int routeCost = 25;
			String stationItemId = scalar(recipe.payload().get("stationItemId"), "");
			int stationItemCount = intPayload(recipe, "stationItemCount", 0);
			if (!stationItemId.isBlank() && stationItemCount > 0) {
				Optional<ActionRoute> stationRoute = resolveGoal(ActionGoal.inventoryItem(stationItemId, stationItemCount));
				if (stationRoute.isEmpty()) {
					continue;
				}
				steps.addAll(stationRoute.get().steps());
				routeCost = saturatingAdd(routeCost, stationRoute.get().cost());
			}

			Optional<ActionRoute> inputRoute = resolveGoal(ActionGoal.inventoryItem(inputItemId, inputQuantity));
			if (inputRoute.isEmpty()) {
				continue;
			}

			steps.addAll(inputRoute.get().steps());
			routeCost = saturatingAdd(routeCost, inputRoute.get().cost());

			int cookTimeTicks = intPayload(recipe, "cookTimeTicks", DEFAULT_SMELT_COOK_TICKS);
			Optional<FuelPlan> fuelPlan = resolveSmeltingFuel(
				inputItemId,
				inputQuantity,
				cookTimeTicks,
				trace,
				goal
			);
			if (fuelPlan.isPresent()) {
				steps.addAll(fuelPlan.get().route().steps());
				routeCost = saturatingAdd(routeCost, fuelPlan.get().route().cost());
			}
			else {
				trace.add(event(
					"route_candidate_rejected",
					"smelting_provider",
					optionId,
					"",
					Map.of("goal", goal.normalizedKey(), "reason", "missing_fuel_subgoal")
				));
				continue;
			}

			LinkedHashMap<String, Object> smeltArgs = new LinkedHashMap<>();
			smeltArgs.put("itemId", outputItemId);
			smeltArgs.put("inputItemId", inputItemId);
			smeltArgs.put("optionId", optionId);
			smeltArgs.put("quantity", deficitCount);
			smeltArgs.put("inputQuantity", inputQuantity);
			fuelPlan.ifPresent(plan -> {
				smeltArgs.put("fuelItemId", plan.itemId());
				smeltArgs.put("fuelQuantity", plan.quantity());
			});
			steps.add(new ActionPlanStep(ActionStepKind.PRIMITIVE, "smelting_provider", optionId, "smelt_item", "smelt_item", smeltArgs));
			trace.add(event("primitive_planned", "smelting_provider", optionId, "smelt_item", Map.of(
				"primitive", "smelt_item",
				"itemId", outputItemId,
				"inputItemId", inputItemId
			)));

			ActionWatchSpec readyWatch = new ActionWatchSpec(
				new ActionFactCondition(
					ActionFactType.SMELTING_PROCESS,
					Map.of(
						"worldId", context.worldId(),
						"actorId", context.actorId(),
						"optionId", optionId,
						"itemId", outputItemId
					),
					Map.of("ready", 1)
				),
				null,
				Math.max(1200L, (long) cookTimeTicks * inputQuantity + 1200L),
				ActionWatchProgressKind.AREA_TICKING,
				null
			);
			Map<String, Object> watchArgs = Map.of(
				"fact", ActionFactType.SMELTING_PROCESS.id(),
				"optionId", optionId,
				"itemId", outputItemId,
				"readyAtLeast", 1,
				"timeoutTicks", readyWatch.timeoutTicks()
			);
			steps.add(new ActionPlanStep(
				ActionStepKind.WATCH,
				"smelting_provider",
				optionId,
				"wait_for_smelted_item",
				"watch",
				watchArgs,
				readyWatch
			));
			trace.add(event("watch_planned", "smelting_provider", optionId, "wait_for_smelted_item", watchArgs));

			LinkedHashMap<String, Object> collectArgs = new LinkedHashMap<>();
			collectArgs.put("itemId", outputItemId);
			collectArgs.put("quantity", deficitCount);
			steps.add(new ActionPlanStep(ActionStepKind.PRIMITIVE, "smelting_provider", optionId, "collect_smelted_item", "collect_smelted_item", collectArgs));
			trace.add(event("primitive_planned", "smelting_provider", optionId, "collect_smelted_item", Map.of(
				"primitive", "collect_smelted_item",
				"itemId", outputItemId
			)));
			trace.add(event(
				"route_candidate_built",
				"smelting_provider",
				optionId,
				"",
				Map.of("goal", goal.normalizedKey(), "cost", routeCost, "outputItemId", outputItemId)
			));
			ActionRoute candidateRoute = new ActionRoute(steps, routeCost);
			int provenanceRank = recipe.provenance().authoritative() ? 0 : 1;
			if (bestRoute == null
				|| candidateRoute.cost() < bestRoute.cost()
				|| candidateRoute.cost() == bestRoute.cost() && provenanceRank < bestProvenanceRank
				|| candidateRoute.cost() == bestRoute.cost() && provenanceRank == bestProvenanceRank && optionId.compareTo(bestOptionId) < 0) {
				bestRoute = candidateRoute;
				bestOptionId = optionId;
				bestProvenanceRank = provenanceRank;
			}
		}

		if (bestRoute == null) {
			return Optional.empty();
		}
		return Optional.of(new ProviderCandidate(bestRoute, "smelting_provider", bestOptionId));
	}

	private Optional<FuelPlan> resolveSmeltingFuel(
		String inputItemId,
		int inputQuantity,
		int cookTimeTicks,
		List<ActionTraceEvent> trace,
		ActionGoal smeltingGoal
	) {
		int requiredFuelTicks = inputQuantity * Math.max(1, cookTimeTicks);
		if (requiredFuelTicks <= 0) {
			return Optional.empty();
		}
		List<FuelCandidate> orderedCandidates = fuelCandidates().stream()
			.sorted(Comparator
				.comparingInt(this::fuelAcquisitionAffinity).reversed())
			.toList();
		FuelPlan bestPlan = null;
		for (FuelCandidate candidate : orderedCandidates) {
			int requiredQuantity = fuelItemsNeeded(requiredFuelTicks, candidate.fuelTicks());
			if (requiredQuantity <= 0) {
				continue;
			}
			ActionGoal fuelGoal = ActionGoal.inventoryItem(candidate.itemId(), requiredQuantity + (candidate.itemId().equals(inputItemId) ? inputQuantity : 0));
			if (existingGoalCount(fuelGoal) >= fuelGoal.minimum("countAtLeast", 1)) {
				bestPlan = chooseCheaperFuelPlan(bestPlan, new FuelPlan(candidate.itemId(), requiredQuantity, ActionRoute.empty(), candidate.fuelTicks()));
			}
		}
		// Existing fuel needs no acquisition. Do not replace it with a mining trip.
		if (bestPlan == null) {
			for (FuelCandidate candidate : orderedCandidates) {
				int requiredQuantity = fuelItemsNeeded(requiredFuelTicks, candidate.fuelTicks());
				if (requiredQuantity <= 0) {
					continue;
				}
				ActionGoal fuelGoal = ActionGoal.inventoryItem(candidate.itemId(), requiredQuantity + (candidate.itemId().equals(inputItemId) ? inputQuantity : 0));
				Optional<ActionRoute> fuelRoute = resolveGoal(fuelGoal);
				if (fuelRoute.isPresent()) {
					bestPlan = chooseCheaperFuelPlan(bestPlan, new FuelPlan(candidate.itemId(), requiredQuantity, fuelRoute.get(), candidate.fuelTicks()));
				}
			}
		}
		if (bestPlan == null) {
			return Optional.empty();
		}
		trace.add(event(
			bestPlan.route().steps().isEmpty() ? "fuel_subgoal_satisfied" : "fuel_subgoal_planned",
			"smelting_provider",
			bestPlan.itemId(),
			"",
			Map.of("goal", smeltingGoal.normalizedKey(), "fuelItemId", bestPlan.itemId(), "fuelQuantity", bestPlan.quantity())
		));
		return Optional.of(bestPlan);
	}

	private int fuelAcquisitionAffinity(FuelCandidate candidate) {
		int affinity = existingGoalCount(ActionGoal.inventoryItem(candidate.itemId(), 1)) * 1_000;
		for (ActionFact recipe : craftRecipesByOutput.getOrDefault(candidate.itemId(), List.of())) {
			for (String inputItemId : recipeInputCounts(recipe).keySet()) {
				affinity += existingGoalCount(ActionGoal.inventoryItem(inputItemId, 1));
			}
		}
		return affinity;
	}

	private static FuelPlan chooseCheaperFuelPlan(FuelPlan current, FuelPlan candidate) {
		if (current == null) return candidate;
		int acquisition = Integer.compare(candidate.route().cost(), current.route().cost());
		if (acquisition < 0) return candidate;
		if (acquisition > 0) return current;
		return SmeltingFuelCost.compare(candidate.itemId(), candidate.quantity(), candidate.fuelTicks(),
			current.itemId(), current.quantity(), current.fuelTicks()) < 0 ? candidate : current;
	}

	private boolean goalSatisfied(ActionGoal goal, List<ActionTraceEvent> trace) {
		LinkedHashMap<String, String> keys = new LinkedHashMap<>(goal.keys());
		if (goal.factType() == ActionFactType.INVENTORY_ITEM
			|| goal.factType() == ActionFactType.INVENTORY_RESOURCE
			|| goal.factType() == ActionFactType.INVENTORY_TOOL) {
			keys.put("worldId", context.worldId());
			keys.put("actorId", context.actorId());
		}
		LinkedHashMap<String, Integer> minimums = new LinkedHashMap<>();
		goal.minimums().forEach((key, value) -> minimums.put(switch (key) {
			case "countAtLeast" -> "count";
			case "matureCountAtLeast" -> "matureCount";
			case "readyAtLeast" -> "ready";
			default -> key;
		}, value));
		ActionFactCondition condition = new ActionFactCondition(goal.factType(), keys, minimums);
		Optional<ActionFact> matched = facts.query(condition.factType(), condition.queryKeys()).stream()
			.filter(this::usableFact)
			.filter(condition::satisfiedBy)
			.findFirst();
		trace.add(event("fact_query", "", "", "", Map.of(
			"fact", condition.factType().id(),
			"keys", condition.queryKeys(),
			"satisfied", matched.isPresent()
		)));
		return matched.isPresent();
	}

	private boolean usableFact(ActionFact fact) {
		return GUARD_USABLE_PROVENANCE.contains(fact.provenance()) && !fact.isStaleAt(context.currentTick());
	}

	private Map<String, List<ActionFact>> providerFactsByOutput(
		ActionFactType factType,
		String outputPayloadKey,
		String identitySortKey
	) {
		Map<String, String> query = Map.of(
			"worldId", context.worldId(),
			"actorId", context.actorId()
		);
		LinkedHashMap<String, List<ActionFact>> indexed = new LinkedHashMap<>();
		for (ActionFact fact : facts.query(factType, query).stream()
			.filter(this::usableFact)
			.sorted(Comparator.comparing(candidate -> candidate.identity().keys().getOrDefault(identitySortKey, "")))
			.toList()) {
			String outputItemId = scalar(fact.payload().get(outputPayloadKey), "");
			if (!outputItemId.isBlank()) {
				indexed.computeIfAbsent(outputItemId, ignored -> new ArrayList<>()).add(fact);
			}
		}
		LinkedHashMap<String, List<ActionFact>> immutable = new LinkedHashMap<>();
		indexed.forEach((itemId, candidates) -> immutable.put(itemId, List.copyOf(candidates)));
		return Map.copyOf(immutable);
	}

	private int existingGoalCount(ActionGoal goal) {
		if (goal.factType() != ActionFactType.INVENTORY_ITEM && goal.factType() != ActionFactType.INVENTORY_RESOURCE) {
			return 0;
		}
		LinkedHashMap<String, String> queryKeys = new LinkedHashMap<>();
		queryKeys.put("worldId", context.worldId());
		queryKeys.put("actorId", context.actorId());
		queryKeys.putAll(goal.keys());
		return facts.query(goal.factType(), queryKeys).stream()
			.filter(this::usableFact)
			.map(ActionFact::payload)
			.map(payload -> payload.get("count"))
			.filter(Number.class::isInstance)
			.map(Number.class::cast)
			.mapToInt(Number::intValue)
			.max()
			.orElse(0);
	}

	private int estimateAggregateResourceCost(
		ResourceGatheringCatalog.ResourceEntry entry,
		int deficitCount
	) {
		int bestCost = Integer.MAX_VALUE;
		for (String itemId : entry.acceptedItemIds()) {
			for (BlockAcquisitionRule rule : blockAcquisitions.rulesForOutput(itemId)) {
				if (!rule.dropEstimateKnown() || rule.expectedDropsPerBreak() <= 0.0) {
					continue;
				}
				int breakTicks = fastestImmediatelyAvailableBreakTicks(rule);
				if (breakTicks <= 0) {
					continue;
				}
				int expectedBreakCount = expectedBreakCount(deficitCount, rule.expectedDropsPerBreak());
				int cost = MiningCostModel.workCost(
					breakTicks,
					expectedBreakCount,
					availabilityPenalty(rule, expectedBreakCount)
				);
				bestCost = Math.min(bestCost, cost);
			}
		}
		return bestCost == Integer.MAX_VALUE ? 20 : bestCost;
	}

	private int fastestImmediatelyAvailableBreakTicks(BlockAcquisitionRule rule) {
		int best = rule.emptyHandAllowed() ? normalizedBreakTicks(rule.emptyHandBreakTicks()) : Integer.MAX_VALUE;
		for (Map.Entry<String, Integer> tool : rule.breakTicksByToolItemId().entrySet()) {
			if (existingGoalCount(ActionGoal.inventoryItem(tool.getKey(), 1)) >= 1) {
				best = Math.min(best, normalizedBreakTicks(tool.getValue()));
			}
		}
		return best == Integer.MAX_VALUE ? -1 : best;
	}

	private int availabilityPenalty(BlockAcquisitionRule rule, int expectedBreakCount) {
		return MiningCostModel.availabilityPenalty(
			nearbyBlockAvailability.observed(),
			nearbyBlockAvailability.count(rule.blockId()),
			expectedBreakCount
		);
	}

	private static int expectedBreakCount(int itemCount, double expectedDropsPerBreak) {
		if (itemCount <= 0) {
			return 0;
		}
		if (!Double.isFinite(expectedDropsPerBreak) || expectedDropsPerBreak <= 0.0) {
			return Integer.MAX_VALUE;
		}
		double expected = Math.ceil(itemCount / expectedDropsPerBreak);
		return expected >= Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(1, (int) expected);
	}

	private static int normalizedBreakTicks(int breakTicks) {
		return Math.max(1, breakTicks);
	}

	private static MiningToolPlan cheaperMiningToolPlan(MiningToolPlan current, MiningToolPlan candidate) {
		if (current == null) {
			return candidate;
		}
		int currentCost = saturatingAdd(current.route().cost(), current.miningWorkCost());
		int candidateCost = saturatingAdd(candidate.route().cost(), candidate.miningWorkCost());
		if (candidateCost != currentCost) {
			return candidateCost < currentCost ? candidate : current;
		}
		if (candidate.breakTicks() != current.breakTicks()) {
			return candidate.breakTicks() < current.breakTicks() ? candidate : current;
		}
		return candidate.normalizedToolKey().compareTo(current.normalizedToolKey()) < 0 ? candidate : current;
	}

	private static int saturatingAdd(int left, int right) {
		long result = (long) Math.max(0, left) + Math.max(0, right);
		return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
	}

	private record ProviderCandidate(
		ActionRoute route,
		String actionId,
		String alternativeId
	) {
		private ProviderCandidate {
			route = route == null ? ActionRoute.empty() : route;
			actionId = actionId == null ? "" : actionId;
			alternativeId = alternativeId == null ? "" : alternativeId;
		}
	}

	private record MiningToolPlan(
		boolean available,
		ActionRoute route,
		List<String> requiredToolItemIds,
		int breakTicks,
		int miningWorkCost
	) {
		private MiningToolPlan {
			route = route == null ? ActionRoute.empty() : route;
			requiredToolItemIds = requiredToolItemIds == null ? List.of() : requiredToolItemIds.stream()
				.filter(value -> value != null && !value.isBlank())
				.distinct()
				.sorted()
				.toList();
			breakTicks = normalizedBreakTicks(breakTicks);
			miningWorkCost = Math.max(0, miningWorkCost);
		}

		private static MiningToolPlan available(
			ActionRoute route,
			List<String> requiredToolItemIds,
			int breakTicks,
			int miningWorkCost
		) {
			return new MiningToolPlan(true, route, requiredToolItemIds, breakTicks, miningWorkCost);
		}

		private static MiningToolPlan unavailable() {
			return new MiningToolPlan(false, ActionRoute.empty(), List.of(), 1, 0);
		}

		private String normalizedToolKey() {
			return requiredToolItemIds.isEmpty() ? "hand" : String.join(",", requiredToolItemIds);
		}
	}

	private static int fuelItemsNeeded(int requiredFuelTicks, int fuelTicksPerItem) {
		if (requiredFuelTicks <= 0) {
			return 0;
		}
		if (fuelTicksPerItem <= 0) {
			return Integer.MAX_VALUE;
		}
		return (requiredFuelTicks + fuelTicksPerItem - 1) / fuelTicksPerItem;
	}

	private static List<FuelCandidate> fuelCandidates() {
		ArrayList<FuelCandidate> candidates = new ArrayList<>();
		for (String itemId : ActionGraphDomainKnowledge.plankItemIds()) {
			candidates.add(new FuelCandidate(itemId, FUEL_TICKS_PLANKS));
		}
		candidates.add(new FuelCandidate("minecraft:stick", FUEL_TICKS_STICKS));
		for (String itemId : ActionGraphDomainKnowledge.logItemIds()) {
			candidates.add(new FuelCandidate(itemId, FUEL_TICKS_LOGS));
		}
		candidates.add(new FuelCandidate("minecraft:coal", FUEL_TICKS_COAL));
		candidates.add(new FuelCandidate("minecraft:charcoal", FUEL_TICKS_COAL));
		return List.copyOf(candidates);
	}

	private static long longValue(Object value, long fallback) {
		return value instanceof Number number ? number.longValue() : fallback;
	}

	private static Map<String, Integer> recipeInputCounts(ActionFact recipe) {
		LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
		Object inputCounts = recipe.payload().get("inputCounts");
		if (inputCounts instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				String itemId = String.valueOf(entry.getKey());
				if (itemId.isBlank()) {
					continue;
				}
				int count = entry.getValue() instanceof Number number ? number.intValue() : 0;
				if (count > 0) {
					counts.merge(itemId, count, Integer::sum);
				}
			}
		}
		if (!counts.isEmpty()) {
			return Map.copyOf(counts);
		}
		Object inputItemIds = recipe.payload().get("inputItemIds");
		if (inputItemIds instanceof List<?> list) {
			for (Object item : list) {
				String itemId = String.valueOf(item);
				if (!itemId.isBlank()) {
					counts.merge(itemId, 1, Integer::sum);
				}
			}
		}
		return Map.copyOf(counts);
	}

	private static int intPayload(ActionFact fact, String key, int fallback) {
		Object value = fact.payload().get(key);
		return value instanceof Number number ? number.intValue() : fallback;
	}

	private static ActionTraceEvent event(
		String eventType,
		String actionId,
		String alternativeId,
		String stepId,
		Map<String, Object> payload
	) {
		return new ActionTraceEvent(eventType, actionId, alternativeId, stepId, payload);
	}

	private static List<String> identityKeyNames() {
		return List.of("itemId", "resourceKind", "toolTag", "dimension", "blockPos", "cropId", "siteId", "siteType", "plotId", "candidateId", "sourceId", "sampleId", "entityTypeId", "entityId", "recipeId", "optionId", "processId", "watchId", "goalId");
	}

	private static boolean worldDimensionScopedFact(ActionFactType factType) {
		return factType == ActionFactType.WORLD_BLOCK
			|| factType == ActionFactType.WORLD_CROP
			|| factType == ActionFactType.WORLD_CROP_GROUP
			|| factType == ActionFactType.WORLD_SITE
			|| factType == ActionFactType.WORLD_FARM_SITE
			|| factType == ActionFactType.WORLD_FARM_PLOT
			|| factType == ActionFactType.WORLD_SOIL_CANDIDATE
			|| factType == ActionFactType.WORLD_HYDRATION_SOURCE
			|| factType == ActionFactType.WORLD_LIGHT_LEVEL
			|| factType == ActionFactType.WORLD_CROP_SEED_SOURCE
			|| factType == ActionFactType.WORLD_ENTITY;
	}

	private static String scalar(Object value, String fallback) {
		return value == null ? fallback : String.valueOf(value);
	}

	@FunctionalInterface
	private interface TopLevelProviderResolver {
		Optional<ProviderCandidate> resolve(ActionGoal goal, List<ActionTraceEvent> trace);
	}

	private record FuelCandidate(String itemId, int fuelTicks) {
	}

	private record FuelPlan(String itemId, int quantity, ActionRoute route, int fuelTicks) {
	}

}
