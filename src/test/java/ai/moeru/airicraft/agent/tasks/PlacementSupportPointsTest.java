package ai.moeru.airicraft.agent.tasks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlacementSupportPointsTest {
    @Test void bottomSlabTopIsAtHalfHeightAndSidePointsHitTheSlab(){
        var slab=Shapes.box(0,0,0,1,.5,1);
        var top=PlacementSupportPoints.face(BlockPos.ZERO,Direction.UP,slab);
        assertEquals(new Vec3(.5,.5,.5),top.getFirst());
        assertTrue(top.stream().allMatch(p->p.y==.5));
        var side=PlacementSupportPoints.face(BlockPos.ZERO,Direction.EAST,slab);
        assertEquals(new Vec3(1,.25,.5),side.getFirst());
        for(var p:side){
            var hit=slab.clip(p.add(2,0,0),p.add(-.001,0,0),BlockPos.ZERO);
            assertNotNull(hit);assertEquals(Direction.EAST,hit.getDirection());
        }
    }
    @Test void thinPostUsesItsOwnFaceAndEmptyShapeOffersNoClick(){
        var post=Shapes.box(.375,0,.375,.625,1,.625);
        assertEquals(new Vec3(.625,.5,.5),PlacementSupportPoints.face(BlockPos.ZERO,Direction.EAST,post).getFirst());
        assertTrue(PlacementSupportPoints.face(BlockPos.ZERO,Direction.UP,Shapes.empty()).isEmpty());
    }
    @Test void fullCubeRetainsNineOrderedFaceSamples(){
        var points=PlacementSupportPoints.face(new BlockPos(2,3,4),Direction.UP,Shapes.block());
        assertEquals(9,points.size());assertEquals(new Vec3(2.5,4,4.5),points.getFirst());
        assertTrue(points.contains(new Vec3(2.1,4,4.1)));
    }
    @Test void lazySearchKeepsSampleOrderForEveryFaceAndPartialShape() {
        var support = new BlockPos(4, 7, -3);
        var shapes = java.util.List.of(Shapes.block(), Shapes.empty(), Shapes.box(0,0,0,1,.5,1),
            Shapes.or(Shapes.box(.375,0,.375,.625,1,.625), Shapes.box(0,.5,.4375,1,.75,.5625)));
        for (var shape : shapes) for (var face : Direction.values()) {
            var points = PlacementSupportPoints.face(support, face, shape);
            for (var target : points) {
                assertEquals(java.util.Optional.of(target), PlacementSupportPoints.findFirst(support, face, shape, target::equals));
            }
            assertEquals(points.stream().findFirst(), PlacementSupportPoints.findFirst(support, face, shape, p -> true));
            assertTrue(PlacementSupportPoints.findFirst(support, face, shape, p -> false).isEmpty());
        }
    }

    private static volatile Object allocationSink;

    @Test void firstUsablePlacementDoesNotAllocateTheWholeFace() {
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        var allocations = (com.sun.management.ThreadMXBean) bean;
        org.junit.jupiter.api.Assumptions.assumeTrue(allocations.isThreadAllocatedMemorySupported());
        allocations.setThreadAllocatedMemoryEnabled(true);
        Runnable eager = () -> allocationSink = PlacementSupportPoints.face(BlockPos.ZERO, Direction.UP, Shapes.block())
            .stream().filter(p -> true).findFirst();
        Runnable placement = () -> allocationSink = BlockInteractionTaskExecutor.selectPlacementHitPoint(
            BlockPos.ZERO, Direction.UP, p -> true);
        for (int i = 0; i < 3000; i++) { eager.run(); placement.run(); }
        long thread = Thread.currentThread().threadId();
        long start = allocations.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 10000; i++) eager.run();
        long eagerBytes = allocations.getThreadAllocatedBytes(thread) - start;
        start = allocations.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 10000; i++) placement.run();
        long placementBytes = allocations.getThreadAllocatedBytes(thread) - start;
        System.out.println("Placement allocations for 10000 first-hit queries: eager=" + eagerBytes + " selected=" + placementBytes);
        assertTrue(placementBytes < eagerBytes * .75,
            "First-hit placement should avoid the unused face collection: eager=" + eagerBytes + " selected=" + placementBytes);
    }
}
