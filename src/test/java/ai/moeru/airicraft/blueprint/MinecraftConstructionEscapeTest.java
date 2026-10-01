package ai.moeru.airicraft.blueprint;

import net.minecraft.util.math.Box;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftConstructionEscapeTest {
    @Test void descendingSweepIncludesTheWholeLandingColumnWithoutIntersectingDepartureSupport() {
        var from=new ConstructionEscape.Position(0,3,0);
        var to=new ConstructionEscape.Position(1,0,0);
        var sweeps=MinecraftConstructionEscape.movementSweeps(from,to);
        assertTrue(sweeps.stream().anyMatch(b->b.intersects(new Box(1,1,0,2,2,1))));
        assertFalse(sweeps.stream().anyMatch(b->b.intersects(new Box(0,2,0,1,3,1))));
    }
    @Test void woodenDoorCanBeToggledOutOfWalkingSweepButIronDoorCannot() {
        var sweep=new Box(.2,0,-.5,.8,1.8,1.5);
        var closed=List.of(new Box(0,0,0,1,2,.1875));
        var open=List.of(new Box(0,0,0,.1875,2,1));
        assertTrue(MinecraftConstructionEscape.passable(sweep,closed,open,true));
        assertFalse(MinecraftConstructionEscape.passable(sweep,closed,open,false));
    }
    @Test void doorIsNotPassableWhenBothPanelOrientationsObstructTheSweep() {
        var sweep=new Box(-.5,0,-.5,1.5,1.8,1.5);
        assertFalse(MinecraftConstructionEscape.passable(sweep,List.of(new Box(0,0,0,1,2,.1875)),List.of(new Box(0,0,0,.1875,2,1)),true));
    }
}
