package ai.moeru.airicraft.blueprint;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FailedRepairApproachesTest {
    @Test void failedStanceDoesNotExcludeAnotherApproachOrDependOnTravelCost() {
        var failures = new FailedRepairApproaches();
        var target = new ConstructionEscape.Position(370,-59,32);
        var stance = new ConstructionEscape.Position(370,-60,30);
        failures.reject(new AccessRepairSearch.Edit(target,"minecraft:oak_planks","minecraft:air",8,stance));
        assertFalse(failures.allows(new AccessRepairSearch.Edit(target,"minecraft:oak_planks","minecraft:air",12,stance)));
        assertTrue(failures.allows(new AccessRepairSearch.Edit(target,"minecraft:oak_planks","minecraft:air",8,
            new ConstructionEscape.Position(371,-60,30))));
        assertFalse(failures.allows(target,"minecraft:oak_planks","minecraft:air",stance,false));
        failures.clear();
        assertTrue(failures.allows(new AccessRepairSearch.Edit(target,"minecraft:oak_planks","minecraft:air",8,stance)));
    }
}
