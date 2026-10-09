package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TowerPlacementPolicyTest {
    @Test void waitsForFeetToClearTheWholeBlockBeforePlacing() {
        assertEquals(TowerPlacementPolicy.Action.JUMP, TowerPlacementPolicy.next(.05, 64, 64, true, true));
        assertEquals(TowerPlacementPolicy.Action.WAIT, TowerPlacementPolicy.next(.05, 64.9, 64, false, true));
        assertEquals(TowerPlacementPolicy.Action.PLACE, TowerPlacementPolicy.next(.05, 65.01, 64, false, true));
    }
    @Test void centresOnGroundAndRejectsObstructedOrWrongLevelLaunches() {
        assertEquals(TowerPlacementPolicy.Action.CENTER, TowerPlacementPolicy.next(.35,64,64,true,true));
        assertEquals(TowerPlacementPolicy.Action.FAIL, TowerPlacementPolicy.next(.05,64,64,true,false));
        assertEquals(TowerPlacementPolicy.Action.FAIL, TowerPlacementPolicy.next(.05,63,64,true,true));
        assertEquals(TowerPlacementPolicy.Action.FAIL, TowerPlacementPolicy.next(.4,65.01,64,false,true));
    }
}
