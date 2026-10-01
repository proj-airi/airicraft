package ai.moeru.airicraft.blueprint;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import java.util.*;

/** Queries live collision boxes, with a conservative solid overlay for the proposed placement. */
public final class MinecraftConstructionEscape {
    private MinecraftConstructionEscape() { }
    private static Box body(ConstructionEscape.Position p,int y) {
        return new Box(p.x()+.2,y+.001,p.z()+.2,p.x()+.8,y+1.8,p.z()+.8);
    }
    static List<Box> movementSweeps(ConstructionEscape.Position a,ConstructionEscape.Position b) {
        int high=Math.max(a.y(),b.y());
        return List.of(body(a,high).union(body(b,high)), body(b,high).union(body(b,b.y())));
    }
    static boolean passable(Box sweep,List<Box> current,List<Box> toggled,boolean canToggle) {
        return current.stream().noneMatch(sweep::intersects) || (canToggle && toggled.stream().noneMatch(sweep::intersects));
    }
    public static boolean permits(MinecraftClient client,BlockPos target,ConstructionEscape.Bounds bounds,boolean doubleHeight) {
        var overlay=new HashMap<BlockPos,net.minecraft.block.BlockState>();
        overlay.put(target,net.minecraft.block.Blocks.STONE.getDefaultState());
        if(doubleHeight)overlay.put(target.up(),net.minecraft.block.Blocks.STONE.getDefaultState());
        return canEscape(client,bounds,overlay);
    }
    public static boolean canEscape(MinecraftClient client,ConstructionEscape.Bounds bounds,Map<BlockPos,net.minecraft.block.BlockState> overlay) {
        return explore(client,bounds,overlay).escaped();
    }
    public static ConstructionEscape.Reachability explore(MinecraftClient client,ConstructionEscape.Bounds bounds,Map<BlockPos,net.minecraft.block.BlockState> overlay) {
        return explore(client,bounds,overlay,null,false);
    }
    public static ConstructionEscape.Reachability explore(MinecraftClient client,ConstructionEscape.Bounds bounds,Map<BlockPos,net.minecraft.block.BlockState> overlay,ConstructionEscape.Position start,boolean complete) {
        if(client.world==null || client.player==null)return new ConstructionEscape.Reachability(false,Set.of());
        var world=client.world;
        var cache=new HashMap<BlockPos,List<Box>>();
        class Geometry implements ConstructionEscape.Geometry {
            List<Box> shapes(BlockPos pos) {
                return cache.computeIfAbsent(pos.toImmutable(),p->{
                    if(!world.isChunkLoaded(p) || !world.getFluidState(p).isEmpty()
                       )return List.of(new Box(p));
                    return overlay.getOrDefault(p,world.getBlockState(p)).getCollisionShape(world,p).getBoundingBoxes().stream().map(b->b.offset(p)).toList();
                });
            }
            boolean clear(Box box) {
                for(int x=(int)Math.floor(box.minX);x<=Math.floor(box.maxX);x++)
                    for(int y=(int)Math.floor(box.minY);y<=Math.floor(box.maxY);y++)
                        for(int z=(int)Math.floor(box.minZ);z<=Math.floor(box.maxZ);z++) {
                            var pos=new BlockPos(x,y,z);var current=shapes(pos);
                            if(current.stream().noneMatch(box::intersects))continue;
                            var state=overlay.getOrDefault(pos,world.getBlockState(pos));
                            boolean toggle=world.isChunkLoaded(pos) && world.getFluidState(pos).isEmpty()
                                && state.getBlock() instanceof net.minecraft.block.DoorBlock door && door.getBlockSetType().canOpenByHand();
                            var alternative=toggle?state.cycle(net.minecraft.block.DoorBlock.OPEN).getCollisionShape(world,pos).getBoundingBoxes().stream().map(b->b.offset(pos)).toList():List.<Box>of();
                            if(!passable(box,current,alternative,toggle))return false;
                        }
                return true;
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
        var feet=client.player.getBlockPos();
        var initial=start!=null?start:new ConstructionEscape.Position(feet.getX(),feet.getY(),feet.getZ());
        return complete?ConstructionEscape.component(initial,bounds,new Geometry()):ConstructionEscape.explore(initial,bounds,new Geometry());
    }
}
