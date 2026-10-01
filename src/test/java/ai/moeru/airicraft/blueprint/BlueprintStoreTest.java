package ai.moeru.airicraft.blueprint;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class BlueprintStoreTest {
    @TempDir Path directory;
    @Test void roundTripRetainsIndependentSemanticDesignsAndReplacement() throws Exception {
        var store=new BlueprintStore(directory.resolve("blueprints.json"));assertTrue(store.load().isEmpty());
        var state=JsonParser.parseString("{\"version\":1,\"commits\":[{\"id\":\"house\",\"tree\":{\"id\":\"house\",\"children\":[{\"id\":\"roof\"}]}},{\"id\":\"furniture\",\"source\":\"function design(input) {}\",\"origin\":[64,-60,32]}]}").getAsJsonObject();
        store.save(state);assertEquals(state,new BlueprintStore(directory.resolve("blueprints.json")).load());
        state.addProperty("revision",4);store.save(state);assertEquals(state,store.load());
    }
    @Test void corruptOrFutureVersionIsNotSilentlyReset() throws Exception {
        var path=directory.resolve("blueprints.json");var store=new BlueprintStore(path);
        Files.writeString(path,"broken");assertThrows(java.io.IOException.class,store::load);assertEquals("broken",Files.readString(path));
        Files.writeString(path,"{\"version\":2}");assertThrows(java.io.IOException.class,store::load);
    }
    @Test void missingAndChangedBlocksDifferButNeighborConnectionsDoNot(){
        assertFalse(BlueprintIntegrity.matches("minecraft:oak_planks","minecraft:air"));
        assertFalse(BlueprintIntegrity.matches("minecraft:oak_planks","minecraft:stone"));
        assertTrue(BlueprintIntegrity.matches("minecraft:oak_fence[north=false,waterlogged=false]","minecraft:oak_fence[north=true,waterlogged=false]"));
        assertFalse(BlueprintIntegrity.matches("minecraft:oak_fence[north=false,waterlogged=false]","minecraft:oak_fence[north=true,waterlogged=true]"));
        assertFalse(BlueprintIntegrity.matches("minecraft:oak_stairs[facing=north]","minecraft:oak_stairs[facing=south]"));
    }
}
