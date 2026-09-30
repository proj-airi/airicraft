package ai.moeru.airicraft.blueprint.prototype;

import com.google.gson.*;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** THROWAWAY: bounded component compiler, ownership and explicit composition. */
public final class BlueprintPrototype {
    public record Cell(BlockPos position, BlockState state, String owner, List<String> contributors) {}
    public final LinkedHashMap<BlockPos, Cell> cells = new LinkedHashMap<>();
    public final List<Map<String,Object>> components = new ArrayList<>();
    private final Set<String> paths = new HashSet<>();
    private final Map<String,Integer> surface;
    public final JsonObject tree;
    public BlueprintPrototype(JsonObject tree, Map<String,Integer> surface) {
        this.tree = tree.deepCopy(); this.surface = surface;
        visit(tree,"",BlockPos.ORIGIN,0,List.of(),0);
    }
    private void visit(JsonObject node,String parent,BlockPos origin,int rotation,List<String> inherited,int depth) {
        if(depth>24 || components.size()>512) throw new IllegalArgumentException("component_limit");
        String id=node.get("id").getAsString();
        if(!id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("invalid_component_id: "+id);
        String path=parent.isEmpty()?id:parent+"."+id;
        if(!paths.add(path)) throw new IllegalArgumentException("duplicate_component: "+path);
        BlockPos at=origin.add(rotate(vector(node,"at",BlockPos.ORIGIN),rotation));
        int turn=node.has("rotate")?node.get("rotate").getAsInt():0;
        if(turn%90!=0) throw new IllegalArgumentException("rotation_must_be_quarter_turn");
        int rot=Math.floorMod(rotation+turn,360);
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
            node.getAsJsonObject("anchors").entrySet().forEach(e->anchors.put(e.getKey(),xyz(at.add(rotate(vector(e.getValue()),rot)))));
            info.put("anchors",anchors);
        }
        components.add(info);
        if(node.has("volume")) {
            JsonObject volume=node.getAsJsonObject("volume"); BlockPos size=vector(volume.get("size"));
            if(size.getX()<1||size.getY()<1||size.getZ()<1||(long)size.getX()*size.getY()*size.getZ()>8192) throw new IllegalArgumentException("volume_limit");
            BlockState state=parseState(volume.get("state").getAsString()).rotate(switch(rot) {case 90->BlockRotation.CLOCKWISE_90; case 180->BlockRotation.CLOCKWISE_180;case 270->BlockRotation.COUNTERCLOCKWISE_90;default->BlockRotation.NONE;});
            for(int y=0;y<size.getY();y++) for(int z=0;z<size.getZ();z++) for(int x=0;x<size.getX();x++) put(at.add(rotate(new BlockPos(x,y,z),rot)),state,path,allowed);
        }
        if(node.has("foundation")) {
            if(surface==null||rot!=0) throw new IllegalArgumentException("foundation_requires_terrain_and_zero_rotation");
            var f=node.getAsJsonObject("foundation"); var size=vector(f.get("size")); var state=parseState(f.get("material").getAsString());
            if(size.getX()<1||size.getX()>32||size.getZ()<1||size.getZ()>32) throw new IllegalArgumentException("foundation_size_limit");
            for(int x=0;x<size.getX();x++) for(int z=0;z<size.getZ();z++) {
                Integer ground=surface.get((at.getX()+x)+","+(at.getZ()+z));
                if(ground==null) throw new IllegalArgumentException("unsampled_foundation_column");
                if(ground>=at.getY()) throw new IllegalArgumentException("terrain_intersects_floor");
                for(int y=ground+1;y<at.getY();y++) put(new BlockPos(at.getX()+x,y,at.getZ()+z),state,path,allowed);
            }
        }
        if(node.has("children")) for(JsonElement child:node.getAsJsonArray("children")) visit(child.getAsJsonObject(),path,at,rot,allowed,depth+1);
    }
    private void put(BlockPos p,BlockState state,String path,List<String> allowed) {
        if(Math.abs(p.getX())>128||Math.abs(p.getY())>128||Math.abs(p.getZ())>128||cells.size()>=8192) throw new IllegalArgumentException("blueprint_bounds_limit");
        Cell old=cells.get(p);
        var history=new ArrayList<String>();
        if(old!=null) {
            boolean permitted=path.startsWith(old.owner()+".")||allowed.stream().anyMatch(a->old.owner().equals(a)||old.owner().startsWith(a+"."));
            if(!permitted) throw new IllegalArgumentException("component_conflict at "+xyz(p)+": "+old.owner()+" vs "+path);
            history.addAll(old.contributors());
        }
        history.add(path); cells.put(p,new Cell(p,state,path,List.copyOf(history)));
    }
    public Map<String,Object> snapshot(int revision) {
        var out=new LinkedHashMap<String,Object>(); out.put("revision",revision);out.put("tree",tree);out.put("components",components);
        out.put("cells",cells.values().stream().map(BlueprintPrototype::describe).toList());
        var materials=new TreeMap<String,Integer>(); cells.values().stream().filter(c->!c.state().isAir()).forEach(c->materials.merge(Registries.BLOCK.getId(c.state().getBlock()).toString(),1,Integer::sum));
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
        String[] parts=text.split("\\[",2);Identifier id=Identifier.of(parts[0]);
        if(!Registries.BLOCK.containsId(id)) throw new IllegalArgumentException("unknown_block: "+id);
        BlockState state=Registries.BLOCK.get(id).getDefaultState();
        if(parts.length==2) {
            if(!parts[1].endsWith("]")) throw new IllegalArgumentException("invalid_state: "+text);
            for(String entry:parts[1].substring(0,parts[1].length()-1).split(",")) {
                String[] kv=entry.split("=",2); Property<?> property=state.getBlock().getStateManager().getProperty(kv[0]);
                if(property==null||kv.length!=2) throw new IllegalArgumentException("invalid_property: "+entry);
                state=with(state,property,kv[1]);
            }
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState with(BlockState state,Property<T> p,String value) {return state.with(p,p.parse(value).orElseThrow(()->new IllegalArgumentException("invalid_property_value: "+value)));}
    public static String stateText(BlockState state) {
        String name=Registries.BLOCK.getId(state.getBlock()).toString();if(state.getEntries().isEmpty())return name;
        var values=new ArrayList<String>();state.getEntries().forEach((p,v)->values.add(p.getName()+"="+valueName(p,v)));Collections.sort(values);
        return name+"["+String.join(",",values)+"]";
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private static String valueName(Property p,Comparable v){return p.name(v);}
}
