package ai.moeru.airicraft.blueprint;

import com.google.gson.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** THROWAWAY: bounded component compiler, ownership and explicit composition. */
public final class Blueprint {
    public record Cell(BlockPos position, BlockState state, String owner, List<String> contributors) {}
    public final LinkedHashMap<BlockPos, Cell> cells = new LinkedHashMap<>();
    public final List<Map<String,Object>> components = new ArrayList<>();
    private final Set<String> paths = new HashSet<>();
    private final Map<String,Integer> surface;
    private final Set<String> softOwners=new HashSet<>();
    public final JsonObject tree;
    public Blueprint(JsonObject tree, Map<String,Integer> surface) {this(tree,surface,null);}
    /** Natural obstacles ("x,z" to "block@y0..y1" runs, local y) inside the built volume are cleared by the program, not the designer. */
    public Blueprint(JsonObject tree, Map<String,Integer> surface, Map<String,List<String>> obstacles) {
        this.tree = tree.deepCopy(); this.surface = surface;
        visit(tree,"",BlockPos.ZERO,0,List.of(),new JsonObject(),0);
        if(obstacles!=null) deriveClearance(obstacles);
    }
    public static final String DERIVED_CLEARANCE="derived_clearance";
    /** Clears every sampled obstacle block that is inside the columns the blueprint touches (plus one block of walking room) and not above its highest cell. */
    private void deriveClearance(Map<String,List<String>> obstacles) {
        int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,minZ=Integer.MAX_VALUE,maxZ=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE;
        for(var c:cells.values()) {
            if(c.state().isAir()) continue;
            var p=c.position();minX=Math.min(minX,p.getX());maxX=Math.max(maxX,p.getX());minZ=Math.min(minZ,p.getZ());maxZ=Math.max(maxZ,p.getZ());maxY=Math.max(maxY,p.getY());
        }
        if(maxY==Integer.MIN_VALUE) return;
        var air=Blocks.AIR.defaultBlockState();int cleared=0;
        softOwners.add(DERIVED_CLEARANCE);
        for(var column:obstacles.entrySet()) {
            String[] xz=column.getKey().split(",");int x=Integer.parseInt(xz[0]),z=Integer.parseInt(xz[1]);
            if(x<minX-1||x>maxX+1||z<minZ-1||z>maxZ+1) continue;
            for(String run:column.getValue()) {
                int at=run.lastIndexOf('@');if(at<0) continue;
                String[] range=run.substring(at+1).split("\\.\\.");
                int y0=Integer.parseInt(range[0]),y1=Integer.parseInt(range[1]);
                for(int y=y0;y<=Math.min(y1,maxY);y++) {
                    var p=new BlockPos(x,y,z);
                    if(cells.containsKey(p)) continue;
                    put(p,air,DERIVED_CLEARANCE,List.of());cleared++;
                }
            }
        }
        if(cleared>0) {
            var info=new LinkedHashMap<String,Object>();info.put("path",DERIVED_CLEARANCE);info.put("type","DerivedClearance");info.put("origin",xyz(BlockPos.ZERO));info.put("rotation",0);
            info.put("guidance",new JsonObject());info.put("clearedBlocks",cleared);components.add(info);
        }
    }
    private void visit(JsonObject node,String parent,BlockPos origin,int rotation,List<String> inherited,JsonObject inheritedGuidance,int depth) {
        if(depth>24 || components.size()>512) throw new IllegalArgumentException("component_limit");
        if(!node.has("id")||!node.get("id").isJsonPrimitive()) throw new IllegalArgumentException("missing_component_id: every component needs a unique string id"+(node.has("type")?" (type "+node.get("type").getAsString()+" under "+(parent.isEmpty()?"root":parent)+")":""));
        String id=node.get("id").getAsString();
        if(!id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("invalid_component_id: "+id);
        String path=parent.isEmpty()?id:parent+"."+id;
        if(!paths.add(path)) throw new IllegalArgumentException("duplicate_component: "+path);
        BlockPos at=origin.offset(rotate(vector(node,"at",BlockPos.ZERO),rotation));
        int turn=node.has("rotate")?node.get("rotate").getAsInt():0;
        if(turn%90!=0) throw new IllegalArgumentException("rotation_must_be_quarter_turn");
        int rot=Math.floorMod(rotation+turn,360);
        if(node.has("yields")&&node.get("yields").getAsBoolean()) softOwners.add(path);
        var allowed=new ArrayList<>(inherited);
        if(node.has("replaces")) {
            var replace=node.get("replaces");
            if(replace.isJsonArray()) replace.getAsJsonArray().forEach(v->allowed.add(v.getAsString()));
            else allowed.add(replace.getAsString());
        }
        var info=new LinkedHashMap<String,Object>();
        info.put("path",path); info.put("type",node.has("type")?node.get("type").getAsString():"Component");
        info.put("origin",xyz(at)); info.put("rotation",rot);
        if(node.has("anchors")) {
            var anchors=new LinkedHashMap<String,Object>();
            node.getAsJsonObject("anchors").entrySet().forEach(e->anchors.put(e.getKey(),xyz(at.offset(rotate(vector(e.getValue()),rot)))));
            info.put("anchors",anchors);
        }
        var guidance=inheritedGuidance.deepCopy();
        if(node.has("guidance"))node.getAsJsonObject("guidance").entrySet().forEach(e->guidance.add(e.getKey(),e.getValue().deepCopy()));
        info.put("guidance",guidance);
        for(String key:List.of("interior","ignoredFields"))if(node.has(key))info.put(key,node.get(key).deepCopy());
        components.add(info);
        if(node.has("volume")) {
            info.put("volumeSize",node.getAsJsonObject("volume").get("size").deepCopy());
            JsonObject volume=node.getAsJsonObject("volume"); BlockPos size=vector(volume.get("size"));
            if(size.getX()<1||size.getY()<1||size.getZ()<1||(long)size.getX()*size.getY()*size.getZ()>8192) throw new IllegalArgumentException("volume_limit");
            BlockState state=parseState(volume.get("state").getAsString()).rotate(switch(rot) {case 90->Rotation.CLOCKWISE_90; case 180->Rotation.CLOCKWISE_180;case 270->Rotation.COUNTERCLOCKWISE_90;default->Rotation.NONE;});
            for(int y=0;y<size.getY();y++) for(int z=0;z<size.getZ();z++) for(int x=0;x<size.getX();x++) put(at.offset(rotate(new BlockPos(x,y,z),rot)),state,path,allowed);
        }
        if(node.has("foundation")) {
            if(surface==null||rot!=0) throw new IllegalArgumentException("foundation_requires_terrain_and_zero_rotation");
            var f=node.getAsJsonObject("foundation"); var size=vector(f.get("size")); var state=parseState(f.get("material").getAsString());
            if(size.getX()<1||size.getX()>32||size.getZ()<1||size.getZ()>32) throw new IllegalArgumentException("foundation_size_limit");
            for(int x=0;x<size.getX();x++) for(int z=0;z<size.getZ();z++) {
                Integer ground=surface.get((at.getX()+x)+","+(at.getZ()+z));
                if(ground==null) throw new IllegalArgumentException("unsampled_foundation_column");
                if(ground>=at.getY()) throw new IllegalArgumentException("terrain_intersects_floor at column "+(at.getX()+x)+","+(at.getZ()+z)+": ground top is local y="+ground+" but foundation "+path+" floor is local y="+at.getY()
                    +"; highest ground under its footprint is y="+footprintMax(at,size)+". Raise the foundation/floor to at least that +1 (foundation blocks fill y=ground+1..floor-1) or lower the site; use Clearance to remove blocks above ground.");
                for(int y=ground+1;y<at.getY();y++) put(new BlockPos(at.getX()+x,y,at.getZ()+z),state,path,allowed);
            }
        }
        if(node.has("children")) for(JsonElement child:node.getAsJsonArray("children")) visit(child.getAsJsonObject(),path,at,rot,allowed,guidance,depth+1);
    }
    private int footprintMax(BlockPos at,BlockPos size) {
        int max=Integer.MIN_VALUE;
        for(int x=0;x<size.getX();x++) for(int z=0;z<size.getZ();z++){Integer g=surface.get((at.getX()+x)+","+(at.getZ()+z));if(g!=null)max=Math.max(max,g);}
        return max;
    }
    private void put(BlockPos p,BlockState state,String path,List<String> allowed) {
        if(Math.abs(p.getX())>128||Math.abs(p.getY())>128||Math.abs(p.getZ())>128||cells.size()>=8192) throw new IllegalArgumentException("blueprint_bounds_limit");
        Cell old=cells.get(p);
        var history=new ArrayList<String>();
        // A yielding component (site clearing) never displaces another component's cell and is displaced by any later one.
        if(old!=null&&softOwners.contains(path)) return;
        if(old!=null) {
            boolean permitted=softOwners.contains(old.owner())||path.startsWith(old.owner()+".")||allowed.stream().anyMatch(a->old.owner().equals(a)||old.owner().startsWith(a+"."));
            if(!permitted) throw new IllegalArgumentException("component_conflict at "+xyz(p)+": "+old.owner()+" vs "+path
                +". To overlap on purpose, give "+path+" (or an ancestor) replaces:[\""+old.owner()+"\"] using the exact full dotted path; shorter relative names do not match.");
            history.addAll(old.contributors());
        }
        history.add(path); cells.put(p,new Cell(p,state,path,List.copyOf(history)));
    }
    public Map<String,Object> snapshot(int revision) {
        var out=new LinkedHashMap<String,Object>(); out.put("revision",revision);out.put("tree",tree);out.put("components",components);
        out.put("cells",cells.values().stream().map(Blueprint::describe).toList());
        var materials=new TreeMap<String,Integer>(); cells.values().stream().filter(c->!c.state().isAir()).forEach(c->materials.merge(BuiltInRegistries.BLOCK.getKey(c.state().getBlock()).toString(),1,Integer::sum));
        out.put("materials",materials);out.put("cellCount",cells.size());return out;
    }
    public static Map<String,Object> describe(Cell c) {
        return Map.of("position",xyz(c.position()),"state",stateText(c.state()),"owner",c.owner(),"contributors",c.contributors(),"ancestry",ancestry(c.owner()));
    }
    private static List<String> ancestry(String path) {
        var out=new ArrayList<String>();String p="";for(String s:path.split("\\.")){p=p.isEmpty()?s:p+"."+s;out.add(p);}return out;
    }
    public static List<Integer> xyz(BlockPos p) {return List.of(p.getX(),p.getY(),p.getZ());}
    public static BlockPos vector(JsonObject o,String key,BlockPos fallback) {return o.has(key)?vector(o.get(key)):fallback;}
    public static BlockPos vector(JsonElement e) {
        var a=e.getAsJsonArray();if(a.size()!=3) throw new IllegalArgumentException("expected_xyz");
        return new BlockPos(a.get(0).getAsBigDecimal().intValueExact(),a.get(1).getAsBigDecimal().intValueExact(),a.get(2).getAsBigDecimal().intValueExact());
    }
    private static BlockPos rotate(BlockPos p,int r) {return switch(r){case 90->new BlockPos(-p.getZ(),p.getY(),p.getX());case 180->new BlockPos(-p.getX(),p.getY(),-p.getZ());case 270->new BlockPos(p.getZ(),p.getY(),-p.getX());default->p;};}
    public static BlockState parseState(String text) {
        String[] parts=text.split("\\[",2);ResourceLocation id=ResourceLocation.parse(parts[0]);
        if(!BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalArgumentException("unknown_block: "+id);
        BlockState state=BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
        if(parts.length==2) {
            if(!parts[1].endsWith("]")) throw new IllegalArgumentException("invalid_state: "+text);
            for(String entry:parts[1].substring(0,parts[1].length()-1).split(",")) {
                String[] kv=entry.split("=",2); Property<?> property=state.getBlock().getStateDefinition().getProperty(kv[0]);
                if(property==null||kv.length!=2) throw new IllegalArgumentException("invalid_property: "+entry);
                state=with(state,property,kv[1]);
            }
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState with(BlockState state,Property<T> p,String value) {return state.setValue(p,p.getValue(value).orElseThrow(()->new IllegalArgumentException("invalid_property_value: "+value)));}
    public static String stateText(BlockState state) {
        String name=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();if(state.getValues().isEmpty())return name;
        var values=new ArrayList<String>();state.getValues().forEach((p,v)->values.add(p.getName()+"="+valueName(p,v)));Collections.sort(values);
        return name+"["+String.join(",",values)+"]";
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private static String valueName(Property p,Comparable v){return p.getName(v);}
}
