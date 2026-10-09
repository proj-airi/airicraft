package ai.moeru.airicraft.blueprint;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Errors are the designer model's only feedback, so they must name the fix. */
class BlueprintDiagnosticsTest {
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
    @Test void floorBelowGroundNamesColumnAndHighestGround(){
        var tree=JsonParser.parseString("{\"id\":\"f\",\"at\":[0,2,0],\"foundation\":{\"size\":[2,1,1],\"material\":\"cobblestone\"}}").getAsJsonObject();
        var error=assertThrows(IllegalArgumentException.class,()->new Blueprint(tree,Map.of("0,0",1,"1,0",5)));
        assertTrue(error.getMessage().startsWith("terrain_intersects_floor"));
        assertTrue(error.getMessage().contains("column 1,0"),error.getMessage());
        assertTrue(error.getMessage().contains("highest ground under its footprint is y=5"),error.getMessage());
    }
    @Test void conflictSuggestsTheExactReplacesPath(){
        var tree=JsonParser.parseString("{\"id\":\"a\",\"children\":[{\"id\":\"wall\",\"volume\":{\"size\":[1,1,1],\"state\":\"minecraft:stone\"}},{\"id\":\"torch\",\"volume\":{\"size\":[1,1,1],\"state\":\"minecraft:torch\"}}]}").getAsJsonObject();
        var error=assertThrows(IllegalArgumentException.class,()->new Blueprint(tree,null));
        assertTrue(error.getMessage().contains("replaces:[\"a.wall\"]"),error.getMessage());
    }
    @Test void yieldingClearanceDoesNotConflictInEitherOrder(){
        String clear="{\"id\":\"clear\",\"yields\":true,\"volume\":{\"size\":[2,1,1],\"state\":\"minecraft:air\"}}";
        String wall="{\"id\":\"wall\",\"volume\":{\"size\":[1,1,1],\"state\":\"minecraft:stone\"}}";
        for(String children:new String[]{clear+","+wall,wall+","+clear}){
            var blueprint=new Blueprint(JsonParser.parseString("{\"id\":\"a\",\"children\":["+children+"]}").getAsJsonObject(),null);
            var origin=blueprint.cells.get(net.minecraft.core.BlockPos.ZERO);
            assertEquals("a.wall",origin.owner(),children);
            assertEquals("a.clear",blueprint.cells.get(new net.minecraft.core.BlockPos(1,0,0)).owner());
        }
    }
    @Test void obstaclesInsideTheBuiltVolumeAreClearedByTheProgram(){
        var tree=JsonParser.parseString("{\"id\":\"a\",\"volume\":{\"size\":[2,3,2],\"state\":\"minecraft:stone\"}}").getAsJsonObject();
        var obstacles=Map.of(
            "1,1",java.util.List.of("oak_log@1..2","oak_leaves@3..4"),   // inside; y above the roof peak (2) stays
            "2,0",java.util.List.of("oak_log@0..1"),                      // one block of walking room
            "5,5",java.util.List.of("oak_log@0..1"));                     // far away
        var blueprint=new Blueprint(tree,null,obstacles);
        assertTrue(blueprint.cells.get(new net.minecraft.core.BlockPos(1,1,1)).state().is(net.minecraft.world.level.block.Blocks.STONE),"existing cells win");
        var walking=blueprint.cells.get(new net.minecraft.core.BlockPos(2,0,0));
        assertNotNull(walking);assertTrue(walking.state().isAir());assertEquals(Blueprint.DERIVED_CLEARANCE,walking.owner());
        assertNull(blueprint.cells.get(new net.minecraft.core.BlockPos(5,0,5)));
        assertNull(blueprint.cells.get(new net.minecraft.core.BlockPos(1,3,1)),"canopy above the roof is left alone");
    }
    @Test void missingIdIsNamedNotANullPointer(){
        var tree=JsonParser.parseString("{\"id\":\"a\",\"children\":[{\"type\":\"WalkableArea\"}]}").getAsJsonObject();
        var error=assertThrows(IllegalArgumentException.class,()->new Blueprint(tree,null));
        assertTrue(error.getMessage().startsWith("missing_component_id"),error.getMessage());
        assertTrue(error.getMessage().contains("WalkableArea"),error.getMessage());
    }
    @Test void paneConnectionsAreDerivedButOtherStateIsNot(){
        var pane=net.minecraft.world.level.block.Blocks.GLASS_PANE.defaultBlockState();
        var connected=pane.setValue(net.minecraft.world.level.block.IronBarsBlock.EAST,true).setValue(net.minecraft.world.level.block.IronBarsBlock.WEST,true);
        assertTrue(BlueprintIntegrity.statesMatch(pane,connected));
        assertFalse(BlueprintIntegrity.statesMatch(pane,connected.setValue(net.minecraft.world.level.block.IronBarsBlock.WATERLOGGED,true)));
        var stairs=net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState();
        assertFalse(BlueprintIntegrity.statesMatch(stairs,stairs.setValue(net.minecraft.world.level.block.StairBlock.FACING,net.minecraft.core.Direction.EAST)));
        assertTrue(BlueprintIntegrity.statesMatch(stairs,stairs));
    }
}
