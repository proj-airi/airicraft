package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.navigation.BodyState;
import ai.moeru.airicraft.navigation.GridPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavigationStartRecoveryTest {
    private final BodyState body=new BodyState(193.563033,-56.5,35.082375,0,true,false,false,false,1);
    private final AABB box=new AABB(193.263033,-56.5,34.782375,193.863033,-54.7,35.382375);
    private final GridPos top=new GridPos(193,-56,35);
    @Test void invalidStairCentreStepsOntoValidTopBeforeSearching(){
        var intent=NavigationStartRecovery.intent(body,box,top::equals,b->true).orElseThrow();
        assertTrue(intent.moveZ()>0);assertFalse(intent.jump());assertFalse(intent.sprint());assertNull(intent.action());
    }
    @Test void lowCeilingRejectsTheWholeStepUpSweep(){
        var ceiling=new AABB(193,-54.6,35,194,-54,36);
        assertTrue(NavigationStartRecovery.intent(body,box,top::equals,b->!b.intersects(ceiling)).isEmpty());
    }
    @Test void validStartsAndUnsupportedBodiesAreNeverRepositioned(){
        assertTrue(NavigationStartRecovery.intent(body,box,p->true,b->true).isEmpty());
        var airborne=new BodyState(body.x(),body.y(),body.z(),0,false,false,false,false,1);
        assertTrue(NavigationStartRecovery.intent(airborne,box,top::equals,b->true).isEmpty());
    }
    @Test void recoveryDoesNotJumpOntoAFullBlock(){
        var low=new BodyState(body.x(),-56.8,body.z(),0,true,false,false,false,1);
        assertTrue(NavigationStartRecovery.intent(low,box.move(0,-.3,0),top::equals,b->true).isEmpty());
    }
}
