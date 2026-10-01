package ai.moeru.airicraft.blueprint;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionEscapeTest {
    private final ConstructionEscape.Bounds bounds=new ConstructionEscape.Bounds(0,0,0,2,3,2);
    private ConstructionEscape.Geometry corridor(Set<ConstructionEscape.Position> blocked) {
        return new ConstructionEscape.Geometry(){
            public boolean standable(ConstructionEscape.Position p){return p.y()==0 && p.z()==1 && !blocked.contains(p);}
            public boolean traversable(ConstructionEscape.Position a,ConstructionEscape.Position b){return true;}
        };
    }
    @Test void rejectsClosingTheOnlyExitButAllowsPlacementFromOutside() {
        var left=new ConstructionEscape.Position(0,0,1);var right=new ConstructionEscape.Position(2,0,1);
        assertTrue(ConstructionEscape.canEscape(new ConstructionEscape.Position(1,0,1),bounds,corridor(Set.of(left))));
        assertFalse(ConstructionEscape.canEscape(new ConstructionEscape.Position(1,0,1),bounds,corridor(Set.of(left,right))));
        assertTrue(ConstructionEscape.canEscape(new ConstructionEscape.Position(3,0,1),bounds,corridor(Set.of(left,right))));
    }
    @Test void doesNotTreatStandableButDisconnectedTilesAsAnExit() {
        var world=new ConstructionEscape.Geometry(){
            public boolean standable(ConstructionEscape.Position p){return true;}
            public boolean traversable(ConstructionEscape.Position a,ConstructionEscape.Position b){return false;}
        };
        assertFalse(ConstructionEscape.canEscape(new ConstructionEscape.Position(1,0,1),bounds,world));
    }
    @Test void canDescendThreeBlocksButCannotClimbBackUp() {
        var world=new ConstructionEscape.Geometry(){
            public boolean standable(ConstructionEscape.Position p){return p.z()==1 && (p.x()==1?p.y()==3:p.y()==0);}
            public boolean traversable(ConstructionEscape.Position a,ConstructionEscape.Position b){return true;}
        };
        assertTrue(ConstructionEscape.canEscape(new ConstructionEscape.Position(1,3,1),bounds,world));
        assertFalse(ConstructionEscape.component(new ConstructionEscape.Position(0,0,1),bounds,world).reached().contains(new ConstructionEscape.Position(1,3,1)));
    }
    @Test void rejectsDropsBeyondThreeBlocks() {
        var world=new ConstructionEscape.Geometry(){
            public boolean standable(ConstructionEscape.Position p){return p.z()==1 && (p.x()==1?p.y()==4:p.y()==0);}
            public boolean traversable(ConstructionEscape.Position a,ConstructionEscape.Position b){return true;}
        };
        assertFalse(ConstructionEscape.canEscape(new ConstructionEscape.Position(1,4,1),bounds,world));
    }
}
