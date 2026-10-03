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
    @Test void missingIdIsNamedNotANullPointer(){
        var tree=JsonParser.parseString("{\"id\":\"a\",\"children\":[{\"type\":\"WalkableArea\"}]}").getAsJsonObject();
        var error=assertThrows(IllegalArgumentException.class,()->new Blueprint(tree,null));
        assertTrue(error.getMessage().startsWith("missing_component_id"),error.getMessage());
        assertTrue(error.getMessage().contains("WalkableArea"),error.getMessage());
    }
}
