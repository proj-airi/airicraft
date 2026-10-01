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
        double distanceSquared(Cell cell);
        default boolean immediatelyPlaceable(Cell cell) { return false; }
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
    private final List<Cell> cells;
    private final Environment environment;
    private final Map<Cell,Integer> deferred=new HashMap<>();
    private Cell target;
    private CompletableFuture<JsonElement> settling;
    private JsonElement settlingResult;
    private int settlingTicks;
    private final ConstructionRepairLedger ledger=new ConstructionRepairLedger();
    private final ArrayDeque<AccessRepairSearch.Edit> repairQueue=new ArrayDeque<>();
    private AccessRepairSearch.Edit repairEdit;
    private int repairEdits,repairPlans;
    private boolean inspected,closed,moving,movingToAccess,restoredDebt;
    private int consecutiveFailures,scaffoldsPlaced,scaffoldsRemoved;
    private int placed,failures,remaining;
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
        if(!restoredDebt){ledger.restore(environment.restoredDebt());restoredDebt=true;}
        var step=advance(result);
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
                return effect("place_block",args);
            }
            if(inspected && target.state().equals(environment.state(target))) {
                consecutiveFailures=0;
                if(repairEdit==null)placed++;else {
                    repairEdits++;deferred.keySet().removeIf(environment::immediatelyPlaceable);
                    if(repairQueue.isEmpty())accessDestination=environment.repairDestination();
                    if(repairEdit.before().equals("minecraft:air") && !target.air())scaffoldsPlaced++;
                    if(target.air() && ledger.entries().get(repairEdit.position()).requiredState().equals("minecraft:air"))scaffoldsRemoved++;
                }
                deferred.remove(target);
                ledger.reconcile(p->environment.state(new Cell(p.x(),p.y(),p.z(),"minecraft:air","minecraft:air","")));
            } else {
                failures++;consecutiveFailures++;repairQueue.clear();environment.repairDestination();deferred.put(target,placed);lastFailure=target.key()+": "+result;
            }
            target=null;repairEdit=null;inspected=false;
        }
        ledger.reconcile(p->environment.state(new Cell(p.x(),p.y(),p.z(),"minecraft:air","minecraft:air","")));
        if(accessDestination!=null) {
            movingToAccess=true;
            var args=new JsonObject();args.addProperty("x",accessDestination.x());args.addProperty("y",accessDestination.y());args.addProperty("z",accessDestination.z());args.addProperty("exactY",true);
            return effect("navigate_to",args);
        }
        List<Cell> pending=cells.stream().filter(c->!c.state().equals(environment.state(c))).toList();
        remaining=pending.size();
        if(repairPlans==0) {repairPlans++;repairQueue.addAll(environment.repairPlan(cells,true));}
        if(consecutiveFailures>=3 && repairQueue.isEmpty() && repairPlans<32) {
            consecutiveFailures=0;repairPlans++;repairQueue.addAll(environment.repairPlan(cells,false));
        }
        if(pending.isEmpty() && ledger.entries().isEmpty()) {
            var value=new JsonObject();value.addProperty("placed",placed);value.addProperty("retries",failures);value.addProperty("verifiedCells",cells.size());
            var done=new JsonObject();done.addProperty("done",true);done.add("value",value);return done;
        }
        Set<String> pendingPositions=new HashSet<>();pending.forEach(c->pendingPositions.add(c.key()));
        var eligible=pending.stream().filter(c->!c.air() && !c.state().contains("half=upper") && environment.eligible(c)).toList();
        var immediate=new HashSet<Cell>();
        eligible.stream().filter(environment::immediatelyPlaceable).forEach(immediate::add);
        var ordering=Comparator.comparingInt((Cell c)->immediate.contains(c)?0:1)
            .thenComparingDouble(c->score(c,pendingPositions)).thenComparing(Cell::key);
        // Exhaust untried work first. Retry only after verified geometry progress, never
        // merely because another target failed or a tool returned an accepted receipt.
        target=eligible.stream().filter(c->!deferred.containsKey(c)).min(ordering)
            .orElseGet(()->eligible.stream().filter(c->deferred.getOrDefault(c,placed)<placed).min(ordering).orElse(null));
        if(!repairQueue.isEmpty() || target==null) {
            if(repairQueue.isEmpty() && repairPlans++<32) {
                boolean onlyCleanup=pending.stream().allMatch(Cell::air) && !ledger.entries().isEmpty();
                repairQueue.addAll(onlyCleanup?environment.cleanupPlan(cells,ledger.entries()):environment.repairPlan(cells,false));
            }
            if(repairQueue.isEmpty())throw new IllegalStateException("construction_no_feasible_target remaining="+pending.size()+" deferred="+deferred.size()+" repairDebt="+ledger.entries().size()+" last="+lastFailure);
            repairEdit=repairQueue.removeFirst();var p=repairEdit.position();
            target=new Cell(p.x(),p.y(),p.z(),repairEdit.after(),repairEdit.after().split("\\[")[0],"temporary_access");
            if(!repairEdit.before().equals(environment.state(target)))throw new IllegalStateException("repair_precondition_changed");
            if(repairEdit.stance()!=null) {
                moving=true;var stance=repairEdit.stance();var args=new JsonObject();args.addProperty("x",stance.x());args.addProperty("y",stance.y());args.addProperty("z",stance.z());args.addProperty("exactY",true);
                return effect("navigate_to",args);
            }
        }
        return inspectTarget();
    }
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
        var value=environment.metrics();value.addProperty("repairEdits",repairEdits);value.addProperty("scaffoldsPlaced",scaffoldsPlaced);value.addProperty("scaffoldsRemoved",scaffoldsRemoved);value.addProperty("repairPlans",repairPlans);
        var debts=new JsonArray();ledger.entries().forEach((p,d)->{var debt=new JsonObject();debt.addProperty("x",p.x());debt.addProperty("y",p.y());debt.addProperty("z",p.z());debt.addProperty("state",d.requiredState());debt.addProperty("component",d.component());debt.addProperty("intermediateState",d.intermediateState());debts.add(debt);});value.add("repairDebt",debts);value.addProperty("totalCells",cells.size());value.addProperty("remainingCells",remaining);value.addProperty("placed",placed);value.addProperty("failedAttempts",failures);value.addProperty("deferred",deferred.size());
        if(target!=null){var at=position(target);at.addProperty("state",target.state());at.addProperty("component",target.owner());value.add("target",at);}
        value.addProperty("phase",settling!=null?"waiting_for_ground":movingToAccess?"moving_to_platform":moving?"moving_to_repair":target==null?"planning":inspected?"placing":"inspecting");return value;
    }
    @Override public void close(){if(!closed){closed=true;if(settling!=null){settling.completeExceptionally(new IllegalStateException("construction_cancelled"));settling=null;}environment.close();}}
}
