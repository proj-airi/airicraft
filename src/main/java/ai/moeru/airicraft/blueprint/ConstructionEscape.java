package ai.moeru.airicraft.blueprint;

import java.util.*;

/** Bounded, conservative walking graph. Unknown geometry and exhausted searches fail closed. */
public final class ConstructionEscape {
    private ConstructionEscape() { }
    public record Position(int x,int y,int z) { }
    public record Bounds(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
        public Bounds {
            if(minX>maxX || minY>maxY || minZ>maxZ) throw new IllegalArgumentException("invalid_escape_bounds");
        }
        public boolean outside(Position p){return p.x()<minX || p.x()>maxX || p.z()<minZ || p.z()>maxZ;}
        boolean contains(Position p){return p.x()>=minX-1 && p.x()<=maxX+1 && p.z()>=minZ-1 && p.z()<=maxZ+1 && p.y()>=minY-2 && p.y()<=maxY+2;}
    }
    public interface Geometry {
        boolean standable(Position position);
        boolean traversable(Position from,Position to);
    }
    public record Reachability(boolean escaped,Set<Position> reached) { }
    public static boolean canEscape(Position start,Bounds bounds,Geometry geometry) {return explore(start,bounds,geometry).escaped();}
    public static Reachability explore(Position start,Bounds bounds,Geometry geometry) {return explore(start,bounds,geometry,false);}
    public static Reachability component(Position start,Bounds bounds,Geometry geometry) {return explore(start,bounds,geometry,true);}
    private static Reachability explore(Position start,Bounds bounds,Geometry geometry,boolean complete) {
        if(!geometry.standable(start))return new Reachability(false,Set.of());
        if(!complete && bounds.outside(start))return new Reachability(true,Set.of(start));
        boolean escaped=bounds.outside(start);
        var visited=new HashSet<Position>();var queue=new ArrayDeque<Position>();queue.add(start);visited.add(start);
        while(!queue.isEmpty() && visited.size()<=8192) {
            var from=queue.removeFirst();
            for(int[] direction:List.of(new int[]{1,0},new int[]{-1,0},new int[]{0,1},new int[]{0,-1})) {
                for(int dy:new int[]{0,1,-1,-2,-3}) {
                    var to=new Position(from.x()+direction[0],from.y()+dy,from.z()+direction[1]);
                    if(!bounds.contains(to) || visited.contains(to) || !geometry.standable(to) || !geometry.traversable(from,to))continue;
                    if(bounds.outside(to)){escaped=true;if(!complete){visited.add(to);return new Reachability(true,Set.copyOf(visited));}}
                    visited.add(to);queue.addLast(to);
                }
            }
        }
        return new Reachability(escaped,Set.copyOf(visited));
    }
}
