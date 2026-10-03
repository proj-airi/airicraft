package ai.moeru.airicraft.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftScaffoldPlannerTest {
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
    @Test void diagonalStairSupportCandidatesIncludeTheMissingFaceAndStayBounded(){
        var target=new BlockPos(3,3,0);var stance=new BlockPos(2,3,-1);
        var supports=MinecraftScaffoldPlanner.extensionSupports(java.util.List.of(target,target),stance);
        assertTrue(supports.contains(target.north()));
        assertTrue(supports.contains(target.below()));
        assertFalse(supports.contains(target));
        assertEquals(supports.size(),new java.util.HashSet<>(supports).size());
        assertTrue(supports.size()<=16);
        assertTrue(MinecraftScaffoldPlanner.extensionSupports(java.util.List.of(new BlockPos(100,0,0)),stance).isEmpty());
    }
    @Test void towerOriginsReserveExteriorAccessWhenNearerCellsAreUnderTheDeck(){
        var bounds=new ConstructionEscape.Bounds(0,0,0,12,11,4);
        var candidates=new java.util.ArrayList<ConstructionEscape.Position>();
        for(int x=0;x<13;x++)for(int z=0;z<5;z++)candidates.add(new ConstructionEscape.Position(x,0,z));
        var outside=new ConstructionEscape.Position(13,0,2);candidates.add(outside);
        var selected=MinecraftScaffoldPlanner.towerOrigins(candidates,bounds,p->p.equals(outside)?100:0);
        assertEquals(24,selected.size());
        assertTrue(selected.contains(outside),"nearer interior candidates must not starve exterior access");
        assertEquals(selected.size(),new java.util.HashSet<>(selected).size());
    }
    @Test void cleanupFinishesNearbyColumnBeforeCrossingToHigherRemoteScaffold(){
        var near=new ConstructionEscape.Position(0,0,0);var far=new ConstructionEscape.Position(20,0,0);
        var low=new ConstructionEscape.Position(1,0,0);var top=new ConstructionEscape.Position(1,1,0);
        var remote=new ConstructionEscape.Position(21,3,0);
        var debts=new java.util.HashSet<>(java.util.Set.of(low,top,remote));
        var routes=java.util.Map.of(near,0,far,20);
        java.util.function.BiFunction<ConstructionEscape.Position,ConstructionEscape.Position,AccessRepairSearch.Edit> removal=(p,stance)->{
            if((p.x()<10)!=stance.equals(near))return null;
            return new AccessRepairSearch.Edit(p,"minecraft:dirt","minecraft:air",1,stance);
        };
        var first=MinecraftScaffoldPlanner.nearestCleanup(routes,debts,removal);
        assertEquals(top,first.position());debts.remove(top);
        assertEquals(low,MinecraftScaffoldPlanner.nearestCleanup(routes,debts,removal).position());
    }
    @Test void cleanupDoesNotUndercutUnreachableTopButCanRemoveOtherColumn(){
        var stance=new ConstructionEscape.Position(0,0,0);
        var low=new ConstructionEscape.Position(1,0,0);var top=new ConstructionEscape.Position(1,1,0);
        var other=new ConstructionEscape.Position(2,0,0);
        var result=MinecraftScaffoldPlanner.nearestCleanup(java.util.Map.of(stance,0),java.util.Set.of(low,top,other),
            (p,s)->p.equals(top)?null:new AccessRepairSearch.Edit(p,"minecraft:dirt","minecraft:air",1,s));
        assertEquals(other,result.position());
    }
    @Test void slabApproachRejectsClicksThatWouldCreateTheWrongHalf(){
        var p=BlockPos.ZERO;var eye=new Vec3(2,2,2);
        var bottom=Blocks.OAK_SLAB.defaultBlockState();
        assertFalse(MinecraftScaffoldPlanner.compatibleApproach(bottom,p,Direction.DOWN,eye,new Vec3(.5,1,.5)));
        assertFalse(MinecraftScaffoldPlanner.compatibleApproach(bottom,p,Direction.NORTH,eye,new Vec3(.5,.8,0)));
        assertTrue(MinecraftScaffoldPlanner.compatibleApproach(bottom,p,Direction.UP,eye,new Vec3(.5,0,.5)));
        var top=bottom.setValue(net.minecraft.world.level.block.SlabBlock.TYPE,net.minecraft.world.level.block.state.properties.SlabType.TOP);
        assertTrue(MinecraftScaffoldPlanner.compatibleApproach(top,p,Direction.DOWN,eye,new Vec3(.5,1,.5)));
        assertFalse(MinecraftScaffoldPlanner.compatibleApproach(top,p,Direction.UP,eye,new Vec3(.5,0,.5)));
    }
    @Test void disconnectedFenceCannotUseAnAdjacentFullScaffoldAsIfItsStateWereUnchanged(){
        var states=new java.util.HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        var world=new net.minecraft.world.level.BlockGetter(){
            public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos p){return states.getOrDefault(p,Blocks.AIR.defaultBlockState());}
            public net.minecraft.world.level.material.FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
            public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos p){return null;}
            public int getHeight(){return 384;}
            public int getMinY(){return -64;}
        };
        var fence=Blocks.OAK_FENCE.defaultBlockState();
        assertTrue(MinecraftScaffoldPlanner.compatibleNeighbours(fence,world,BlockPos.ZERO));
        states.put(BlockPos.ZERO.east(),Blocks.DIRT.defaultBlockState());
        assertFalse(MinecraftScaffoldPlanner.compatibleNeighbours(fence,world,BlockPos.ZERO));
        assertTrue(MinecraftScaffoldPlanner.compatibleNeighbours(fence.setValue(net.minecraft.world.level.block.FenceBlock.EAST,true),world,BlockPos.ZERO));
        states.clear();states.put(BlockPos.ZERO.below(),Blocks.DIRT.defaultBlockState());
        assertTrue(MinecraftScaffoldPlanner.compatibleNeighbours(fence,world,BlockPos.ZERO));
    }
    @Test void workPatchIncludesNewlySupportedNeighboursButStopsAtBlockedCells(){
        var placed=new java.util.HashSet<Integer>();
        // Reverse order forces a second pass once the first support is placed.
        assertEquals(3,MinecraftScaffoldPlanner.workPatchSize(java.util.List.of(3,2,1,4),
            n->n<=3 && (n==1 || placed.contains(n-1)),placed::add));
        assertEquals(java.util.Set.of(1,2,3),placed);
        assertEquals(0,MinecraftScaffoldPlanner.workPatchSize(java.util.List.of(5),n->false,placed::add));
    }
    @Test void towerRankingAmortizesConstructionAndCleanupAcrossAUsefulPatch(){
        assertTrue(MinecraftScaffoldPlanner.towerUtility(9,5,0)>MinecraftScaffoldPlanner.towerUtility(1,1,0));
        assertTrue(MinecraftScaffoldPlanner.towerUtility(9,5,0)>MinecraftScaffoldPlanner.towerUtility(9,6,0));
        assertTrue(MinecraftScaffoldPlanner.towerUtility(9,5,0)>MinecraftScaffoldPlanner.towerUtility(9,5,20));
        assertEquals(0,MinecraftScaffoldPlanner.towerUtility(0,1,0));
    }
    @Test void ownedDirtScaffoldMayGrowGrassWithoutLosingCleanupOwnership(){
        var debt=new ConstructionRepairLedger.Debt("minecraft:air","temporary_access","minecraft:dirt");
        assertTrue(MinecraftScaffoldPlanner.ownedScaffold(debt,Blocks.DIRT.defaultBlockState()));
        assertTrue(MinecraftScaffoldPlanner.ownedScaffold(debt,Blocks.GRASS_BLOCK.defaultBlockState()));
        assertTrue(MinecraftScaffoldPlanner.ownedScaffold(debt,Blocks.GRASS_BLOCK.defaultBlockState()
            .setValue(net.minecraft.world.level.block.SnowyDirtBlock.SNOWY,true)));
        assertFalse(MinecraftScaffoldPlanner.ownedScaffold(debt,Blocks.STONE.defaultBlockState()));
        assertFalse(MinecraftScaffoldPlanner.ownedScaffold(debt,Blocks.AIR.defaultBlockState()));
        assertFalse(MinecraftScaffoldPlanner.ownedScaffold(new ConstructionRepairLedger.Debt(
            "minecraft:stone","wall","minecraft:dirt"),Blocks.GRASS_BLOCK.defaultBlockState()));
        assertFalse(MinecraftScaffoldPlanner.ownedScaffold(new ConstructionRepairLedger.Debt(
            "minecraft:air","temporary_access","minecraft:oak_planks"),Blocks.GRASS_BLOCK.defaultBlockState()));
    }
    @Test void accessPlanningAcceptsDryReplaceableVegetationButNotFluidOrSolidObstacles(){
        assertTrue(MinecraftScaffoldPlanner.replaceableTarget(Blocks.AIR.defaultBlockState()));
        assertTrue(MinecraftScaffoldPlanner.replaceableTarget(Blocks.LEAF_LITTER.defaultBlockState()));
        assertTrue(MinecraftScaffoldPlanner.replaceableTarget(Blocks.SHORT_GRASS.defaultBlockState()));
        assertFalse(MinecraftScaffoldPlanner.replaceableTarget(Blocks.WATER.defaultBlockState()));
        assertFalse(MinecraftScaffoldPlanner.replaceableTarget(Blocks.STONE.defaultBlockState()));
    }
    @Test void scaffoldsCanProvideAccessToDirectionalAndNonSolidFinalBlocks(){
        assertTrue(MinecraftScaffoldPlanner.accessTarget(Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.SOUTH)));
        assertTrue(MinecraftScaffoldPlanner.accessTarget(Blocks.LANTERN.defaultBlockState()));
        assertTrue(MinecraftScaffoldPlanner.accessTarget(Blocks.OAK_DOOR.defaultBlockState()));
        assertFalse(MinecraftScaffoldPlanner.accessTarget(Blocks.OAK_DOOR.defaultBlockState().setValue(
            net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)));
        assertFalse(MinecraftScaffoldPlanner.accessTarget(Blocks.AIR.defaultBlockState()));
    }
    @Test void breakStanceAllowsForNavigationArrivalAnywhereInsideTheGoalCell(){
        var target=new BlockPos(368,-52,32);
        // Recorded navigation succeeded at z=36.864, rather than the nominal 36.5.
        var crouchedNominal=new Vec3(368.5,-49.73,36.5);
        assertTrue(MinecraftScaffoldPlanner.breakWithinReach(crouchedNominal,target));
        assertFalse(MinecraftScaffoldPlanner.breakStanceWithinReach(crouchedNominal,target));
        assertFalse(MinecraftScaffoldPlanner.breakWithinReach(new Vec3(368.555,-49.38,36.864),target));
        assertTrue(MinecraftScaffoldPlanner.breakStanceWithinReach(new Vec3(368.5,-49.38,35.5),target));
    }
    @Test void visibleSupportFromTheSideDoesNotProveTheRequiredStairFacing(){
        var target=new BlockPos(162,-59,33);
        var desired=Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.SOUTH);
        var topOfSupport=new Vec3(162.5,-59,33.5);
        assertFalse(MinecraftScaffoldPlanner.compatibleApproach(desired,target,Direction.UP,new Vec3(160.5,-57.38,32.5),topOfSupport));
        assertTrue(MinecraftScaffoldPlanner.compatibleApproach(desired,target,Direction.UP,new Vec3(162.5,-57.38,31.5),topOfSupport));
    }
    @Test void stairHalfFollowsClickedFaceAndHeight(){
        var target=BlockPos.ZERO;var eye=new Vec3(.5,2,-2);
        var bottom=Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.SOUTH).setValue(StairBlock.HALF,Half.BOTTOM);
        assertFalse(MinecraftScaffoldPlanner.compatibleApproach(bottom,target,Direction.DOWN,eye,new Vec3(.5,1,.5)));
        assertFalse(MinecraftScaffoldPlanner.compatibleApproach(bottom,target,Direction.NORTH,eye,new Vec3(.5,.75,0)));
        assertTrue(MinecraftScaffoldPlanner.compatibleApproach(bottom,target,Direction.NORTH,eye,new Vec3(.5,.25,0)));
        assertTrue(MinecraftScaffoldPlanner.compatibleApproach(bottom.setValue(StairBlock.HALF,Half.TOP),target,Direction.DOWN,eye,new Vec3(.5,1,.5)));
    }
    @Test void accessCutCanRequireWalkingAroundTheWallInsteadOfCuttingFromCurrentPosition(){
        var current=new ConstructionEscape.Position(159,-60,32);
        var around=new ConstructionEscape.Position(162,-60,31);
        var target=new BlockPos(162,-59,32);
        assertEquals(around,MinecraftScaffoldPlanner.reachableBreakStance(java.util.List.of(current,around),target,
            new Vec3(159.5,-60,32.5),1.62,around::equals).orElseThrow());
        assertTrue(MinecraftScaffoldPlanner.reachableBreakStance(java.util.List.of(current),target,
            new Vec3(159.5,-60,32.5),1.62,around::equals).isEmpty());
        var underfoot=new ConstructionEscape.Position(162,-58,32);
        assertTrue(MinecraftScaffoldPlanner.reachableBreakStance(java.util.List.of(underfoot),target,
            new Vec3(162.5,-58,32.5),1.62,p->true).isEmpty());
    }
    @Test void cleanupAccessRemovesStrandedBlockBeforeDescendingItsEntireNewTower(){
        var base=new ConstructionEscape.Position(10,0,0);
        var upper=new ConstructionEscape.Position(10,1,0);
        var top=new ConstructionEscape.Position(10,2,0);
        var stranded=new ConstructionEscape.Position(11,3,0);
        var tower=new MinecraftScaffoldPlanner.Plan(java.util.List.of(
            new AccessRepairSearch.Edit(base,"minecraft:air","minecraft:dirt",3,base,true),
            new AccessRepairSearch.Edit(upper,"minecraft:air","minecraft:dirt",3,upper,true)),top);
        var edits=MinecraftScaffoldPlanner.cleanupSequence(tower,stranded,"minecraft:oak_planks");
        var world=new java.util.HashMap<ConstructionEscape.Position,String>();
        world.put(stranded,"minecraft:oak_planks");
        var ledger=new ConstructionRepairLedger();
        ledger.restore(java.util.Map.of(stranded,new ConstructionRepairLedger.Debt("minecraft:air","temporary_access","minecraft:oak_planks")));
        for(var edit:edits){
            assertEquals(edit.before(),world.getOrDefault(edit.position(),"minecraft:air"));
            ledger.beforeEdit(edit.position(),"minecraft:air","temporary_access",edit.after());
            assertFalse(ledger.entries().isEmpty(),"ownership must exist before every effect");
            world.put(edit.position(),edit.after());
            ledger.reconcile(p->world.getOrDefault(p,"minecraft:air"));
        }
        assertEquals(stranded,edits.get(2).position());
        assertEquals(top,edits.get(2).stance());
        assertEquals(upper,edits.get(3).position());
        assertEquals(top,edits.get(3).stance());
        assertEquals(base,edits.get(4).position());
        assertEquals(upper,edits.get(4).stance());
        assertTrue(world.values().stream().allMatch("minecraft:air"::equals));
        assertTrue(ledger.entries().isEmpty());
    }
    @Test void cleanupTowerMustReachTheBreakExecutorsCentreDistanceGate(){
        var stranded=new BlockPos(163,-53,31);
        assertFalse(MinecraftScaffoldPlanner.breakWithinReach(new Vec3(163.5,-57.38,31.5),stranded));
        assertTrue(MinecraftScaffoldPlanner.breakWithinReach(new Vec3(163.5,-56.38,31.5),stranded));
    }
    @Test void trappedRoofSearchPrefersNearbyEscapeCutsOverUnfinishedRoofProximity(){
        var player=new Vec3(226.492987,-52,32.550947);
        var exitWall=new BlueprintConstructionProgram.Cell(226,-51,33,"minecraft:white_concrete","minecraft:white_concrete","gable");
        var distantRoof=new BlueprintConstructionProgram.Cell(228,-50,33,"minecraft:white_concrete","minecraft:white_concrete","gable");
        var pending=java.util.List.of(new BlueprintConstructionProgram.Cell(228,-49,33,"minecraft:dark_oak_planks","minecraft:dark_oak_planks","roof"));
        assertTrue(MinecraftBlueprintConstructionEnvironment.repairCandidateScore(exitWall,pending,player,false)
            <MinecraftBlueprintConstructionEnvironment.repairCandidateScore(distantRoof,pending,player,false));
        assertTrue(MinecraftBlueprintConstructionEnvironment.repairCandidateScore(distantRoof,pending,player,true)
            <MinecraftBlueprintConstructionEnvironment.repairCandidateScore(exitWall,pending,player,true));
    }
}
