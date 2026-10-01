package ai.moeru.airicraft.blueprint;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BlueprintConstructionProgramTest {
    private static final JsonElement OK = JsonParser.parseString("{\"ok\":true}");
    private static final JsonElement FAIL = JsonParser.parseString("{\"ok\":false,\"error\":\"no_stance\"}");
    private static BlueprintConstructionProgram.Cell cell(int y) { return new BlueprintConstructionProgram.Cell(0,y,0,"minecraft:stone","minecraft:stone","house.wall"); }
    private static class World implements BlueprintConstructionProgram.Environment {
        final Map<Integer,String> states = new HashMap<>();
        public String state(BlueprintConstructionProgram.Cell cell) { return states.getOrDefault(cell.y(),"minecraft:air"); }
        public boolean eligible(BlueprintConstructionProgram.Cell cell) { return cell.y()==0 || states.containsKey(cell.y()-1); }
        public double distanceSquared(BlueprintConstructionProgram.Cell cell) { return cell.y()*cell.y(); }
        public void close() { }
    }
    private static String tool(JsonObject step) { return step.getAsJsonObject("value").getAsJsonObject("arguments").get("name").getAsString(); }
    @Test void replansAfterEachVerifiedPlacementAndBuildsSupportBeforeUpperCell() {
        var world=new World(); var program=new BlueprintConstructionProgram(List.of(cell(1),cell(0)),world);
        assertEquals("inspect_world",tool(program.resume(JsonNull.INSTANCE).join().getAsJsonObject()));
        var place=program.resume(OK).join().getAsJsonObject(); assertEquals("place_block",tool(place));
        assertEquals("[0,0,0,0,1,0]",place.getAsJsonObject("value").getAsJsonObject("arguments").getAsJsonObject("args").get("escapeBounds").toString());
        assertEquals(0,place.getAsJsonObject("value").getAsJsonObject("arguments").getAsJsonObject("args").get("y").getAsInt());
        world.states.put(0,"minecraft:stone");
        assertEquals("inspect_world",tool(program.resume(OK).join().getAsJsonObject()));
        assertEquals(1,program.progress().get("placed").getAsInt());
        assertEquals(1,program.progress().get("remainingCells").getAsInt());
        program.resume(OK).join(); world.states.put(1,"minecraft:stone");
        assertTrue(program.resume(OK).join().getAsJsonObject().get("done").getAsBoolean());
    }
    @Test void successfulToolReceiptDoesNotHideWorldMismatch() {
        var program=new BlueprintConstructionProgram(List.of(cell(0)),new World());
        program.resume(JsonNull.INSTANCE).join(); program.resume(OK).join();
        assertThrows(RuntimeException.class,()->program.resume(OK).join());
    }
    @Test void failedTargetIsDeferredUntilOtherWorkChangesGeometry() {
        var world=new World(){ public boolean eligible(BlueprintConstructionProgram.Cell c){return true;} };
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(1)),world);
        program.resume(JsonNull.INSTANCE).join(); program.resume(OK).join();
        assertEquals("inspect_world",tool(program.resume(FAIL).join().getAsJsonObject()));
        var next=program.resume(OK).join().getAsJsonObject();
        assertEquals(1,next.getAsJsonObject("value").getAsJsonObject("arguments").getAsJsonObject("args").get("y").getAsInt());
        world.states.put(1,"minecraft:stone");
        assertEquals("inspect_world",tool(program.resume(OK).join().getAsJsonObject()));
    }
    @Test void completesUntriedWorkBeforeRetryingFailedTarget() {
        var world=new World(){ public boolean eligible(BlueprintConstructionProgram.Cell c){return true;} };
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(1),cell(2)),world);
        program.resume(JsonNull.INSTANCE).join(); program.resume(OK).join();
        program.resume(FAIL).join(); program.resume(OK).join();
        world.states.put(1,"minecraft:stone");
        program.resume(OK).join();
        var next=program.resume(OK).join().getAsJsonObject();
        assertEquals(2,next.getAsJsonObject("value").getAsJsonObject("arguments").getAsJsonObject("args").get("y").getAsInt());
        world.states.put(2,"minecraft:stone");
        program.resume(OK).join();
        var retry=program.resume(OK).join().getAsJsonObject();
        assertEquals(0,retry.getAsJsonObject("value").getAsJsonObject("arguments").getAsJsonObject("args").get("y").getAsInt());
        assertThrows(RuntimeException.class,()->program.resume(FAIL).join());
    }

    @Test void accessRepairBreaksThroughNormalToolAndKeepsRestorationDebt() {
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return false;}
            public List<AccessRepairSearch.Edit> repairPlan(List<BlueprintConstructionProgram.Cell> cells){
                return List.of(new AccessRepairSearch.Edit(new ConstructionEscape.Position(0,1,0),"minecraft:stone","minecraft:air",8));
            }
        };
        world.states.put(1,"minecraft:stone");
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(1)),world);
        assertEquals("inspect_world",tool(program.resume(JsonNull.INSTANCE).join().getAsJsonObject()));
        assertEquals("break_blocks",tool(program.resume(OK).join().getAsJsonObject()));
        world.states.remove(1);
        // A successful break cannot count as completing construction or erase the repair obligation.
        assertThrows(RuntimeException.class,()->program.resume(OK).join());
        assertEquals(1,program.progress().getAsJsonArray("repairDebt").size());
        assertEquals(1,program.progress().get("repairEdits").getAsInt());
    }

    @Test void waitsForGroundedEnvironmentBeforePlanningOrDispatching() {
        var ready=new java.util.concurrent.atomic.AtomicBoolean(false);
        var world=new World(){public boolean ready(){return ready.get();}};
        var program=new BlueprintConstructionProgram(List.of(cell(0)),world);
        var pending=program.resume(JsonNull.INSTANCE);
        assertFalse(pending.isDone());program.tick();assertFalse(pending.isDone());
        ready.set(true);program.tick();assertEquals("inspect_world",tool(pending.join().getAsJsonObject()));
    }

    @Test void immediatelyPlaceableWorkWinsOverLowButDistantWork() {
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return true;}
            public boolean immediatelyPlaceable(BlueprintConstructionProgram.Cell c){return c.y()==4;}
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(4)),world);
        program.resume(JsonNull.INSTANCE).join();
        var action=program.resume(OK).join().getAsJsonObject();
        assertEquals(4,action.getAsJsonObject("value").getAsJsonObject("arguments").getAsJsonObject("args").get("y").getAsInt());
    }
    @Test void unrelatedScaffoldDoesNotResetFailedTargets() {
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return true;}
            public List<AccessRepairSearch.Edit> repairPlan(List<BlueprintConstructionProgram.Cell> cells,boolean accessOnly){
                return accessOnly || states.containsKey(9)?List.of():List.of(new AccessRepairSearch.Edit(new ConstructionEscape.Position(0,9,0),"minecraft:air","minecraft:dirt",2));
            }
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0)),world);
        program.resume(JsonNull.INSTANCE).join();program.resume(OK).join();
        program.resume(FAIL).join();assertEquals("place_block",tool(program.resume(OK).join().getAsJsonObject()));
        world.states.put(9,"minecraft:dirt");
        assertThrows(RuntimeException.class,()->program.resume(OK).join());
        assertEquals(1,program.progress().get("deferred").getAsInt());
    }
    @Test void navigatesOntoVerifiedPlatformBeforeSelectingMoreWork() {
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return states.containsKey(1);}
            public List<AccessRepairSearch.Edit> repairPlan(List<BlueprintConstructionProgram.Cell> cells,boolean accessOnly){
                return accessOnly?List.of():List.of(new AccessRepairSearch.Edit(new ConstructionEscape.Position(0,1,0),"minecraft:air","minecraft:dirt",2));
            }
            public ConstructionEscape.Position repairDestination(){return new ConstructionEscape.Position(0,2,0);}
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0)),world);
        program.resume(JsonNull.INSTANCE).join();program.resume(OK).join();
        world.states.put(1,"minecraft:dirt");
        assertEquals("navigate_to",tool(program.resume(OK).join().getAsJsonObject()));
        assertEquals("moving_to_platform",program.progress().get("phase").getAsString());
        assertEquals(1,program.progress().getAsJsonArray("repairDebt").size());
        assertEquals("inspect_world",tool(program.resume(OK).join().getAsJsonObject()));
    }
    @Test void repairEffectWaitsForJournalBeforeDispatch() {
        var saved=new java.util.concurrent.CompletableFuture<Void>();
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return false;}
            public List<AccessRepairSearch.Edit> repairPlan(List<BlueprintConstructionProgram.Cell> cells,boolean accessOnly){return List.of(new AccessRepairSearch.Edit(new ConstructionEscape.Position(0,1,0),"minecraft:air","minecraft:dirt",2));}
            public java.util.concurrent.CompletableFuture<Void> persistDebt(Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debts){return debts.isEmpty()?java.util.concurrent.CompletableFuture.completedFuture(null):saved;}
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0)),world);
        program.resume(JsonNull.INSTANCE).join();
        var effect=program.resume(OK);assertFalse(effect.isDone());
        saved.complete(null);assertEquals("place_block",tool(effect.join().getAsJsonObject()));
    }
    @Test void restoredScaffoldDebtPreventsFalseCompletion() {
        var world=new World(){
            public Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> restoredDebt(){return Map.of(new ConstructionEscape.Position(0,1,0),new ConstructionRepairLedger.Debt("minecraft:air","temporary_access","minecraft:dirt"));}
            public List<AccessRepairSearch.Edit> cleanupPlan(List<BlueprintConstructionProgram.Cell> cells,Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debts){return List.of(new AccessRepairSearch.Edit(new ConstructionEscape.Position(0,1,0),"minecraft:dirt","minecraft:air",1));}
        };
        world.states.put(0,"minecraft:stone");world.states.put(1,"minecraft:dirt");
        var program=new BlueprintConstructionProgram(List.of(cell(0)),world);
        assertEquals("inspect_world",tool(program.resume(JsonNull.INSTANCE).join().getAsJsonObject()));
        assertEquals("break_blocks",tool(program.resume(OK).join().getAsJsonObject()));
        assertEquals(1,program.progress().getAsJsonArray("repairDebt").size());
        world.states.remove(1);assertTrue(program.resume(OK).join().getAsJsonObject().get("done").getAsBoolean());
    }
    @Test void scaffoldIsRemovedFromSafePoseBeforeCompletion() {
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return states.containsKey(1);}
            public List<AccessRepairSearch.Edit> repairPlan(List<BlueprintConstructionProgram.Cell> cells,boolean accessOnly){
                return accessOnly?List.of():List.of(new AccessRepairSearch.Edit(new ConstructionEscape.Position(0,1,0),"minecraft:air","minecraft:dirt",2));
            }
            public List<AccessRepairSearch.Edit> cleanupPlan(List<BlueprintConstructionProgram.Cell> cells,Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debts){
                return List.of(new AccessRepairSearch.Edit(new ConstructionEscape.Position(0,1,0),"minecraft:dirt","minecraft:air",1,new ConstructionEscape.Position(2,0,0)));
            }
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0)),world);
        program.resume(JsonNull.INSTANCE).join();assertEquals("place_block",tool(program.resume(OK).join().getAsJsonObject()));
        world.states.put(1,"minecraft:dirt");program.resume(OK).join();
        assertEquals("place_block",tool(program.resume(OK).join().getAsJsonObject()));world.states.put(0,"minecraft:stone");
        assertEquals("navigate_to",tool(program.resume(OK).join().getAsJsonObject()));
        assertEquals("inspect_world",tool(program.resume(OK).join().getAsJsonObject()));
        assertEquals("break_blocks",tool(program.resume(OK).join().getAsJsonObject()));
        world.states.remove(1);
        assertTrue(program.resume(OK).join().getAsJsonObject().get("done").getAsBoolean());
        assertEquals(0,program.progress().getAsJsonArray("repairDebt").size());
    }

}
