package ai.moeru.airicraft.blueprint;

import ai.moeru.airicraft.agent.llm.*;
import com.google.gson.*;
import java.util.*;
import java.util.function.*;

/** A bounded conversation containing only the building brief and blueprint evidence. */
public final class BlueprintDesigner {
    public static final Set<String> OPERATIONS = Set.of("draft", "get", "explain", "lint");
    private static final String INSTRUCTIONS = "You design Minecraft blueprints. Your only task is the supplied building brief. "
        + "Use named semantic components, explicit replaces relationships for intentional overlaps, and walkability annotations. "
        + "The site and authoring library below are facts/API documentation. Do not invent constructor fields. "
        + "Iterate draft, inspect, lint and repair. Never place blocks or operate gameplay. "
        + "Call one blueprint tool at a time. Finish with a short design summary and honest unresolved limitations. "
        + "A failed draft leaves the previous revision; do not claim a rejected change succeeded. "
        + "Lint is advisory: explain intentional exceptions; never suppress warnings merely to get a clean result.";
    public interface Model { PlannerResponse generate(LlmConversation conversation) throws Exception; }
    public interface Tools { String call(JsonObject arguments) throws Exception; }
    public record Result(String status, String summary, JsonObject draft, JsonObject lint, int turns) {}
    private final Model model;
    private final Tools tools;
    private final BooleanSupplier active;
    private final BiConsumer<String, String> transcript;
    private final int maxTurns;

    public BlueprintDesigner(Model model, Tools tools, BooleanSupplier active, BiConsumer<String,String> transcript, int maxTurns) {
        this.model=model;this.tools=tools;this.active=active;this.transcript=transcript;this.maxTurns=maxTurns;
    }
    public Result run(String brief, String authoring, String site, String existing) throws Exception {
        var history=new ArrayList<LlmChatMessage>();
        history.add(LlmChatMessage.system(INSTRUCTIONS+"\nAUTHORING API\n"+authoring));
        history.add(LlmChatMessage.user("BUILDING BRIEF\n"+brief+"\nSITE\n"+site+"\nEXISTING DESIGN\n"+existing,LlmMessageKind.TASK));
        transcript.accept("system",INSTRUCTIONS+"\nAUTHORING API\n"+authoring);
        transcript.accept("brief",brief);transcript.accept("context",site+"\n"+existing);
        JsonObject draft=null,lint=null;boolean rejected=false;int turns=0;
        for(;turns<maxTurns;turns++) {
            checkActive();
            if(history.stream().mapToInt(m->m.content().length()).sum()>240_000)
                return new Result("context_limit","Designer context limit reached; design requires further review.",draft,lint,turns);
            transcript.accept("progress","Model turn "+(turns+1)+" of "+maxTurns);
            var response=model.generate(LlmConversation.of(history));checkActive();
            if(response.rawAssistantContent()!=null)transcript.accept("assistant_raw",response.rawAssistantContent().toString());
            if(response.toolCalls().isEmpty()) {
                transcript.accept("assistant",response.replyText());
                if(draft!=null&&!rejected) {
                    String checked=call(json("lint"));
                    if(!checked.startsWith("TOOL_ERROR:"))lint=JsonParser.parseString(checked).getAsJsonObject();
                    else return new Result("needs_review",response.replyText()+"; final lint failed: "+checked,draft,null,turns+1);
                    return new Result("completed",response.replyText(),draft,lint,turns+1);
                }
                history.add(LlmChatMessage.assistant(response.replyText()));
                history.add(LlmChatMessage.user("No accepted final draft yet. Repair the rejected draft using the error, then lint it.",LlmMessageKind.TASK));
                continue;
            }
            if(!response.replyText().isBlank())transcript.accept("assistant",response.replyText());
            history.add(LlmChatMessage.assistantToolCalls(response.replyText(),response.toolCalls(),response.rawAssistantContent()));
            for(var tool:response.toolCalls()) {
                checkActive();var args=tool.arguments();String op=args.has("op")?args.get("op").getAsString():"";
                String result;
                if(!tool.name().equals("blueprint")||!OPERATIONS.contains(op)){result="TOOL_ERROR: designer may only draft, get, explain or lint blueprints";transcript.accept("tool_call",tool.name()+" "+args);transcript.accept("tool_result",result);}
                else {
                    // Site and advisory rules are controlled by the host, not arbitrary model arguments.
                    args=args.deepCopy();args.remove("origin");args.remove("rules");args.remove("world");
                    result=call(args);
                    if(op.equals("draft")){rejected=result.startsWith("TOOL_ERROR:");if(!rejected){draft=JsonParser.parseString(result).getAsJsonObject();lint=null;}}
                    if(op.equals("get")&&!result.startsWith("TOOL_ERROR:"))draft=JsonParser.parseString(result).getAsJsonObject();
                    if(op.equals("lint")&&!result.startsWith("TOOL_ERROR:"))lint=JsonParser.parseString(result).getAsJsonObject();
                }
                String feedback=result;
                if((op.equals("draft")||op.equals("get"))&&!result.startsWith("TOOL_ERROR:")){
                    var compact=JsonParser.parseString(result).getAsJsonObject();compact.remove("cells");compact.remove("tree");feedback=compact.toString();
                }
                history.add(LlmChatMessage.tool(tool.id(),feedback));
            }
        }
        return new Result("iteration_limit","Designer reached its iteration budget; inspect remaining findings before committing.",draft,lint,turns);
    }
    private String call(JsonObject args)throws Exception {
        checkActive();transcript.accept("tool_call",args.toString());String result=tools.call(args);checkActive();
        transcript.accept("tool_result",result);return result;
    }
    private void checkActive(){if(!active.getAsBoolean())throw new java.util.concurrent.CancellationException("Blueprint design cancelled");}
    public static JsonObject json(String op){var a=new JsonObject();a.addProperty("op",op);return a;}
}
