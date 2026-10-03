package ai.moeru.airicraft.blueprint;

import ai.moeru.airicraft.policy.PolicyRuntime;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Deterministic incremental construction; observations are re-read after every child action. */
public final class BlueprintConstructionProgram implements PolicyRuntime.Program {
    public record Cell(int x,int y,int z,String state,String item,String owner) {
        String key(){return x+","+y+","+z;}
        boolean air(){return state.equals("minecraft:air") || state.equals("minecraft:cave_air");}
    }
    public interface Environment extends AutoCloseable {
        String state(Cell cell);
        boolean eligible(Cell cell);
        default boolean hasMaterial(Cell cell){return true;}
        default boolean canToggle(Cell cell){return false;}
        /** Whether this cell is a load-bearing solid for lower-support ordering. */
        default boolean structuralSupport(Cell cell){return !cell.air();}
        default boolean retryReady(Cell cell){return true;}
        default Cell repairTarget(){return null;}
        default void repairFailed(AccessRepairSearch.Edit edit) { }
        default void geometryChanged() { }
        double distanceSquared(Cell cell);
        /** Estimated movement ticks to a legal work stance; infinity means no known route. */
        default double travelTicks(Cell cell){return Math.sqrt(distanceSquared(cell))*5; }
        default boolean immediatelyPlaceable(Cell cell) { return false; }
        /** Ranking hint only: placements still require the ordinary executor checks. */
        default int continuationCount(Cell placed,List<Cell> remaining) { return 0; }
        default ConstructionEscape.Position repairDestination() { return null; }
        default Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> restoredDebt(){return Map.of();}
        default CompletableFuture<Void> persistDebt(Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debt){return CompletableFuture.completedFuture(null);}
        default List<AccessRepairSearch.Edit> repairPlan(List<Cell> cells) { return List.of(); }
        default List<AccessRepairSearch.Edit> repairPlan(List<Cell> cells,boolean accessOnly) { return repairPlan(cells); }
        default List<AccessRepairSearch.Edit> cleanupPlan(List<Cell> cells,Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debts) { return List.of(); }
        default boolean ready() { return true; }
        default void tick() { }
        default JsonObject metrics() { return new JsonObject(); }
        @Override void close();
    }
    /** A full 32-block access tower needs navigate/inspect/edit both up and down. */
    public static int effectLimit(int cellCount){
        if(cellCount<1 || cellCount>8192)throw new IllegalArgumentException("construction_cell_limit");
        return Math.max(128,cellCount*8)+32*6;
    }
    private final List<Cell> cells;
    private final Environment environment;
    private final Map<Cell,Integer> deferred=new HashMap<>();
    private final Set<ConstructionEscape.Position> deferredCleanup=new HashSet<>();
    private Cell target,repairObjective;
    private ConstructionEscape.Position workAnchor;
    private String selectionReason="none";
    private int localSelections,workAreaSwitches;
    private double selectedTravelTicks=Double.NaN;
    private CompletableFuture<JsonElement> settling;
    private JsonElement settlingResult;
    private int settlingTicks;
    private final ConstructionRepairLedger ledger=new ConstructionRepairLedger();
    private final ArrayDeque<AccessRepairSearch.Edit> repairQueue=new ArrayDeque<>();
    private AccessRepairSearch.Edit repairEdit;
    private static final int MAX_REPAIR_PLANS_WITHOUT_PROGRESS=32;
    private final Set<String> verifiedFinalPositions=new HashSet<>();
    private int repairEdits,repairPlans,repairPlansWithoutProgress,towerBlocksPlaced;
    private boolean inspected,closed,moving,movingToAccess,restoredDebt;
    private int consecutiveFailures,scaffoldsPlaced,scaffoldsRemoved;
    private int placed,adjusted,failures,remaining,geometryRevision;
    private int accessProbeRevision=-1;
    private boolean adjusting;
    private String lastFailure="";
    public BlueprintConstructionProgram(List<Cell> cells,Environment environment) {
        this.cells=List.copyOf(cells);this.environment=Objects.requireNonNull(environment);
        if(cells.isEmpty() || cells.size()>8192)throw new IllegalArgumentException("construction_cell_limit");
        if(cells.stream().map(Cell::key).distinct().count()!=cells.size())throw new IllegalArgumentException("duplicate_construction_cell");
    }
    @Override public CompletableFuture<JsonElement> resume(JsonElement result) {
        if(!environment.ready()) {
            if(settling!=null)throw new IllegalStateException("construction_already_waiting_for_ground");
            settling=new CompletableFuture<>();settlingResult=result.deepCopy();settlingTicks=0;return settling;
        }
        try{return advanceDurably(result);}
        catch(RuntimeException error){return CompletableFuture.failedFuture(error);}
    }
    private CompletableFuture<JsonElement> advanceDurably(JsonElement result) {
        if(!restoredDebt){
            ledger.restore(environment.restoredDebt());
            // Restoring an initially correct wall is not new construction progress.
            cells.stream().filter(c->!c.air() && c.state().equals(environment.state(c)))
                .forEach(c->verifiedFinalPositions.add(c.key()));
            restoredDebt=true;
        }
        final JsonElement step;
        try{step=advance(result);}
        catch(RuntimeException error){
            // A verified restoration must survive even if planning the next action fails.
            return environment.persistDebt(ledger.entries()).thenCompose(ignored->CompletableFuture.failedFuture(error));
        }
        return environment.persistDebt(ledger.entries()).thenApply(ignored->step);
    }
    private JsonElement advance(JsonElement result) {
        if(closed)throw new IllegalStateException("construction_cancelled");
        if(movingToAccess) {
            movingToAccess=false;
            if(!result.isJsonObject() || !result.getAsJsonObject().has("ok") || !result.getAsJsonObject().get("ok").getAsBoolean())
                throw new IllegalStateException("access_platform_unreachable");
            deferred.keySet().removeIf(environment::immediatelyPlaceable);
        }
        ConstructionEscape.Position accessDestination=null;
        if(target!=null) {
            boolean ok=result.isJsonObject() && result.getAsJsonObject().has("ok") && result.getAsJsonObject().get("ok").getAsBoolean();
            if(moving) {
                moving=false;
                if(!ok)throw new IllegalStateException("repair_stance_unreachable");
                return inspectTarget();
            }
            if(!inspected && ok) {
                inspected=true;
                if(adjusting){
                    if(!environment.canToggle(target))throw new IllegalStateException("adjustment_precondition_changed");
                    var args=position(target);var ids=new JsonArray();ids.add(target.item());args.add("expectedSupportBlockIds",ids);
                    return effect("use_block",args);
                }
                if(repairEdit!=null) {
                    if(!repairEdit.before().equals(environment.state(target)))throw new IllegalStateException("repair_precondition_changed");
                    var intended=cells.stream().filter(c->c.key().equals(target.key())).findFirst();
                    ledger.beforeEdit(repairEdit.position(),intended.map(Cell::state).orElse(repairEdit.before()),intended.map(Cell::owner).orElse("temporary_access"),target.state());
                    if(target.air()) {
                        var block=position(target);var ids=new JsonArray();ids.add(repairEdit.before().split("\\[")[0]);block.add("expectedBlockIds",ids);
                        var targets=new JsonArray();targets.add(block);var args=new JsonObject();args.add("targets",targets);
                        return effect("break_blocks",args);
                    }
                }
                var args=position(target);args.addProperty("itemId",target.item());args.addProperty("expectedState",target.state());
                var bounds=new JsonArray();
                bounds.add(cells.stream().mapToInt(Cell::x).min().orElseThrow());bounds.add(cells.stream().mapToInt(Cell::y).min().orElseThrow());bounds.add(cells.stream().mapToInt(Cell::z).min().orElseThrow());
                bounds.add(cells.stream().mapToInt(Cell::x).max().orElseThrow());bounds.add(cells.stream().mapToInt(Cell::y).max().orElseThrow());bounds.add(cells.stream().mapToInt(Cell::z).max().orElseThrow());
                args.add("escapeBounds",bounds);
                if(repairEdit!=null && repairEdit.tower())args.addProperty("tower",true);
                return effect("place_block",args);
            }
            if(inspected && target.state().equals(environment.state(target))) {
                consecutiveFailures=0;deferredCleanup.clear();
                environment.geometryChanged();
                if(repairEdit==null && !adjusting){
                    // Count each intended solid position only once. Temporary scaffolds,
                    // teardown, door toggles and repeated wall patches cannot renew search.
                    if(!target.air() && verifiedFinalPositions.add(target.key()))repairPlansWithoutProgress=0;
                    if(workAnchor!=null && frontierDistance(target)>2)workAreaSwitches++;
                    workAnchor=new ConstructionEscape.Position(target.x(),target.y(),target.z());
                }
                // Removal can reopen routes to non-cube targets such as stairs. Added
                // scaffolds retain the narrower immediately-placeable retry policy.
                if(repairEdit==null || target.air())geometryRevision++;
                if(adjusting)adjusted++;else if(repairEdit==null)placed++;else {
                    repairEdits++;if(repairEdit.tower())towerBlocksPlaced++;deferred.keySet().removeIf(environment::immediatelyPlaceable);
                    if(repairQueue.isEmpty()){accessDestination=environment.repairDestination();repairObjective=environment.repairTarget();if(repairObjective!=null)deferred.remove(repairObjective);}
                    if(repairEdit.before().equals("minecraft:air") && !target.air())scaffoldsPlaced++;
                    if(target.air() && ledger.entries().get(repairEdit.position()).requiredState().equals("minecraft:air"))scaffoldsRemoved++;
                }
                if(target.equals(repairObjective))repairObjective=null;
                deferred.remove(target);
                ledger.reconcile(p->environment.state(new Cell(p.x(),p.y(),p.z(),"minecraft:air","minecraft:air","")));
            } else {
                if(repairEdit!=null)environment.repairFailed(repairEdit);
                if(repairEdit!=null && target.air())deferredCleanup.add(repairEdit.position());
                failures++;consecutiveFailures++;repairQueue.clear();environment.repairDestination();deferred.put(target,geometryRevision);lastFailure=target.key()+": "+result;
            }
            target=null;repairEdit=null;inspected=false;adjusting=false;
        }
        ledger.reconcile(p->environment.state(new Cell(p.x(),p.y(),p.z(),"minecraft:air","minecraft:air","")));
        if(accessDestination!=null) {
            workAnchor=repairObjective==null?accessDestination:new ConstructionEscape.Position(repairObjective.x(),repairObjective.y(),repairObjective.z());
            selectionReason="access_destination";
            movingToAccess=true;
            var args=new JsonObject();args.addProperty("x",accessDestination.x());args.addProperty("y",accessDestination.y());args.addProperty("z",accessDestination.z());args.addProperty("exactY",true);
            return effect("navigate_to",args);
        }
        List<Cell> pending=cells.stream().filter(c->!c.state().equals(environment.state(c))).toList();
        remaining=pending.size();
        // Resource failures cannot be repaired by changing geometry. Check before
        // any access search, and again after every child effect consumes inventory.
        var checkedItems=new HashSet<String>();
        for(var cell:pending)if(!cell.air() && !cell.state().contains("half=upper")
            && !environment.canToggle(cell) && checkedItems.add(cell.item()) && !environment.hasMaterial(cell))
            throw new IllegalStateException("construction_material_missing itemId="+cell.item()+" target="+cell.key());
        // Door closing waits until teardown, but it does not need an access-repair search.
        boolean onlyCleanup=!ledger.entries().isEmpty()
            && pending.stream().allMatch(c->c.air() || environment.canToggle(c));
        if(repairPlans==0) {repairPlans++;repairPlansWithoutProgress++;repairQueue.addAll(environment.repairPlan(cells,true));}
        if(!onlyCleanup && consecutiveFailures>=3 && repairQueue.isEmpty() && repairPlansWithoutProgress<MAX_REPAIR_PLANS_WITHOUT_PROGRESS) {
            consecutiveFailures=0;repairPlans++;repairPlansWithoutProgress++;repairQueue.addAll(environment.repairPlan(cells,false));
        }
        if(pending.isEmpty() && ledger.entries().isEmpty()) {
            var value=new JsonObject();value.addProperty("placed",placed);value.addProperty("retries",failures);value.addProperty("verifiedCells",cells.size());
            var done=new JsonObject();done.addProperty("done",true);done.add("value",value);return done;
        }
        Set<String> pendingPositions=new HashSet<>();pending.forEach(c->pendingPositions.add(c.key()));
        // Give untried lower supports their first attempt before covering them. A
        // failed support or intentional repair opening must not deadlock other work.
        Set<String> unfinishedSolids=new HashSet<>();pending.stream()
            .filter(c->environment.structuralSupport(c) && !deferred.containsKey(c) && !verifiedFinalPositions.contains(c.key()))
            .forEach(c->unfinishedSolids.add(c.key()));
        var eligible=pending.stream().filter(c->!unfinishedSolids.contains(c.x()+","+(c.y()-1)+","+c.z()))
            .filter(c->!c.air() && !c.state().contains("half=upper") && environment.eligible(c)).toList();
        var immediate=new HashSet<Cell>();
        eligible.stream().filter(environment::immediatelyPlaceable).forEach(immediate::add);
        var ordering=Comparator.comparingInt((Cell c)->c.equals(repairObjective)?0:1)
            .thenComparingInt(c->immediate.contains(c)?0:1)
            .thenComparingDouble(c->score(c,pendingPositions)).thenComparing(Cell::key);
        target=selectTarget(eligible,immediate,ordering,pending);
        // No shortlisted target has a known usable stance. Try a bounded access
        // macro before spending native attempts on the whole inaccessible layer.
        // An empty search does not veto native movement omitted by the coarse graph.
        if(target!=null && selectionReason.equals("global_untried") && repairQueue.isEmpty()
            && accessProbeRevision!=geometryRevision && repairPlansWithoutProgress<MAX_REPAIR_PLANS_WITHOUT_PROGRESS){
            accessProbeRevision=geometryRevision;
            repairPlans++;repairPlansWithoutProgress++;
            repairQueue.addAll(environment.repairPlan(cells,false));
        }
        if(target==null && repairQueue.isEmpty() && ledger.entries().isEmpty()
            && !pending.isEmpty() && pending.stream().allMatch(environment::canToggle)) {
            target=pending.stream().filter(c->!c.state().contains("half=upper") && !deferred.containsKey(c))
                .min(Comparator.comparingDouble(environment::distanceSquared)).orElse(null);
            adjusting=target!=null;
        }
        if(!repairQueue.isEmpty() || target==null) {
            if(repairQueue.isEmpty()) {
                if(!onlyCleanup && repairPlansWithoutProgress<MAX_REPAIR_PLANS_WITHOUT_PROGRESS){repairPlans++;repairPlansWithoutProgress++;repairQueue.addAll(environment.repairPlan(cells,false));}
                // Owned teardown is finite and must outlive the speculative access budget.
                // Failed removals are retried only after verified geometry progress.
                if(repairQueue.isEmpty() && !ledger.entries().isEmpty()){
                    var cleanupDebt=new LinkedHashMap<>(ledger.entries());
                    deferredCleanup.forEach(cleanupDebt::remove);
                    if(!cleanupDebt.isEmpty())repairQueue.addAll(environment.cleanupPlan(cells,cleanupDebt));
                }
            }
            if(repairQueue.isEmpty())throw new IllegalStateException("construction_no_feasible_target remaining="+pending.size()+" deferred="+deferred.size()+" repairDebt="+ledger.entries().size()+" last="+lastFailure);
            repairEdit=repairQueue.removeFirst();var p=repairEdit.position();
            selectionReason="access_repair_or_cleanup";
            target=new Cell(p.x(),p.y(),p.z(),repairEdit.after(),repairEdit.after().split("\\[")[0],"temporary_access");
            if(!repairEdit.before().equals(environment.state(target)))throw new IllegalStateException("repair_precondition_changed");
            if(repairEdit.stance()!=null) {
                moving=true;var stance=repairEdit.stance();var args=new JsonObject();args.addProperty("x",stance.x());args.addProperty("y",stance.y());args.addProperty("z",stance.z());args.addProperty("exactY",true);
                return effect("navigate_to",args);
            }
        }
        if(repairEdit==null && (selectionReason.equals("current_stance") || selectionReason.equals("local_frontier")))localSelections++;
        return inspectTarget();
    }
    private Cell selectTarget(List<Cell> eligible,Set<Cell> immediate,Comparator<Cell> ordering,List<Cell> pending){
        // Keep retries gated by verified geometry progress. Within each work area,
        // prefer untouched cells; do not let untouched remote work evict a now usable
        // local retry from the stance we just paid to reach.
        selectedTravelTicks=Double.NaN;
        var pendingKeys=new HashSet<String>();pending.forEach(c->pendingKeys.add(c.key()));
        var travel=new HashMap<Cell,Double>();
        java.util.function.ToDoubleFunction<Cell> travelCost=c->travel.computeIfAbsent(c,environment::travelTicks);
        Comparator<Cell> travelOrdering=Comparator.comparingDouble((Cell c)->travelCost.applyAsDouble(c)
            + score(c,pendingKeys)).thenComparing(ordering);
        var readiness=new HashMap<Cell,Boolean>();
        java.util.function.Predicate<Cell> reachable=c->readiness.computeIfAbsent(c,environment::retryReady);
        java.util.function.Predicate<Cell> attemptable=c->!deferred.containsKey(c)
            || deferred.get(c)<geometryRevision && reachable.test(c);
        if(repairObjective!=null && eligible.contains(repairObjective) && attemptable.test(repairObjective)){
            selectionReason="repair_objective";return repairObjective;
        }
        Comparator<Cell> withinArea=Comparator.comparingInt((Cell c)->deferred.containsKey(c)?1:0)
            .thenComparingInt(c->workAnchor==null?0:frontierDistance(c)).thenComparing(ordering);
        var fromHere=eligible.stream().filter(immediate::contains).filter(attemptable).sorted(withinArea).limit(24).toList();
        Cell chosen=null;
        if(!fromHere.isEmpty()){
            // One-ply geometry lookahead avoids sealing sight lines to work that
            // can still be finished from this stance. Bound both dimensions.
            var nearby=pending.stream().filter(c->!c.air() && !c.state().contains("half=upper"))
                .sorted(Comparator.comparingDouble(environment::distanceSquared)).limit(24).toList();
            var continuation=new HashMap<Cell,Integer>();
            if(fromHere.size()>1)for(var c:fromHere)continuation.put(c,environment.continuationCount(c,nearby));
            chosen=fromHere.stream().min(Comparator.comparingInt((Cell c)->-continuation.getOrDefault(c,0))
                .thenComparing(withinArea)).orElseThrow();
        }
        if(chosen!=null){selectedTravelTicks=0;selectionReason="current_stance";return chosen;}
        Cell local=null;
        if(workAnchor!=null){
            local=eligible.stream().filter(c->frontierDistance(c)<=2 && environment.distanceSquared(c)<=36)
                .filter(attemptable).sorted(withinArea).limit(24).filter(reachable)
                .min(Comparator.comparingInt((Cell c)->deferred.containsKey(c)?1:0).thenComparing(travelOrdering)).orElse(null);
        }
        var untried=eligible.stream().filter(c->!deferred.containsKey(c)).sorted(ordering).toList();
        // Include nearby targets even if a large lower layer consumes the structural
        // shortlist. Reachability and route estimates share one geometry flood fill.
        var candidates=new LinkedHashSet<Cell>();
        untried.stream().limit(24).forEach(candidates::add);
        untried.stream().sorted(Comparator.comparingDouble(environment::distanceSquared)).limit(24).forEach(candidates::add);
        chosen=candidates.stream().filter(reachable).min(travelOrdering).orElse(null);
        // A small switching allowance keeps a work patch coherent, but adjacency
        // must not force a long detour when a cheap placement is available elsewhere.
        if(local!=null && (chosen==null || travelCost.applyAsDouble(local)<=travelCost.applyAsDouble(chosen)+12)){
            selectedTravelTicks=travelCost.applyAsDouble(local);selectionReason="local_frontier";return local;
        }
        if(chosen!=null){selectedTravelTicks=travelCost.applyAsDouble(chosen);selectionReason="global_reachable";return chosen;}
        // The coarse graph cannot represent every legal native movement.
        if(!untried.isEmpty()){selectionReason="global_untried";return untried.getFirst();}
        selectionReason="global_retry";
        return eligible.stream().filter(attemptable).min(ordering).orElse(null);
    }
    private int frontierDistance(Cell c){return Math.abs(c.x()-workAnchor.x())+Math.abs(c.y()-workAnchor.y())+Math.abs(c.z()-workAnchor.z());}
    private JsonElement inspectTarget() {
        var args=new JsonObject();args.addProperty("mode","inspect_area");args.addProperty("scope","box");
        args.addProperty("x1",target.x());args.addProperty("x2",target.x());args.addProperty("y1",target.y());args.addProperty("y2",target.y());args.addProperty("z1",target.z());args.addProperty("z2",target.z());
        args.addProperty("detail","blocks");args.addProperty("maxResults",1);
        return effect("inspect_world",args);
    }
    private double score(Cell c,Set<String> pending) {
        int unlocks=0;
        for(int[] d:List.of(new int[]{1,0,0},new int[]{-1,0,0},new int[]{0,1,0},new int[]{0,-1,0},new int[]{0,0,1},new int[]{0,0,-1}))
            if(pending.contains((c.x()+d[0])+","+(c.y()+d[1])+","+(c.z()+d[2])))unlocks++;
        return c.y()*4 + environment.distanceSquared(c)*.05 - unlocks*2 - (c.item().endsWith("_stairs")?3:0);
    }
    private static JsonObject position(Cell c){var a=new JsonObject();a.addProperty("x",c.x());a.addProperty("y",c.y());a.addProperty("z",c.z());return a;}
    private static JsonObject effect(String name,JsonObject args){var call=new JsonObject();call.addProperty("name",name);call.add("args",args);var effect=new JsonObject();effect.addProperty("operation","call_tool");effect.add("arguments",call);var step=new JsonObject();step.addProperty("done",false);step.add("value",effect);return step;}
    @Override public void tick(){
        environment.tick();
        if(settling!=null) {
            if(environment.ready()) {
                var future=settling;settling=null;
                try{advanceDurably(settlingResult).whenComplete((step,error)->{if(error!=null)future.completeExceptionally(error);else future.complete(step);});}catch(RuntimeException error){future.completeExceptionally(error);}
            }else if(++settlingTicks>100) {
                settling.completeExceptionally(new IllegalStateException("construction_grounding_timeout"));settling=null;
            }
        }
    }
    @Override public JsonObject progress(){
        var value=environment.metrics();value.addProperty("repairEdits",repairEdits);value.addProperty("towerBlocksPlaced",towerBlocksPlaced);value.addProperty("scaffoldsPlaced",scaffoldsPlaced);value.addProperty("scaffoldsRemoved",scaffoldsRemoved);value.addProperty("repairPlans",repairPlans);value.addProperty("repairPlansWithoutProgress",repairPlansWithoutProgress);
        if(Double.isFinite(selectedTravelTicks))value.addProperty("estimatedTravelTicks",selectedTravelTicks);
        value.addProperty("selectionReason",selectionReason);value.addProperty("localSelections",localSelections);value.addProperty("workAreaSwitches",workAreaSwitches);
        if(workAnchor!=null){var anchor=new JsonObject();anchor.addProperty("x",workAnchor.x());anchor.addProperty("y",workAnchor.y());anchor.addProperty("z",workAnchor.z());value.add("workAnchor",anchor);}
        var debts=new JsonArray();ledger.entries().forEach((p,d)->{var debt=new JsonObject();debt.addProperty("x",p.x());debt.addProperty("y",p.y());debt.addProperty("z",p.z());debt.addProperty("state",d.requiredState());debt.addProperty("component",d.component());debt.addProperty("intermediateState",d.intermediateState());debts.add(debt);});value.add("repairDebt",debts);value.addProperty("totalCells",cells.size());value.addProperty("remainingCells",remaining);value.addProperty("placed",placed);value.addProperty("adjusted",adjusted);value.addProperty("failedAttempts",failures);value.addProperty("deferred",deferred.size());
        if(target!=null){var at=position(target);at.addProperty("state",target.state());at.addProperty("component",target.owner());value.add("target",at);}
        value.addProperty("phase",settling!=null?"waiting_for_ground":movingToAccess?"moving_to_platform":moving?"moving_to_repair":target==null?"planning":inspected?"placing":"inspecting");return value;
    }
    @Override public void close(){if(!closed){closed=true;if(settling!=null){settling.completeExceptionally(new IllegalStateException("construction_cancelled"));settling=null;}environment.close();}}
}
