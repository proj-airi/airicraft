package ai.moeru.airicraft.blueprint;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Queries live collision boxes with the expected placement state, including paired block parts. */
public final class MinecraftConstructionEscape {
    private MinecraftConstructionEscape() { }
    private static AABB body(ConstructionEscape.Position p,double y) {
        return new AABB(p.x()+.2,y+.001,p.z()+.2,p.x()+.8,y+1.8,p.z()+.8);
    }
    static Optional<ConstructionEscape.Position> projectStandingBody(AABB actual,
        java.util.function.Predicate<ConstructionEscape.Position> standable,
        java.util.function.Predicate<AABB> clear) {
        var candidates=new ArrayList<ConstructionEscape.Position>();
        // The caller verifies actual support. Connect a half-height stair/slab
        // surface to an overlapping grid stance with ordinary stepping clearance.
        for(int y=(int)Math.floor(actual.minY);y<=Math.ceil(actual.minY);y++){
            if(Math.abs(y-actual.minY)>.600001)continue;
            for(int x=(int)Math.floor(actual.minX);x<=Math.floor(actual.maxX-1e-6);x++)
                for(int z=(int)Math.floor(actual.minZ);z<=Math.floor(actual.maxZ-1e-6);z++)
                    candidates.add(new ConstructionEscape.Position(x,y,z));
        }
        double cx=(actual.minX+actual.maxX)/2,cz=(actual.minZ+actual.maxZ)/2;
        var raised=new AABB(actual.minX,actual.minY+.001,actual.minZ,actual.maxX,actual.maxY,actual.maxZ);
        return candidates.stream().sorted(Comparator.comparingDouble(p->Math.pow(p.x()+.5-cx,2)+Math.pow(p.z()+.5-cz,2)+Math.pow(p.y()-actual.minY,2)))
            .filter(standable).filter(p->{
                double high=Math.max(actual.minY,p.y());
                var lifted=raised.move(0,high-actual.minY,0);
                return clear.test(raised.minmax(lifted))
                    && clear.test(lifted.minmax(body(p,high)))
                    && clear.test(body(p,high).minmax(body(p,p.y())));
            }).findFirst();
    }
    static boolean supportsBody(AABB actual,AABB support){
        return Math.abs(support.maxY-actual.minY)<.001 && support.maxX>actual.minX && support.minX<actual.maxX
            && support.maxZ>actual.minZ && support.minZ<actual.maxZ;
    }
    static List<AABB> movementSweeps(ConstructionEscape.Position a,ConstructionEscape.Position b) {
        int high=Math.max(a.y(),b.y());
        return List.of(body(a,high).minmax(body(b,high)), body(b,high).minmax(body(b,b.y())));
    }
    static boolean passable(AABB sweep,List<AABB> current,List<AABB> toggled,boolean canToggle) {
        return current.stream().noneMatch(sweep::intersects) || (canToggle && toggled.stream().noneMatch(sweep::intersects));
    }
    public static boolean permits(Minecraft client,BlockPos target,ConstructionEscape.Bounds bounds,net.minecraft.world.level.block.state.BlockState state) {
        return canEscape(client,bounds,placementOverlay(target,state));
    }
    public static boolean permitsFrom(Minecraft client,BlockPos target,ConstructionEscape.Bounds bounds,
        net.minecraft.world.level.block.state.BlockState state,BlockPos stance) {
        return explore(client,bounds,placementOverlay(target,state),
            new ConstructionEscape.Position(stance.getX(),stance.getY(),stance.getZ()),false).escaped();
    }
    static Map<BlockPos,net.minecraft.world.level.block.state.BlockState> placementOverlay(BlockPos target,net.minecraft.world.level.block.state.BlockState state) {
        var overlay=new HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        var placed=state==null?net.minecraft.world.level.block.Blocks.STONE.defaultBlockState():state;
        overlay.put(target,placed);
        var halfProperty=net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF;
        if(placed.hasProperty(halfProperty)){
            var lower=placed.getValue(halfProperty)==net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER;
            overlay.put(lower?target.above():target.below(),placed.setValue(halfProperty,lower?
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER:net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
        }
        if(placed.getBlock() instanceof net.minecraft.world.level.block.BedBlock){
            var foot=placed.getValue(net.minecraft.world.level.block.BedBlock.PART)==net.minecraft.world.level.block.state.properties.BedPart.FOOT;
            var facing=placed.getValue(net.minecraft.world.level.block.BedBlock.FACING);
            overlay.put(target.relative(foot?facing:facing.getOpposite()),placed.setValue(net.minecraft.world.level.block.BedBlock.PART,foot?
                net.minecraft.world.level.block.state.properties.BedPart.HEAD:net.minecraft.world.level.block.state.properties.BedPart.FOOT));
        }
        return Map.copyOf(overlay);
    }
    public static boolean canEscape(Minecraft client,ConstructionEscape.Bounds bounds,Map<BlockPos,net.minecraft.world.level.block.state.BlockState> overlay) {
        return explore(client,bounds,overlay).escaped();
    }
    public static ConstructionEscape.Reachability explore(Minecraft client,ConstructionEscape.Bounds bounds,Map<BlockPos,net.minecraft.world.level.block.state.BlockState> overlay) {
        return explore(client,bounds,overlay,null,false);
    }
    public static ConstructionEscape.Reachability explore(Minecraft client,ConstructionEscape.Bounds bounds,Map<BlockPos,net.minecraft.world.level.block.state.BlockState> overlay,ConstructionEscape.Position start,boolean complete) {
        if(client.level==null || client.player==null)return new ConstructionEscape.Reachability(false,Set.of());
        return explore(client.level,client.level::hasChunkAt,client.player.getBoundingBox(),bounds,overlay,start,complete);
    }
    static ConstructionEscape.Reachability explore(net.minecraft.world.level.BlockGetter world,
        java.util.function.Predicate<BlockPos> loaded,AABB playerBody,ConstructionEscape.Bounds bounds,
        Map<BlockPos,net.minecraft.world.level.block.state.BlockState> overlay,ConstructionEscape.Position start,boolean complete) {
        var cache=new HashMap<BlockPos,List<AABB>>();
        class Geometry implements ConstructionEscape.Geometry {
            List<AABB> shapes(BlockPos pos) {
                return cache.computeIfAbsent(pos.immutable(),p->{
                    if(!loaded.test(p) || !world.getFluidState(p).isEmpty()
                       )return List.of(new AABB(p));
                    return overlay.getOrDefault(p,world.getBlockState(p)).getCollisionShape(world,p).toAabbs().stream().map(b->b.move(p)).toList();
                });
            }
            boolean clear(AABB box) {
                for(int x=(int)Math.floor(box.minX);x<=Math.floor(box.maxX);x++)
                    for(int y=(int)Math.floor(box.minY);y<=Math.floor(box.maxY);y++)
                        for(int z=(int)Math.floor(box.minZ);z<=Math.floor(box.maxZ);z++) {
                            var pos=new BlockPos(x,y,z);var current=shapes(pos);
                            if(current.stream().noneMatch(box::intersects))continue;
                            var state=overlay.getOrDefault(pos,world.getBlockState(pos));
                            boolean toggle=loaded.test(pos) && world.getFluidState(pos).isEmpty()
                                && state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock door && door.type().canOpenByHand();
                            var alternative=toggle?state.cycle(net.minecraft.world.level.block.DoorBlock.OPEN).getCollisionShape(world,pos).toAabbs().stream().map(b->b.move(pos)).toList():List.<AABB>of();
                            if(!passable(box,current,alternative,toggle))return false;
                        }
                return true;
            }
            boolean supported(AABB actual){
                int y=(int)Math.floor(actual.minY-.001);
                for(int x=(int)Math.floor(actual.minX);x<=Math.floor(actual.maxX-1e-6);x++)
                    for(int z=(int)Math.floor(actual.minZ);z<=Math.floor(actual.maxZ-1e-6);z++)
                        for(var support:shapes(new BlockPos(x,y,z)))
                            if(supportsBody(actual,support))return true;
                return false;
            }
            public boolean standable(ConstructionEscape.Position p) {
                if(!clear(body(p,p.y())))return false;
                for(var support:shapes(new BlockPos(p.x(),p.y()-1,p.z())))
                    if(Math.abs(support.maxY-p.y())<.001 && support.maxX>p.x()+.2 && support.minX<p.x()+.8
                        && support.maxZ>p.z()+.2 && support.minZ<p.z()+.8)return true;
                return false;
            }
            public boolean traversable(ConstructionEscape.Position a,ConstructionEscape.Position b) {
                // Horizontal clearance at the higher elevation plus the complete descent shaft.
                // Checking only the raised sweep could incorrectly pass through an intermediate ledge.
                return movementSweeps(a,b).stream().allMatch(this::clear);
            }
        }
        var geometry=new Geometry();
        var initial=start;
        if(initial==null){
            if(!geometry.supported(playerBody))return new ConstructionEscape.Reachability(false,Set.of());
            initial=projectStandingBody(playerBody,geometry::standable,geometry::clear).orElse(null);
            if(initial==null)return new ConstructionEscape.Reachability(false,Set.of());
        }
        return complete?ConstructionEscape.component(initial,bounds,geometry):ConstructionEscape.explore(initial,bounds,geometry);
    }
}
