package ai.moeru.airicraft.agent.llm;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class StandingGeometryTest {
	@Test void partialSupportHasExplicitFeetAndFloorHeights() {
		var slab = new AABB(0,63,0,1,63.5,1);
		var result = StandingGeometry.assess(.5,63.5,.5,List.of(slab),true);
		assertTrue(result.standable()); assertEquals(63,result.floorBlockY()); assertEquals(63.5,result.supportSurfaceY());
		assertFalse(StandingGeometry.assess(.5,64,.5,List.of(slab),true).standable());
	}
	@Test void unsupportedObstructedAndUnloadedAreDifferentFacts() {
		assertEquals("unsupported",StandingGeometry.assess(.5,64,.5,List.of(),true).support());
		var result = StandingGeometry.assess(.5,64,.5,List.of(new AABB(0,63,0,1,64,1),new AABB(0,65,0,1,66,1)),true);
		assertEquals("clear",result.feetClearance()); assertEquals("obstructed",result.headClearance());
		assertEquals(65.,result.roofBottomY());
		assertEquals("unknown",StandingGeometry.assess(.5,64,.5,List.of(),false).support());
	}
}
