package ai.moeru.airicraft.blueprint;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftConstructionEscapeTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap(){
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
    }
    @Test void recordedStairCleanupPoseCanReachTheOpenFrontDoor() throws Exception {
        com.google.gson.JsonObject data;
        try(var in=getClass().getResourceAsStream("/blueprint/cleanup-stair-world.json")){
            data=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        var states=new java.util.HashMap<net.minecraft.core.BlockPos,net.minecraft.world.level.block.state.BlockState>();
        for(var entry:data.getAsJsonArray("blocks")){
            var a=entry.getAsJsonArray();states.put(new net.minecraft.core.BlockPos(a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt()),Blueprint.parseState(a.get(3).getAsString()));
        }
        var world=new net.minecraft.world.level.BlockGetter(){
            public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos p){return states.getOrDefault(p,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());}
            public net.minecraft.world.level.material.FluidState getFluidState(net.minecraft.core.BlockPos p){return getBlockState(p).getFluidState();}
            public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(net.minecraft.core.BlockPos p){return null;}
            public int getHeight(){return 384;}
            public int getMinY(){return -64;}
        };
        var b=data.getAsJsonArray("body");var body=new AABB(b.get(0).getAsDouble(),b.get(1).getAsDouble(),b.get(2).getAsDouble(),b.get(3).getAsDouble(),b.get(4).getAsDouble(),b.get(5).getAsDouble());
        java.util.function.Predicate<net.minecraft.core.BlockPos> loaded=p->p.getX()>=540 && p.getX()<=556 && p.getY()>=-62 && p.getY()<=-45 && p.getZ()>=28 && p.getZ()<=42;
        var bounds=new ConstructionEscape.Bounds(544,-60,32,552,-48,38);
        var result=MinecraftConstructionEscape.explore(world,loaded,body,bounds,java.util.Map.of(),null,true);
        assertTrue(result.escaped(),"Recorded doorway should remain reachable; reached="+result.reached());
    }
    @Test void proposedDoorKeepsBothToggleableHalvesInsteadOfSealingExitWithStone(){
        var pos=new net.minecraft.core.BlockPos(164,-59,32);
        var door=net.minecraft.world.level.block.Blocks.OAK_DOOR.defaultBlockState()
            .setValue(net.minecraft.world.level.block.DoorBlock.FACING,net.minecraft.core.Direction.NORTH);
        var overlay=MinecraftConstructionEscape.placementOverlay(pos,door);
        assertEquals(door,overlay.get(pos));
        assertEquals(door.setValue(net.minecraft.world.level.block.DoorBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),overlay.get(pos.above()));
        var sweep=new AABB(.2,0,-.5,.8,1.8,1.5);
        var lower=overlay.get(pos);
        assertTrue(MinecraftConstructionEscape.passable(sweep,
            lower.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,net.minecraft.core.BlockPos.ZERO).toAabbs(),
            lower.cycle(net.minecraft.world.level.block.DoorBlock.OPEN).getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,net.minecraft.core.BlockPos.ZERO).toAabbs(),true));
    }
    @Test void placementOverlayPreservesPartialShapeButFullBlocksRemainObstacles(){
        var pos=net.minecraft.core.BlockPos.ZERO;
        var stairs=net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState();
        assertEquals(stairs,MinecraftConstructionEscape.placementOverlay(pos,stairs).get(pos));
        var stone=net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        assertEquals(java.util.Map.of(pos,stone),MinecraftConstructionEscape.placementOverlay(pos,stone));
        assertEquals(java.util.Map.of(pos,stone),MinecraftConstructionEscape.placementOverlay(pos,null));
    }
    @Test void pairedBedOccupiesBothCellsInEscapePrediction(){
        var pos=net.minecraft.core.BlockPos.ZERO;
        var foot=net.minecraft.world.level.block.Blocks.RED_BED.defaultBlockState()
            .setValue(net.minecraft.world.level.block.BedBlock.FACING,net.minecraft.core.Direction.EAST)
            .setValue(net.minecraft.world.level.block.BedBlock.PART,net.minecraft.world.level.block.state.properties.BedPart.FOOT);
        var head=foot.setValue(net.minecraft.world.level.block.BedBlock.PART,net.minecraft.world.level.block.state.properties.BedPart.HEAD);
        var expected=java.util.Map.of(pos,foot,pos.east(),head);
        assertEquals(expected,MinecraftConstructionEscape.placementOverlay(pos,foot));
        assertEquals(expected,MinecraftConstructionEscape.placementOverlay(pos.east(),head));
    }
    @Test void projectsRecordedEdgeStandingPoseOntoItsSupportingCell() {
        var body=new AABB(136.72049264583788,-58,32.06208396163521,137.32049266967974,-56.200000047683716,32.66208398547707);
        var support=new ConstructionEscape.Position(136,-58,32);
        assertEquals(support,MinecraftConstructionEscape.projectStandingBody(body,support::equals,b->true).orElseThrow());
    }
    @Test void edgeProjectionCannotCrossAnObstructingWallOrChooseDistantSupport() {
        var body=new AABB(.72,0,.06,1.32,1.8,.66);
        var wall=new AABB(.6,0,0,.7,2,1);
        assertTrue(MinecraftConstructionEscape.projectStandingBody(body,p->p.x()==0,
            sweep->!sweep.intersects(wall)).isEmpty());
        assertTrue(MinecraftConstructionEscape.projectStandingBody(body,p->p.x()==2,sweep->true).isEmpty());
    }
    @Test void descendingSweepIncludesTheWholeLandingColumnWithoutIntersectingDepartureSupport() {
        var from=new ConstructionEscape.Position(0,3,0);
        var to=new ConstructionEscape.Position(1,0,0);
        var sweeps=MinecraftConstructionEscape.movementSweeps(from,to);
        assertTrue(sweeps.stream().anyMatch(b->b.intersects(new AABB(1,1,0,2,2,1))));
        assertFalse(sweeps.stream().anyMatch(b->b.intersects(new AABB(0,2,0,1,3,1))));
    }
    @Test void woodenDoorCanBeToggledOutOfWalkingSweepButIronDoorCannot() {
        var sweep=new AABB(.2,0,-.5,.8,1.8,1.5);
        var closed=List.of(new AABB(0,0,0,1,2,.1875));
        var open=List.of(new AABB(0,0,0,.1875,2,1));
        assertTrue(MinecraftConstructionEscape.passable(sweep,closed,open,true));
        assertFalse(MinecraftConstructionEscape.passable(sweep,closed,open,false));
    }
    @Test void doorIsNotPassableWhenBothPanelOrientationsObstructTheSweep() {
        var sweep=new AABB(-.5,0,-.5,1.5,1.8,1.5);
        assertFalse(MinecraftConstructionEscape.passable(sweep,List.of(new AABB(0,0,0,1,2,.1875)),List.of(new AABB(0,0,0,.1875,2,1)),true));
    }
    @Test void supportedHalfStepCanReachTheTopWithoutPretendingThePlayerIsTrapped(){
        var actual=new AABB(193.11842758,-56.5,34.44403443,193.71842758,-54.7,35.04403443);
        var top=new ConstructionEscape.Position(193,-56,35);
        var stairPos=new net.minecraft.core.BlockPos(193,-57,35);
        var stair=net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(net.minecraft.world.level.block.StairBlock.FACING,net.minecraft.core.Direction.SOUTH);
        var shapes=stair.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,stairPos)
            .toAabbs().stream().map(b->b.move(stairPos)).toList();
        assertTrue(shapes.stream().anyMatch(b->MinecraftConstructionEscape.supportsBody(actual,b)));
        assertEquals(top,MinecraftConstructionEscape.projectStandingBody(actual,top::equals,
            sweep->shapes.stream().noneMatch(sweep::intersects)).orElseThrow());
        var lowCeiling=new AABB(193,-54.65,34,194,-54,36);
        assertTrue(MinecraftConstructionEscape.projectStandingBody(actual,top::equals,b->!b.intersects(lowCeiling)).isEmpty());
    }
    @Test void projectionDoesNotStepAcrossAnUnjumpableRise(){
        var actual=new AABB(.2,.25,.2,.8,2.05,.8);
        assertTrue(MinecraftConstructionEscape.projectStandingBody(actual,p->p.y()==1,b->true).isEmpty());
    }
    @Test void fractionalProjectionRequiresActualSupportNotJustANearbyLanding(){
        var actual=new AABB(.2,.5,.2,.8,2.3,.8);
        assertTrue(MinecraftConstructionEscape.supportsBody(actual,new AABB(0,0,0,1,.5,1)));
        assertFalse(MinecraftConstructionEscape.supportsBody(actual,new AABB(0,-1,0,1,0,1)));
        assertFalse(MinecraftConstructionEscape.supportsBody(actual,new AABB(1,0,0,2,.5,1)));
    }
}
