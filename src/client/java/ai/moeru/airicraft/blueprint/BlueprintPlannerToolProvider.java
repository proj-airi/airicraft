package ai.moeru.airicraft.blueprint;

import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.llm.*;
import ai.moeru.airicraft.agent.llm.codex.CodexAppServerLlmBackend;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

/** Main-planner facade. The specialist's source and iteration history never enter its conversation. */
public final class BlueprintPlannerToolProvider implements PlannerToolProvider {
    private static final Gson JSON=new Gson();
    private final AgentConfig.LlmConfig config;
    private final BlueprintService service;
    private final boolean driver;
    private String lease,blueprintId;
    private JsonArray site;
    private final List<Map<String,Object>> transcript=new ArrayList<>();
    private String status="idle",modelSummary="";
    private int transcriptChars;
    private volatile LlmBackend backend;
    private Thread workerThread;
    public BlueprintPlannerToolProvider(AgentConfig.LlmConfig config,boolean driver){this.config=config;this.driver=driver;service=BlueprintService.instance();}
    @Override public String id(){return "blueprint_designer";}
    @Override public boolean handles(String name){return name.equals("design_blueprint")||name.equals("blueprint");}
    @Override public boolean isReadTool(String name){return false;}
    @Override public String promptInstructions(){return "For construction, call design_blueprint with a building brief and site. It delegates all source authoring and lint repair to an isolated designer. Review its compact result and unresolved findings, then explicitly commit using blueprint with the returned revision and origin. Never claim a design is built before commit and verify succeed.";}
    @Override public List<Map<String,Object>> openAiTools(){
        var result=new ArrayList<Map<String,Object>>();
        result.add(toolForProvider("design_blueprint","Design or revise a semantic blueprint in a separate specialist conversation. Does not place blocks. Returns blueprintId, revision, dimensions and advisory findings. Runs a bounded design/compile/lint repair loop; may take several minutes. Use revise with the returned blueprintId to improve this session's current design. Repeating the same site also revises it, retaining the original terrain context.",
            propertiesForProvider(propForProvider("brief",stringForProvider("What to build, intended use, materials and constraints.")),
                propForProvider("site",Map.of("type","array","items",Map.of("type","integer"),"minItems",3,"maxItems",3)),
                propForProvider("revise",stringForProvider("Existing blueprintId, when revising."))),List.of("brief","site")));
        if(driver)result.addAll(new BlueprintToolProvider().openAiTools());
        else result.add(toolForProvider("blueprint","Inspect or realize the current design. list discovers saved designs; load selects blueprintId for inspection/revision. get returns compact metadata and findings; explain traces a local position; commit requires exact revision and origin and is creative Blueprint-* only; verify checks placed blocks. Author through design_blueprint. No blocks are placed during design.",
            propertiesForProvider(propForProvider("op",Map.of("type","string","enum",List.of("get","list","load","explain","commit","verify"))),
                propForProvider("blueprintId",stringForProvider("Saved ID for load; list discovers saved designs.")),propForProvider("revision",Map.of("type","integer")),propForProvider("origin",vector()),propForProvider("position",vector())),List.of("op")));
        return result;
    }
    private static Map<String,Object> vector(){return Map.of("type","array","items",Map.of("type","integer"),"minItems",3,"maxItems",3);}
    @Override public CompletableFuture<String> execute(PlannerToolCall call){
        if(call.name().equals("design_blueprint"))return start(call.arguments());
        String op=call.arguments().has("op")?call.arguments().get("op").getAsString():"";
        if(!driver&&!Set.of("get","list","load","explain","commit","verify").contains(op))return CompletableFuture.completedFuture("TOOL_ERROR: use design_blueprint for authoring");
        return service.execute(call.arguments()).thenApply(r->{
            if(!r.startsWith("TOOL_ERROR:")&&Set.of("get","load").contains(op)){
                var d=JsonParser.parseString(r).getAsJsonObject();synchronized(this){if(d.has("blueprintId")&&!d.get("blueprintId").isJsonNull())blueprintId=d.get("blueprintId").getAsString();if(d.has("origin"))site=d.getAsJsonArray("origin");}
                return driver?r:compact("current","",d,null);
            }return r;
        });
    }
    private synchronized CompletableFuture<String> start(JsonObject args){
        try{
            if(lease!=null)throw new IllegalStateException("designer_busy");
            String brief=args.get("brief").getAsString();if(brief.isBlank()||brief.length()>8000)throw new IllegalArgumentException("brief must contain 1..8000 characters");
            JsonArray anchor=args.getAsJsonArray("site").deepCopy();if(anchor.size()!=3)throw new IllegalArgumentException("site must have three integers");
            for(var n:anchor){if(!n.isJsonPrimitive()||!n.getAsJsonPrimitive().isNumber()||n.getAsDouble()!=n.getAsInt())throw new IllegalArgumentException("site must have three integers");}
            boolean explicitRevision=args.has("revise");
            if(explicitRevision&&(blueprintId==null||!blueprintId.equals(args.get("revise").getAsString())||!anchor.equals(site)))throw new IllegalArgumentException("revision blueprintId or site does not match this session");
            boolean revise=explicitRevision||sameSite(blueprintId,site,anchor);
            String token=service.beginDesign();lease=token;
            if(!revise)blueprintId=UUID.randomUUID().toString();site=anchor;
            transcript.clear();transcriptChars=0;status="running";modelSummary=config.model();publish(token);
            var result=new CompletableFuture<String>();
            workerThread=Thread.ofPlatform().daemon().name("airicraft-blueprint-designer").unstarted(()->{
                LlmBackend localBackend=null;
                try{
                    var tools=PlannerToolRegistry.isolated(designerTools());
                    localBackend=switch(config.plannerBackend()){
                        case OPENAI_COMPATIBLE -> new OpenAiCompatibleLlmBackend(config,NoopObservability.INSTANCE,tools);
                        case CODEX_APP_SERVER -> new CodexAppServerLlmBackend(config,NoopObservability.INSTANCE,tools);
                    };
                    backend=localBackend;
                    String existing="New design";
                    if(revise){existing=invoke(token,BlueprintDesigner.json("get"));if(existing.startsWith("TOOL_ERROR:"))throw new IllegalStateException(existing);var previous=JsonParser.parseString(existing).getAsJsonObject();previous.remove("cells");existing=previous.toString();}
                    else {var sample=BlueprintDesigner.json("sample");sample.add("origin",anchor);String captured=invoke(token,sample);if(captured.startsWith("TOOL_ERROR:"))throw new IllegalStateException(captured);existing="Terrain context: "+JsonParser.parseString(captured).getAsJsonObject().get("terrain");}
                    var docs=JsonParser.parseString(invoke(token,BlueprintDesigner.json("rule_docs"))).getAsJsonObject();
                    var model=localBackend;
                    var worker=new BlueprintDesigner(c->model.generate(c).payload(),a->{
                        if(a.get("op").getAsString().equals("lint"))a.add("origin",anchor);
                        if(a.get("op").getAsString().equals("draft"))a.addProperty("blueprintId",blueprintId);
                        return invoke(token,a);
                    },()->service.designActive(token),(kind,text)->record(token,kind,text),16);
                    var outcome=worker.run(brief,docs.get("components").getAsString(),"World origin "+anchor+"; ground captured in input.terrain. Dimensions and coordinates in the draft are local.",existing);
                    String receipt;
                    synchronized(this){if(!service.designActive(token))throw new CancellationException();status=outcome.status();publish(token);receipt=compact(outcome.status(),outcome.summary(),outcome.draft(),outcome.lint());service.endDesign(token,false);}
                    result.complete(receipt);
                }catch(Exception error){
                    synchronized(this){if(token.equals(lease)){status=error instanceof CancellationException?"cancelled":"failed";record(token,"error",error.toString());publish(token);service.endDesign(token,true);}}
                    result.complete("TOOL_ERROR: blueprint designer "+error.getClass().getSimpleName()+": "+error.getMessage());
                }finally{service.endDesign(token,false);if(localBackend!=null)localBackend.shutdownBackend();synchronized(this){if(token.equals(lease)){lease=null;backend=null;}}}
            });
            workerThread.start();
            result.whenComplete((v,e)->{if(result.isCancelled())cancel(token);});
            return result;
        }catch(Exception error){return CompletableFuture.completedFuture("TOOL_ERROR: design_blueprint "+error.getMessage());}
    }
    static boolean sameSite(String currentId,JsonArray currentSite,JsonArray requestedSite) {
        return currentId!=null&&requestedSite.equals(currentSite);
    }
    private String invoke(String token,JsonObject args)throws Exception{
        return service.executeDesigner(token,args).get(60,TimeUnit.SECONDS);
    }
    private PlannerToolProvider designerTools(){return new PlannerToolProvider(){
        public String id(){return "blueprint";}public boolean handles(String n){return n.equals("blueprint");}
        public List<Map<String,Object>> openAiTools(){return List.of(toolForProvider("blueprint","Author or inspect your current draft. draft takes JavaScript source defining design(input); get returns current tree/cells; explain takes local position; lint checks captured Minecraft geometry with advisory rules.",
            propertiesForProvider(propForProvider("op",Map.of("type","string","enum",List.of("draft","get","explain","lint"))),propForProvider("source",stringForProvider("function design(input) returning semantic components")),propForProvider("position",vector())),List.of("op")));}
        public CompletableFuture<String> execute(PlannerToolCall c){return CompletableFuture.failedFuture(new UnsupportedOperationException());}
    };}
    private synchronized void record(String token,String kind,String text){
        if(!service.designActive(token))return;
        // Bound the viewer transcript independently of the model's working context.
        String bounded=text.length()>40_000?text.substring(0,40_000)+"\n[display truncated]":text;
        transcript.add(Map.of("kind",kind,"text",bounded,"at",System.currentTimeMillis()));transcriptChars+=bounded.length();
        while(transcriptChars>200_000&&transcript.size()>1)transcriptChars-=((String)transcript.removeFirst().get("text")).length();
        publish(token);
    }
    private synchronized void publish(String token){service.publishDesigner(token,JSON.toJson(Map.of("status",status,"blueprintId",blueprintId,"model",modelSummary,"site",site,"transcript",transcript)));}
    private synchronized void cancel(String token){if(token!=null&&token.equals(lease)){status="cancelled";publish(token);service.endDesign(token,true);if(workerThread!=null)workerThread.interrupt();lease=null;}}
    @Override public synchronized void reset(){cancel(lease);}
    private synchronized String compact(String status,String summary,JsonObject draft,JsonObject lint){
        var out=new JsonObject();out.addProperty("status",status);out.addProperty("summary",summary);out.addProperty("blueprintId",blueprintId);out.add("origin",site);
        if(draft!=null){out.add("revision",draft.get("revision"));out.add("cellCount",draft.get("cellCount"));out.add("materials",draft.get("materials"));
            if(draft.has("cells")){int[] min={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE},max={Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};for(var e:draft.getAsJsonArray("cells")){var p=e.getAsJsonObject().getAsJsonArray("position");for(int i=0;i<3;i++){min[i]=Math.min(min[i],p.get(i).getAsInt());max[i]=Math.max(max[i],p.get(i).getAsInt());}}if(draft.getAsJsonArray("cells").size()>0)out.add("dimensions",JSON.toJsonTree(List.of(max[0]-min[0]+1,max[1]-min[1]+1,max[2]-min[2]+1)));}}
        if(lint!=null){var advice=new JsonArray();for(var element:lint.getAsJsonArray("rules")){var rule=element.getAsJsonObject();var r=new JsonObject();r.add("id",rule.get("id"));r.add("status",rule.get("status"));if(rule.has("message"))r.add("message",rule.get("message"));if(rule.has("result")){var facts=rule.getAsJsonObject("result");r.add("applicability",facts.get("applicability"));var findings=new JsonArray();for(var f:facts.getAsJsonArray("findings")){if(findings.size()==24)break;var brief=f.getAsJsonObject().deepCopy();brief.remove("evidence");findings.add(brief);}r.add("findings",findings);}advice.add(r);}out.add("advice",advice);}else out.addProperty("adviceStatus","not_checked");
        out.addProperty("placed",false);return out.toString();
    }
}
