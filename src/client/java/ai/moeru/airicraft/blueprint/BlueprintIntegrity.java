package ai.moeru.airicraft.blueprint;

import java.util.*;

/** Compare design states while allowing neighbor-derived fence/pane/wall connections. */
final class BlueprintIntegrity {
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
