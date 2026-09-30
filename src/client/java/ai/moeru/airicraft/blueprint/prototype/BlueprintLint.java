package ai.moeru.airicraft.blueprint.prototype;

import ai.moeru.airicraft.policy.GraalPolicyInvocation;
import com.google.gson.*;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.*;
import net.minecraft.world.BlockView;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Prototype facts and isolated JS advice. No rule can mutate Minecraft or block a commit. */
public final class BlueprintLint {
    private static final Gson JSON=new Gson();
    public static String resource(String name) {
        try(var in=BlueprintLint.class.getResourceAsStream("/blueprint-prototype/"+name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    public static JsonArray defaults() {
        var rules=new JsonArray();
        for(String id:List.of("entrance-access","room-lighting")) {var r=new JsonObject();r.addProperty("id",id);r.addProperty("source",resource(id+".js"));rules.add(r);}return rules;
    }
    public static JsonObject capture(BlueprintPrototype draft,int revision,ServerWorld world,BlockPos origin) {
        if(draft.cells.isEmpty())throw new IllegalArgumentException("empty_blueprint");
        int minX=128,minY=128,minZ=128,maxX=-128,maxY=-128,maxZ=-128;
        for(var p:draft.cells.keySet()){minX=Math.min(minX,p.getX());minY=Math.min(minY,p.getY());minZ=Math.min(minZ,p.getZ());maxX=Math.max(maxX,p.getX());maxY=Math.max(maxY,p.getY());maxZ=Math.max(maxZ,p.getZ());}
        minX-=3;minY-=3;minZ-=3;maxX+=3;maxY+=3;maxZ+=3;
        if((long)(maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1)>24000)throw new IllegalArgumentException("lint_volume_limit");
        final var planned=draft.cells;
        BlockView merged=new BlockView(){
            public BlockState getBlockState(BlockPos p){var c=planned.get(p.subtract(origin));return c==null?world.getBlockState(p):c.state();}
            public FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
            public BlockEntity getBlockEntity(BlockPos p){return null;}
            public int getHeight(){return world.getHeight();}
            public int getBottomY(){return world.getBottomY();}
        };
        var voxels=new LinkedHashMap<BlockPos,JsonArray>();var light=new HashMap<BlockPos,Integer>();var opaque=new HashSet<BlockPos>();var queue=new ArrayDeque<BlockPos>();
        int unknown=0;
        for(int y=minY;y<=maxY;y++)for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++) {
            var local=new BlockPos(x,y,z);var p=local.add(origin);var a=new JsonArray();
            a.add(x);a.add(y);a.add(z);
            boolean known=planned.containsKey(local)||(world.isChunkLoaded(p)&&p.getY()>=world.getBottomY()&&p.getY()<=world.getTopYInclusive());
            a.add(known);var boxes=new JsonArray();int emission=0;boolean liquid=false;
            if(known){
                var state=merged.getBlockState(p);emission=state.getLuminance();liquid=!state.getFluidState().isEmpty();
                // An operable wooden door is treated as opened for access advice; iron doors remain obstacles.
                if(!(state.getBlock() instanceof DoorBlock && !state.isOf(Blocks.IRON_DOOR)))
                    for(var box:state.getCollisionShape(merged,p).getBoundingBoxes())boxes.add(JSON.toJsonTree(List.of(box.minX,box.minY,box.minZ,box.maxX,box.maxY,box.maxZ)));
                if(state.isOpaqueFullCube())opaque.add(local);
            }else{unknown++;opaque.add(local);}
            a.add(boxes);a.add(emission);a.add(0);a.add(liquid);voxels.put(local,a);
            if(emission>0){light.put(local,emission);queue.add(local);}
        }
        // Approximate block-light flood fill. No skylight, partial-block occlusion or light entering from outside the capture.
        while(!queue.isEmpty()){
            BlockPos p=queue.remove();int level=light.get(p)-1;if(level<=0)continue;
            for(var direction:Direction.values()) {BlockPos next=p.offset(direction);if(!voxels.containsKey(next)||opaque.contains(next)||light.getOrDefault(next,0)>=level)continue;light.put(next,level);queue.add(next);}
        }
        var geometry=new JsonArray();voxels.forEach((p,a)->{a.set(6,new JsonPrimitive(light.getOrDefault(p,0)));geometry.add(a);});
        var out=new JsonObject();out.addProperty("revision",revision);out.addProperty("serverTick",world.getTime());out.add("origin",JSON.toJsonTree(BlueprintPrototype.xyz(origin)));
        out.add("components",JSON.toJsonTree(draft.components));
        out.add("cells",JSON.toJsonTree(draft.cells.values().stream().map(c->Map.of("position",BlueprintPrototype.xyz(c.position()),"owner",c.owner(),"state",BlueprintPrototype.stateText(c.state()))).toList()));
        out.add("geometry",geometry);out.addProperty("unknownCells",unknown);
        out.addProperty("lightingMethod","Approximate block-light only; excludes skylight, external boundary light and partial-block occlusion. Design advice, not a spawn-safety guarantee.");
        out.addProperty("accessMethod","Local straight entrance approach using actual collision boxes, 0.6x1.8 player and <=0.6 step-up; wooden doors assumed operable. Not a whole-building pathfinding proof.");
        return out;
    }
    public static CompletableFuture<String> run(JsonObject snapshot,JsonArray rules) {
        if(rules.size()>12)throw new IllegalArgumentException("at_most_12_rules");
        var ids=new HashSet<String>();
        for(var e:rules){var r=e.getAsJsonObject();if(!r.get("id").getAsString().matches("[a-zA-Z0-9_.-]{1,80}")||!ids.add(r.get("id").getAsString()))throw new IllegalArgumentException("invalid_or_duplicate_rule_id");}
        var results=new JsonArray();CompletableFuture<Void> chain=CompletableFuture.completedFuture(null);
        for(var e:rules){var rule=e.getAsJsonObject().deepCopy();chain=chain.thenCompose(ignored->{
            var input=new JsonObject();input.addProperty("ruleId",rule.get("id").getAsString());
            try{return GraalPolicyInvocation.query(resource("lint-api.js")+"\n"+rule.get("source").getAsString()+"\nfunction query(w,input){return runRule(w,input,check);}",snapshot,input)
                .handle((value,error)->{addResult(results,rule,value,error);return (Void)null;});}
            catch(RuntimeException error){addResult(results,rule,null,error);return CompletableFuture.completedFuture(null);}
        });}
        return chain.thenApply(v->{var out=new JsonObject();out.add("revision",snapshot.get("revision"));out.add("serverTick",snapshot.get("serverTick"));out.addProperty("advisory",true);out.add("rules",results);out.add("lightingMethod",snapshot.get("lightingMethod"));out.add("accessMethod",snapshot.get("accessMethod"));return out.toString();});
    }
    private static void addResult(JsonArray results,JsonObject rule,JsonElement value,Throwable error){
        var r=new JsonObject();r.add("id",rule.get("id"));r.addProperty("status",error==null?"completed":"error");
        if(error==null)r.add("result",value);else{while(error.getCause()!=null)error=error.getCause();r.addProperty("message",error.getMessage());}results.add(r);
    }
}
