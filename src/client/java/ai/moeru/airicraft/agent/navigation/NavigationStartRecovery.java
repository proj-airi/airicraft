package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.navigation.BodyState;
import ai.moeru.airicraft.navigation.GridPos;
import ai.moeru.airicraft.navigation.MotorIntent;
import net.minecraft.world.phys.AABB;
import java.util.Optional;
import java.util.function.Predicate;

/** A half-step onto the centre of the supporting stair before grid path search. */
final class NavigationStartRecovery {
    private NavigationStartRecovery() { }
    static Optional<MotorIntent> intent(BodyState body,AABB actual,Predicate<GridPos> valid,Predicate<AABB> clear){
        if(!body.onGround() || body.inWater() || body.climbing() || valid.test(body.feet()))return Optional.empty();
        var top=body.feet().offset(0,1,0);
        double rise=top.y()-body.y();
        if(rise<=0 || rise>.6 || !valid.test(top))return Optional.empty();
        var raised=new AABB(actual.minX,actual.minY+.001,actual.minZ,actual.maxX,actual.maxY,actual.maxZ);
        var lifted=raised.move(0,rise,0);
        var destination=new AABB(top.x()+.2,top.y()+.001,top.z()+.2,top.x()+.8,top.y()+actual.getYsize(),top.z()+.8);
        if(!clear.test(raised.minmax(lifted)) || !clear.test(lifted.minmax(destination)))return Optional.empty();
        double dx=top.x()+.5-body.x(),dz=top.z()+.5-body.z(),distance=Math.hypot(dx,dz);
        if(distance<.025)return Optional.empty();
        return Optional.of(new MotorIntent(dx/distance,dz/distance,false,false,false,
            new MotorIntent.Point(top.x()+.5,body.y()+1.62,top.z()+.5),null));
    }
}
