package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.navigation.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavigationOptionsTest {
    @Test
    void placementRejectsReachablePrefixToAnUnreachableElevatedStance() {
        var terrain = new MapTerrain(CellInfo.AIR, new Box(-5, -1, -5, 15, 8, 5));
        var stone = CellInfo.builder("stone").full().breakTicks(30).build();
        for (int x=-5;x<=15;x++) for(int z=-5;z<=5;z++) terrain.set(x,-1,z,stone);
        // A nearby roof is standable, but has no climbable connection from the floor.
        terrain.set(8,3,0,stone);
        var partial = PathSearch.search(terrain, MovementPolicy.defaults().noEdits(), new GridPos(0,0,0),
            new Goal.Block(8,4,0), SearchBudget.defaults(), ()->false);
        assertInstanceOf(SearchResult.Partial.class, partial);
        assertTrue(NavigationOptions.DEFAULT.acceptsPath(partial));
        assertFalse(NavigationOptions.PLACEMENT_STANCE.acceptsPath(partial));
        var reachable = PathSearch.search(terrain, MovementPolicy.defaults().noEdits(), new GridPos(0,0,0),
            new Goal.Block(8,0,0), SearchBudget.defaults(), ()->false);
        assertInstanceOf(SearchResult.Found.class, reachable);
        assertTrue(NavigationOptions.PLACEMENT_STANCE.acceptsPath(reachable));
        assertFalse(NavigationOptions.PLACEMENT_STANCE.withWaterPenalty(5).acceptsPath(partial));
    }
}
