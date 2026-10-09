package ai.moeru.airicraft.agent.tasks;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Candidate face points; callers must still raycast and predict placement state. */
public final class PlacementSupportPoints {
    private PlacementSupportPoints() {}
    public static List<Vec3> face(BlockPos support,Direction face,VoxelShape shape){
        var points=new LinkedHashSet<Vec3>();
        findFirst(support,face,shape,p->{points.add(p);return false;});
        return List.copyOf(points);
    }
    /** Generates only as far as the first usable point. The predicate must be side-effect free. */
    public static Optional<Vec3> findFirst(BlockPos support,Direction face,VoxelShape shape,Predicate<Vec3> usable){
        if(shape.isEmpty())return Optional.empty();
        for(var box:shape.toAabbs()){
            double x=(box.minX+box.maxX)/2,y=(box.minY+box.maxY)/2,z=(box.minZ+box.maxZ)/2;
            switch(face){case EAST->x=box.maxX;case WEST->x=box.minX;case UP->y=box.maxY;case DOWN->y=box.minY;case SOUTH->z=box.maxZ;case NORTH->z=box.minZ;}
            var center=new Vec3(support.getX()+x,support.getY()+y,support.getZ()+z);
            for(double a:OFFSETS)for(double b:OFFSETS){
                var p=switch(face.getAxis()){
                    case X->center.add(0,a*(box.maxY-box.minY),b*(box.maxZ-box.minZ));
                    case Y->center.add(a*(box.maxX-box.minX),0,b*(box.maxZ-box.minZ));
                    case Z->center.add(a*(box.maxX-box.minX),b*(box.maxY-box.minY),0);
                };
                if(usable.test(p))return Optional.of(p);
            }
        }
        return Optional.empty();
    }
    private static final double[] OFFSETS={0,-.4,.4};
}
