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
    private static class RepairWorld extends World {
        static final int WALL=1000;
        RepairWorld(){states.put(WALL,"minecraft:stone");}
        public boolean eligible(BlueprintConstructionProgram.Cell c){return c.y()==WALL || !states.containsKey(WALL);}
        public boolean immediatelyPlaceable(BlueprintConstructionProgram.Cell c){return c.y()==WALL;}
        public BlueprintConstructionProgram.Cell repairTarget(){
            for(int y=0;y<40;y++)if(!states.containsKey(y))return cell(y);
            return null;
        }
        public List<AccessRepairSearch.Edit> repairPlan(List<BlueprintConstructionProgram.Cell> cells,boolean accessOnly){
            return accessOnly?List.of():List.of(new AccessRepairSearch.Edit(new ConstructionEscape.Position(0,WALL,0),"minecraft:stone","minecraft:air",8));
        }
    }
    private static JsonElement applyRepairFixtureEffect(JsonObject step,RepairWorld world,boolean failFinal){
        var args=step.getAsJsonObject("value").getAsJsonObject("arguments").getAsJsonObject("args");
        if(tool(step).equals("break_blocks")){world.states.remove(RepairWorld.WALL);return OK;}
        if(tool(step).equals("place_block")){
            int y=args.get("y").getAsInt();
            if(failFinal && y!=RepairWorld.WALL)return FAIL;
            world.states.put(y,"minecraft:stone");
        }
        return OK;
    }
    @Test void failedRepairReportsApproachBeforeReplanningAndSuccessfulEditClearsIt(){
        class FeedbackWorld extends RepairWorld {
            final FailedRepairApproaches rejected=new FailedRepairApproaches();
            int reports,changes;
            final ConstructionEscape.Position first=new ConstructionEscape.Position(1,0,0);
            final ConstructionEscape.Position second=new ConstructionEscape.Position(2,0,0);
            public void repairFailed(AccessRepairSearch.Edit edit){reports++;rejected.reject(edit);}
            public void geometryChanged(){changes++;rejected.clear();}
            public List<AccessRepairSearch.Edit> repairPlan(List<BlueprintConstructionProgram.Cell> cells,boolean accessOnly){
                if(accessOnly)return List.of();
                return java.util.stream.Stream.of(first,second)
                    .map(p->new AccessRepairSearch.Edit(new ConstructionEscape.Position(0,WALL,0),"minecraft:stone","minecraft:air",8,p))
                    .filter(rejected::allows).limit(1).toList();
            }
        }
        var world=new FeedbackWorld();
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(RepairWorld.WALL)),world);
        var step=program.resume(JsonNull.INSTANCE).join().getAsJsonObject();
        assertEquals("navigate_to",tool(step));
        step=program.resume(OK).join().getAsJsonObject();
        assertEquals("inspect_world",tool(step));
        step=program.resume(OK).join().getAsJsonObject();
        assertEquals("break_blocks",tool(step));
        step=program.resume(FAIL).join().getAsJsonObject();
        assertEquals(1,world.reports);
        assertEquals(0,world.changes);
        assertEquals("navigate_to",tool(step));
        assertEquals(2,step.getAsJsonObject("value").getAsJsonObject("arguments").getAsJsonObject("args").get("x").getAsInt());
        step=program.resume(OK).join().getAsJsonObject();
        step=program.resume(OK).join().getAsJsonObject();
        assertEquals("break_blocks",tool(step));
        world.states.remove(RepairWorld.WALL);
        program.resume(OK).join();
        assertEquals(1,world.changes);
    }
    @Test void smallBuildBudgetIncludesTemporaryAccessAndItsTeardown(){
        assertEquals("policy_effect_limit",runAccessBudgetFixture(128).reason());
        var result=runAccessBudgetFixture(BlueprintConstructionProgram.effectLimit(16));
        assertEquals("SUCCEEDED",result.state());
        assertEquals(16,result.result().getAsJsonObject().get("verifiedCells").getAsInt());
    }
    private ai.moeru.airicraft.policy.PolicyRuntime.Outcome runAccessBudgetFixture(int limit){
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return true;}
            public List<AccessRepairSearch.Edit> repairPlan(List<BlueprintConstructionProgram.Cell> cells,boolean accessOnly){
                if(!accessOnly)return List.of();
                return java.util.stream.IntStream.range(1000,1020).mapToObj(y->{
                    var p=new ConstructionEscape.Position(0,y,0);
                    return new AccessRepairSearch.Edit(p,"minecraft:air","minecraft:dirt",3,p,true);
                }).toList();
            }
            public List<AccessRepairSearch.Edit> cleanupPlan(List<BlueprintConstructionProgram.Cell> cells,
                    Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debts){
                return debts.keySet().stream().limit(1).map(p->new AccessRepairSearch.Edit(p,"minecraft:dirt","minecraft:air",3,p)).toList();
            }
        };
        var program=new BlueprintConstructionProgram(java.util.stream.IntStream.range(0,16)
            .mapToObj(BlueprintConstructionProgramTest::cell).toList(),world);
        var host=new ai.moeru.airicraft.policy.PolicyRuntime.Host(){
            public java.util.concurrent.CompletableFuture<JsonElement> execute(JsonObject effect){
                var call=effect.getAsJsonObject("arguments");var args=call.getAsJsonObject("args");
                switch(call.get("name").getAsString()){
                    case "place_block" -> world.states.put(args.get("y").getAsInt(),args.get("expectedState").getAsString());
                    case "break_blocks" -> world.states.remove(args.getAsJsonArray("targets").get(0).getAsJsonObject().get("y").getAsInt());
                }
                return java.util.concurrent.CompletableFuture.completedFuture(OK);
            }
            public void close(){}
        };
        var outcome=new java.util.concurrent.atomic.AtomicReference<ai.moeru.airicraft.policy.PolicyRuntime.Outcome>();
        var runtime=new ai.moeru.airicraft.policy.PolicyRuntime(program,host,outcome::set,2000,limit);
        while(runtime.active())runtime.tick();
        if(outcome.get().state().equals("SUCCEEDED")){
            assertEquals(20,program.progress().get("scaffoldsRemoved").getAsInt());
            assertEquals(0,program.progress().getAsJsonArray("repairDebt").size());
        }
        return outcome.get();
    }
    @Test void productiveBuildCanUseMoreThanThirtyTwoRepairPlans(){
        var world=new RepairWorld();
        var cells=new ArrayList<BlueprintConstructionProgram.Cell>();
        for(int y=0;y<40;y++)cells.add(cell(y));cells.add(cell(RepairWorld.WALL));
        var program=new BlueprintConstructionProgram(cells,world);
        var step=program.resume(JsonNull.INSTANCE).join().getAsJsonObject();
        for(int n=0;n<600 && !step.get("done").getAsBoolean();n++)
            step=program.resume(applyRepairFixtureEffect(step,world,false)).join().getAsJsonObject();
        assertTrue(step.get("done").getAsBoolean());
        assertTrue(program.progress().get("repairPlans").getAsInt()>32);
        assertEquals(0,program.progress().getAsJsonArray("repairDebt").size());
    }
    @Test void repeatedlyPatchingExistingWallDoesNotRenewRepairBudget(){
        var world=new RepairWorld();
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(RepairWorld.WALL)),world);
        var step=program.resume(JsonNull.INSTANCE).join().getAsJsonObject();
        boolean bounded=false;
        for(int n=0;n<600;n++){
            try {step=program.resume(applyRepairFixtureEffect(step,world,true)).join().getAsJsonObject();}
            catch(java.util.concurrent.CompletionException error){
                assertTrue(error.getCause().getMessage().startsWith("construction_no_feasible_target"));bounded=true;break;
            }
        }
        assertTrue(bounded,"Cut-and-patch churn must exhaust a finite budget");
        assertTrue(program.progress().get("repairPlans").getAsInt()<=32);
    }
    @Test void ceilingDoesNotWaitForTheHangingLanternThatRequiresIt(){
        var lamp=new BlueprintConstructionProgram.Cell(0,0,0,"minecraft:lantern[hanging=true]","minecraft:lantern","lamp");
        var ceiling=cell(1);
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return c.equals(ceiling) || states.containsKey(1);}
            public boolean structuralSupport(BlueprintConstructionProgram.Cell c){return c.equals(ceiling);}
        };
        var program=new BlueprintConstructionProgram(List.of(lamp,ceiling),world);
        var step=program.resume(JsonNull.INSTANCE).join().getAsJsonObject();var order=new ArrayList<Integer>();
        for(int n=0;n<12 && !(step.has("done") && step.get("done").getAsBoolean());n++){
            if(tool(step).equals("place_block")){
                int y=step.getAsJsonObject("value").getAsJsonObject("arguments").getAsJsonObject("args").get("y").getAsInt();
                order.add(y);world.states.put(y,y==0?lamp.state():ceiling.state());
            }
            step=program.resume(OK).join().getAsJsonObject();
        }
        assertEquals(List.of(1,0),order);assertTrue(step.get("done").getAsBoolean());
    }
    @Test void currentStancePreferenceCannotCoverAnUnfinishedPlannedSupport(){
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return true;}
            public boolean immediatelyPlaceable(BlueprintConstructionProgram.Cell c){return c.y()==1;}
        };
        var program=new BlueprintConstructionProgram(List.of(cell(1),cell(0)),world);
        var step=program.resume(JsonNull.INSTANCE).join().getAsJsonObject();
        assertEquals("inspect_world",tool(step));
        assertEquals(0,step.getAsJsonObject("value").getAsJsonObject("arguments").getAsJsonObject("args").get("y1").getAsInt());
    }
    @Test void intendedAirBelowIsNotARequiredSolidSupport(){
        var world=new World(){public boolean eligible(BlueprintConstructionProgram.Cell c){return true;}};
        world.states.put(0,"minecraft:stone");
        var air=new BlueprintConstructionProgram.Cell(0,0,0,"minecraft:air","minecraft:air","opening");
        var program=new BlueprintConstructionProgram(List.of(air,cell(1)),world);
        var step=program.resume(JsonNull.INSTANCE).join().getAsJsonObject();
        assertEquals(1,step.getAsJsonObject("value").getAsJsonObject("arguments").getAsJsonObject("args").get("y1").getAsInt());
    }
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

    @Test void globalSelectionPrefersReachableUntriedWorkBeforeAnUnreachableCloserBlock(){
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return true;}
            public boolean retryReady(BlueprintConstructionProgram.Cell c){return c.y()==4;}
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(4)),world);
        program.resume(JsonNull.INSTANCE).join();
        assertEquals(4,program.progress().getAsJsonObject("target").get("y").getAsInt());
    }
    @Test void untriedWorkStillHasNativeNavigationFallbackWhenCoarseReachabilityFindsNothing(){
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return true;}
            public boolean retryReady(BlueprintConstructionProgram.Cell c){return false;}
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(4)),world);
        program.resume(JsonNull.INSTANCE).join();
        assertEquals(0,program.progress().getAsJsonObject("target").get("y").getAsInt());
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
    @Test void routeTravelCostBeatsStraightLineDistanceAndHeight() {
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return true;}
            public double distanceSquared(BlueprintConstructionProgram.Cell c){return c.y()==0?1:100;}
            public double travelTicks(BlueprintConstructionProgram.Cell c){return c.y()==0?150:10;}
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(4)),world);
        program.resume(JsonNull.INSTANCE).join();
        assertEquals(4,program.progress().getAsJsonObject("target").get("y").getAsInt(),
            "The geometrically near target requires a long detour around a wall");
    }
    @Test void finishesWorkAtOneSiteBeforePayingToCrossBack() {
        var world=new World(){
            int site=1;
            public boolean eligible(BlueprintConstructionProgram.Cell c){return true;}
            public double distanceSquared(BlueprintConstructionProgram.Cell c){return 100;}
            public double travelTicks(BlueprintConstructionProgram.Cell c){return (c.y()%8==0?0:1)==site?5:150;}
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(4),cell(8),cell(12)),world);
        var step=program.resume(JsonNull.INSTANCE).join().getAsJsonObject();
        var order=new ArrayList<Integer>();
        for(int n=0;n<20 && !step.get("done").getAsBoolean();n++){
            if(tool(step).equals("place_block")){
                int y=step.getAsJsonObject("value").getAsJsonObject("arguments").getAsJsonObject("args").get("y").getAsInt();
                order.add(y);world.states.put(y,"minecraft:stone");world.site=y%8==0?0:1;
            }
            step=program.resume(OK).join().getAsJsonObject();
        }
        assertEquals(List.of(4,12,0,8),order);
        assertTrue(step.get("done").getAsBoolean());
    }
    @Test void currentStanceLookaheadDoesNotSealTheRemainingWorkBehindTheNearestBlock(){
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return true;}
            public boolean immediatelyPlaceable(BlueprintConstructionProgram.Cell c){return !states.containsKey(0);}
            public int continuationCount(BlueprintConstructionProgram.Cell placed,List<BlueprintConstructionProgram.Cell> remaining){
                // Near cell zero closes this work opening; cell two can be placed
                // through it first, leaving zero reachable for final closure.
                return placed.y()==0?0:(int)remaining.stream().filter(c->c.y()!=placed.y()).count();
            }
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(2)),world);
        var step=program.resume(JsonNull.INSTANCE).join().getAsJsonObject();
        var order=new ArrayList<Integer>();
        for(int n=0;n<12 && !(step.has("done") && step.get("done").getAsBoolean());n++){
            if(tool(step).equals("place_block")){
                int y=step.getAsJsonObject("value").getAsJsonObject("arguments").getAsJsonObject("args").get("y").getAsInt();
                assertFalse(world.states.containsKey(0),"near closure must be last");
                order.add(y);world.states.put(y,"minecraft:stone");
            }
            step=program.resume(OK).join().getAsJsonObject();
        }
        assertEquals(List.of(2,0),order);
        assertTrue(step.get("done").getAsBoolean());
        assertEquals(0,program.progress().get("failedAttempts").getAsInt());
    }
    @Test void verifiedProgressDoesNotRetryAStillUnreachableTarget() {
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return true;}
            public boolean retryReady(BlueprintConstructionProgram.Cell c){return false;}
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(1)),world);
        program.resume(JsonNull.INSTANCE).join();program.resume(OK).join();
        program.resume(FAIL).join();program.resume(OK).join();
        world.states.put(1,"minecraft:stone");
        assertThrows(RuntimeException.class,()->program.resume(OK).join());
        assertEquals(1,program.progress().get("failedAttempts").getAsInt());
    }
    @Test void continuesReachableAdjacentFrontierBeforeDistantLowerWork() {
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return true;}
            public boolean immediatelyPlaceable(BlueprintConstructionProgram.Cell c){return c.y()==30;}
            public double distanceSquared(BlueprintConstructionProgram.Cell c){return c.y()==0?100:1;}
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(30),cell(31)),world);
        program.resume(JsonNull.INSTANCE).join();program.resume(OK).join();world.states.put(30,"minecraft:stone");
        program.resume(OK).join();
        assertEquals(31,program.progress().getAsJsonObject("target").get("y").getAsInt());
        program.resume(OK).join();program.resume(FAIL).join();
        assertEquals(0,program.progress().getAsJsonObject("target").get("y").getAsInt(),"Failed local work must yield without looping");
    }
    @Test void newlyUsableWorkAtCurrentStanceBeatsUntriedRemoteWork() {
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return true;}
            public boolean immediatelyPlaceable(BlueprintConstructionProgram.Cell c){return c.y()>=4;}
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(4),cell(5)),world);
        program.resume(JsonNull.INSTANCE).join();program.resume(OK).join();program.resume(FAIL).join();
        assertEquals(5,program.progress().getAsJsonObject("target").get("y").getAsInt());
        program.resume(OK).join();world.states.put(5,"minecraft:stone");program.resume(OK).join();
        assertEquals(4,program.progress().getAsJsonObject("target").get("y").getAsInt(),"Use the current stance before abandoning it for untouched distant work");
    }
    @Test void adjacentTargetsWinWithinTheSameUsableStance() {
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return true;}
            public boolean immediatelyPlaceable(BlueprintConstructionProgram.Cell c){return states.containsKey(3) || c.y()==3;}
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(3),cell(4)),world);
        program.resume(JsonNull.INSTANCE).join();program.resume(OK).join();world.states.put(3,"minecraft:stone");
        program.resume(OK).join();
        assertEquals(4,program.progress().getAsJsonObject("target").get("y").getAsInt(),"Do not sweep back to the bottom when an adjacent target is usable from the same stance");
    }
    @Test void adjacentButUnreachableFrontierDoesNotHoldTheWorkArea() {
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return true;}
            public boolean immediatelyPlaceable(BlueprintConstructionProgram.Cell c){return c.y()==30;}
            public boolean retryReady(BlueprintConstructionProgram.Cell c){return c.y()!=31;}
            public double distanceSquared(BlueprintConstructionProgram.Cell c){return c.y()==0?100:1;}
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(30),cell(31)),world);
        program.resume(JsonNull.INSTANCE).join();program.resume(OK).join();world.states.put(30,"minecraft:stone");
        program.resume(OK).join();
        assertEquals(0,program.progress().getAsJsonObject("target").get("y").getAsInt());
    }
    @Test void openedAccessIsUsedBeforeRestoringTheObstructingWall() {
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return true;}
            public boolean immediatelyPlaceable(BlueprintConstructionProgram.Cell c){return c.y()==0;}
            public BlueprintConstructionProgram.Cell repairTarget(){return cell(1);}
            public List<AccessRepairSearch.Edit> repairPlan(List<BlueprintConstructionProgram.Cell> cells,boolean accessOnly){return accessOnly?List.of():List.of(new AccessRepairSearch.Edit(new ConstructionEscape.Position(0,0,0),"minecraft:stone","minecraft:air",8));}
        };
        world.states.put(0,"minecraft:stone");
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(1)),world);
        program.resume(JsonNull.INSTANCE).join();program.resume(OK).join();
        program.resume(FAIL).join();assertEquals("break_blocks",tool(program.resume(OK).join().getAsJsonObject()));
        world.states.remove(0);program.resume(OK).join();
        var place=program.resume(OK).join().getAsJsonObject();
        assertEquals(1,place.getAsJsonObject("value").getAsJsonObject("arguments").getAsJsonObject("args").get("y").getAsInt());
        world.states.put(1,"minecraft:stone");program.resume(OK).join();
        var patch=program.resume(OK).join().getAsJsonObject();
        assertEquals(0,patch.getAsJsonObject("value").getAsJsonObject("arguments").getAsJsonObject("args").get("y").getAsInt());
        world.states.put(0,"minecraft:stone");assertTrue(program.resume(OK).join().getAsJsonObject().get("done").getAsBoolean());
        assertEquals(0,program.progress().getAsJsonArray("repairDebt").size());
    }
    @Test void missingMaterialsStopBeforeAccessSearchOrPlacement(){
        var searches=new java.util.concurrent.atomic.AtomicInteger();
        var world=new World(){
            public boolean hasMaterial(BlueprintConstructionProgram.Cell c){return false;}
            public List<AccessRepairSearch.Edit> repairPlan(List<BlueprintConstructionProgram.Cell> cells,boolean accessOnly){searches.incrementAndGet();return List.of();}
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0)),world);
        var error=assertThrows(RuntimeException.class,()->program.resume(JsonNull.INSTANCE).join());
        assertTrue(error.toString().contains("construction_material_missing"));
        assertEquals(0,searches.get());
    }
    @Test void depletedMaterialStopsAfterVerifiedPlacementWithoutGeometryRetries(){
        var world=new World(){
            public boolean hasMaterial(BlueprintConstructionProgram.Cell c){return states.isEmpty();}
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(1)),world);
        program.resume(JsonNull.INSTANCE).join();program.resume(OK).join();world.states.put(0,"minecraft:stone");
        var error=assertThrows(RuntimeException.class,()->program.resume(OK).join());
        assertTrue(error.toString().contains("construction_material_missing"));
        assertEquals(1,program.progress().get("placed").getAsInt());
        assertEquals(0,program.progress().get("failedAttempts").getAsInt());
    }
    @Test void createsKnownAccessBeforeTryingTargetsWithNoReachableStance(){
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return true;}
            public boolean retryReady(BlueprintConstructionProgram.Cell c){return false;}
            public List<AccessRepairSearch.Edit> repairPlan(List<BlueprintConstructionProgram.Cell> cells,boolean accessOnly){
                return accessOnly?List.of():List.of(new AccessRepairSearch.Edit(new ConstructionEscape.Position(0,9,0),"minecraft:air","minecraft:dirt",2));
            }
        };
        var program=new BlueprintConstructionProgram(List.of(cell(4)),world);
        program.resume(JsonNull.INSTANCE).join();
        var step=program.resume(OK).join().getAsJsonObject();
        assertEquals("place_block",tool(step));
        assertEquals("minecraft:dirt",step.getAsJsonObject("value").getAsJsonObject("arguments").getAsJsonObject("args").get("itemId").getAsString());
        assertEquals(0,program.progress().get("failedAttempts").getAsInt());
    }
    @Test void unavailableAccessStillAllowsNativeFallbackWithoutRepeatedSpeculativeSearch(){
        var searches=new java.util.concurrent.atomic.AtomicInteger();
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return true;}
            public boolean retryReady(BlueprintConstructionProgram.Cell c){return false;}
            public List<AccessRepairSearch.Edit> repairPlan(List<BlueprintConstructionProgram.Cell> cells,boolean accessOnly){
                if(!accessOnly)searches.incrementAndGet();return List.of();
            }
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0),cell(4)),world);
        program.resume(JsonNull.INSTANCE).join();
        assertEquals(1,searches.get());
        assertEquals("place_block",tool(program.resume(OK).join().getAsJsonObject()));
        program.resume(FAIL).join();
        assertEquals("place_block",tool(program.resume(OK).join().getAsJsonObject()));
        assertEquals(1,searches.get(),"No geometry changed between native fallback attempts");
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
    @Test void towerIntentIsJournaledBeforeJumpAndPlaceDispatch() {
        var saved=new java.util.concurrent.CompletableFuture<Void>();
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return false;}
            public List<AccessRepairSearch.Edit> repairPlan(List<BlueprintConstructionProgram.Cell> cells,boolean accessOnly){
                return List.of(new AccessRepairSearch.Edit(new ConstructionEscape.Position(0,1,0),"minecraft:air","minecraft:dirt",3,null,true));
            }
            public java.util.concurrent.CompletableFuture<Void> persistDebt(Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debts){
                return debts.isEmpty()?java.util.concurrent.CompletableFuture.completedFuture(null):saved;
            }
        };
        var program=new BlueprintConstructionProgram(List.of(cell(0)),world);
        program.resume(JsonNull.INSTANCE).join();
        var pending=program.resume(OK);assertFalse(pending.isDone());
        saved.complete(null);var step=pending.join().getAsJsonObject();
        assertEquals("place_block",tool(step));
        assertTrue(step.getAsJsonObject("value").getAsJsonObject("arguments").getAsJsonObject("args").get("tower").getAsBoolean());
        assertEquals("minecraft:air",program.progress().getAsJsonArray("repairDebt").get(0).getAsJsonObject().get("state").getAsString());
    }
    @Test void stalledConstructionCanDismantleScaffoldBeforeHouseIsComplete() {
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return !states.containsKey(1);}
            public boolean immediatelyPlaceable(BlueprintConstructionProgram.Cell c){return eligible(c);}
            public Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> restoredDebt(){return Map.of(new ConstructionEscape.Position(0,1,0),new ConstructionRepairLedger.Debt("minecraft:air","temporary_access","minecraft:dirt"));}
            public List<AccessRepairSearch.Edit> cleanupPlan(List<BlueprintConstructionProgram.Cell> cells,Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debts){return List.of(new AccessRepairSearch.Edit(new ConstructionEscape.Position(0,1,0),"minecraft:dirt","minecraft:air",1));}
        };
        world.states.put(1,"minecraft:dirt");
        var program=new BlueprintConstructionProgram(List.of(cell(0)),world);
        assertEquals("inspect_world",tool(program.resume(JsonNull.INSTANCE).join().getAsJsonObject()));
        assertEquals("break_blocks",tool(program.resume(OK).join().getAsJsonObject()));
        world.states.remove(1);
        assertEquals("inspect_world",tool(program.resume(OK).join().getAsJsonObject()));
        assertEquals(1,program.progress().get("scaffoldsRemoved").getAsInt());
        assertEquals("place_block",tool(program.resume(OK).join().getAsJsonObject()));
        world.states.put(0,"minecraft:stone");
        assertTrue(program.resume(OK).join().getAsJsonObject().get("done").getAsBoolean());
    }
    @Test void confirmedCleanupIsPersistedEvenWhenNextTargetCannotBePlanned() {
        var saved=new ArrayList<Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt>>();
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return false;}
            public Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> restoredDebt(){return Map.of(new ConstructionEscape.Position(0,1,0),new ConstructionRepairLedger.Debt("minecraft:air","scaffold","minecraft:dirt"));}
            public java.util.concurrent.CompletableFuture<Void> persistDebt(Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debt){saved.add(Map.copyOf(debt));return java.util.concurrent.CompletableFuture.completedFuture(null);}
            public List<AccessRepairSearch.Edit> cleanupPlan(List<BlueprintConstructionProgram.Cell> cells,Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debts){return debts.isEmpty()?List.of():List.of(new AccessRepairSearch.Edit(new ConstructionEscape.Position(0,1,0),"minecraft:dirt","minecraft:air",1));}
        };
        world.states.put(1,"minecraft:dirt");
        var program=new BlueprintConstructionProgram(List.of(cell(0)),world);
        program.resume(JsonNull.INSTANCE).join();program.resume(OK).join();
        world.states.remove(1);
        assertThrows(RuntimeException.class,()->program.resume(OK).join());
        assertTrue(saved.get(saved.size()-1).isEmpty());
    }
    @Test void finishesByTogglingDoorAndVerifiesBothHalves() {
        var lower=new BlueprintConstructionProgram.Cell(0,0,0,"door[half=lower,open=false]","door","entry");
        var upper=new BlueprintConstructionProgram.Cell(0,1,0,"door[half=upper,open=false]","door","entry");
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return false;}
            public boolean canToggle(BlueprintConstructionProgram.Cell c){return state(c).contains("open=true");}
        };
        world.states.put(0,"door[half=lower,open=true]");world.states.put(1,"door[half=upper,open=true]");
        var program=new BlueprintConstructionProgram(List.of(lower,upper),world);
        assertEquals("inspect_world",tool(program.resume(JsonNull.INSTANCE).join().getAsJsonObject()));
        assertEquals("use_block",tool(program.resume(OK).join().getAsJsonObject()));
        world.states.put(0,lower.state());world.states.put(1,upper.state());
        assertTrue(program.resume(OK).join().getAsJsonObject().get("done").getAsBoolean());
        assertEquals(1,program.progress().get("adjusted").getAsInt());
    }
    @Test void cleanupOfOwnedBlocksIsNotLimitedByAccessSearchBudget() {
        var world=new World(){
            public Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> restoredDebt(){
                var debts=new LinkedHashMap<ConstructionEscape.Position,ConstructionRepairLedger.Debt>();
                for(int y=1;y<=40;y++)debts.put(new ConstructionEscape.Position(0,y,0),new ConstructionRepairLedger.Debt("minecraft:air","scaffold","minecraft:dirt"));
                return debts;
            }
            public List<AccessRepairSearch.Edit> cleanupPlan(List<BlueprintConstructionProgram.Cell> cells,Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debts){
                return debts.keySet().stream().limit(1).map(p->new AccessRepairSearch.Edit(p,"minecraft:dirt","minecraft:air",1)).toList();
            }
        };
        world.states.put(0,"minecraft:stone");for(int y=1;y<=40;y++)world.states.put(y,"minecraft:dirt");
        var program=new BlueprintConstructionProgram(List.of(cell(0)),world);
        var step=program.resume(JsonNull.INSTANCE).join().getAsJsonObject();
        for(int n=0;n<40;n++){
            assertEquals("inspect_world",tool(step));
            var effect=program.resume(OK).join().getAsJsonObject();assertEquals("break_blocks",tool(effect));
            int y=effect.getAsJsonObject("value").getAsJsonObject("arguments").getAsJsonObject("args").getAsJsonArray("targets").get(0).getAsJsonObject().get("y").getAsInt();
            world.states.remove(y);step=program.resume(OK).join().getAsJsonObject();
        }
        assertTrue(step.get("done").getAsBoolean());
        assertEquals(40,program.progress().get("scaffoldsRemoved").getAsInt());
    }
    @Test void failedCleanupIsNotRepeatedWithoutGeometryProgress() {
        var p=new ConstructionEscape.Position(0,1,0);
        var world=new World(){
            public Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> restoredDebt(){return Map.of(p,new ConstructionRepairLedger.Debt("minecraft:air","scaffold","minecraft:dirt"));}
            public List<AccessRepairSearch.Edit> cleanupPlan(List<BlueprintConstructionProgram.Cell> cells,Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debts){return debts.containsKey(p)?List.of(new AccessRepairSearch.Edit(p,"minecraft:dirt","minecraft:air",1)):List.of();}
        };
        world.states.put(0,"minecraft:stone");world.states.put(1,"minecraft:dirt");
        var program=new BlueprintConstructionProgram(List.of(cell(0)),world);
        program.resume(JsonNull.INSTANCE).join();program.resume(OK).join();
        assertThrows(RuntimeException.class,()->program.resume(FAIL).join());
        assertEquals(1,program.progress().get("failedAttempts").getAsInt());
        assertEquals("minecraft:dirt",program.progress().getAsJsonArray("repairDebt").get(0).getAsJsonObject().get("intermediateState").getAsString());
    }
    @Test void cleanupMakesDeferredConstructionRetryableWithoutAnotherFinalPlacement() {
        var p=new ConstructionEscape.Position(0,1,0);
        var world=new World(){
            public Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> restoredDebt(){return Map.of(p,new ConstructionRepairLedger.Debt("minecraft:air","scaffold","minecraft:dirt"));}
            public List<AccessRepairSearch.Edit> cleanupPlan(List<BlueprintConstructionProgram.Cell> cells,Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debts){return debts.containsKey(p)?List.of(new AccessRepairSearch.Edit(p,"minecraft:dirt","minecraft:air",1)):List.of();}
        };
        world.states.put(1,"minecraft:dirt");
        var program=new BlueprintConstructionProgram(List.of(cell(0)),world);
        program.resume(JsonNull.INSTANCE).join();program.resume(OK).join();
        assertEquals("inspect_world",tool(program.resume(FAIL).join().getAsJsonObject()));
        assertEquals("break_blocks",tool(program.resume(OK).join().getAsJsonObject()));
        world.states.remove(1);
        assertEquals("inspect_world",tool(program.resume(OK).join().getAsJsonObject()));
        assertEquals("place_block",tool(program.resume(OK).join().getAsJsonObject()));
        world.states.put(0,"minecraft:stone");
        assertTrue(program.resume(OK).join().getAsJsonObject().get("done").getAsBoolean());
    }
    @Test void pendingDoorAdjustmentDoesNotTriggerAccessSearchDuringScaffoldCleanup(){
        var searches=new java.util.concurrent.atomic.AtomicInteger();
        var world=new World(){
            public boolean eligible(BlueprintConstructionProgram.Cell c){return false;}
            public boolean canToggle(BlueprintConstructionProgram.Cell c){return c.y()==0;}
            public Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> restoredDebt(){return Map.of(new ConstructionEscape.Position(0,1,0),new ConstructionRepairLedger.Debt("minecraft:air","temporary_access","minecraft:dirt"));}
            public List<AccessRepairSearch.Edit> repairPlan(List<BlueprintConstructionProgram.Cell> cells,boolean accessOnly){if(!accessOnly)searches.incrementAndGet();return List.of();}
            public List<AccessRepairSearch.Edit> cleanupPlan(List<BlueprintConstructionProgram.Cell> cells,Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debts){return List.of(new AccessRepairSearch.Edit(new ConstructionEscape.Position(0,1,0),"minecraft:dirt","minecraft:air",1));}
        };
        world.states.put(0,"minecraft:oak_door[open=true]");world.states.put(1,"minecraft:dirt");
        var door=new BlueprintConstructionProgram.Cell(0,0,0,"minecraft:oak_door[open=false]","minecraft:oak_door","entry");
        var program=new BlueprintConstructionProgram(List.of(door),world);
        assertEquals("inspect_world",tool(program.resume(JsonNull.INSTANCE).join().getAsJsonObject()));
        assertEquals(0,searches.get(),"Closing an existing door requires no construction access search");
        assertEquals("break_blocks",tool(program.resume(OK).join().getAsJsonObject()));
        world.states.remove(1);
        assertEquals("inspect_world",tool(program.resume(OK).join().getAsJsonObject()));
        assertEquals("use_block",tool(program.resume(OK).join().getAsJsonObject()));
        world.states.put(0,door.state());
        assertTrue(program.resume(OK).join().getAsJsonObject().get("done").getAsBoolean());
        assertEquals(0,searches.get());
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
