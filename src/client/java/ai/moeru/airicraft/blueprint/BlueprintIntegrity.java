package ai.moeru.airicraft.blueprint;

import java.util.*;

/** Compare design states while allowing neighbor-derived fence/pane/wall connections. */
public final class BlueprintIntegrity {
    private static final java.util.Set<String> CONNECTION_PROPERTIES=java.util.Set.of("north","south","east","west");
    /** True when the states differ only in neighbor-derived fence/pane/bar/wall connections, which the game computes at placement. */
    public static boolean statesMatch(net.minecraft.world.level.block.state.BlockState expected,net.minecraft.world.level.block.state.BlockState actual){
        if(expected==null||actual==null)return expected==actual;
        if(expected.equals(actual))return true;
        var block=expected.getBlock();
        if(block!=actual.getBlock())return false;
        boolean wall=block instanceof net.minecraft.world.level.block.WallBlock;
        if(!wall&&!(block instanceof net.minecraft.world.level.block.CrossCollisionBlock))return false;
        var normalized=expected;
        for(var property:expected.getProperties())if(CONNECTION_PROPERTIES.contains(property.getName())||(wall&&property.getName().equals("up")))normalized=copy(normalized,actual,property);
        return normalized.equals(actual);
    }
    private static <T extends Comparable<T>> net.minecraft.world.level.block.state.BlockState copy(net.minecraft.world.level.block.state.BlockState to,net.minecraft.world.level.block.state.BlockState from,net.minecraft.world.level.block.state.properties.Property<T> property){return to.setValue(property,from.getValue(property));}
    static boolean matches(String expected,String actual){
        if(expected.equals(actual))return true;
        String block=expected.split("\\[",2)[0];
        if(!block.equals(actual.split("\\[",2)[0]))return false;
        boolean connections=block.endsWith("_fence")||block.endsWith("_pane")||block.endsWith(":glass_pane")||block.endsWith("_wall");
        if(!connections)return false;
        var a=properties(expected);var b=properties(actual);
        for(String p:List.of("north","south","east","west")){a.remove(p);b.remove(p);}
        if(block.endsWith("_wall")){a.remove("up");b.remove("up");}
        return a.equals(b);
    }
    private static Map<String,String> properties(String text){
        var out=new HashMap<String,String>();int start=text.indexOf('[');
        if(start>=0)for(String part:text.substring(start+1,text.length()-1).split(",")){var pair=part.split("=",2);if(pair.length==2)out.put(pair[0],pair[1]);}
        return out;
    }
}
