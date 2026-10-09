package ai.moeru.airicraft.blueprint;

import ai.moeru.airicraft.agent.llm.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class BlueprintDesignerTest {
    private static final String DRAFT="{\"revision\":2,\"cellCount\":1,\"components\":[],\"tree\":{},\"cells\":[{\"position\":[0,0,0],\"owner\":\"house.floor\"}]}";
    private static final String LINT="{\"revision\":2,\"rules\":[{\"id\":\"walkable-area\",\"status\":\"completed\",\"result\":{\"findings\":[]}}]}";
    private static PlannerResponse tool(String name,String arguments){return new PlannerResponse("",new PlannerToolCall(UUID.randomUUID().toString(),name,JsonParser.parseString(arguments).getAsJsonObject(),null),null);}
    private static PlannerResponse draft(){return tool("blueprint","{\"op\":\"draft\",\"source\":\"function design(){return Assembly({id:'house'})}\"}");}
    private static PlannerResponse done(){return new PlannerResponse("House ready for review",(PlannerToolCall)null,null);}
    @Test void repairsCompilerErrorsInIsolatedConversationAndRechecksFinalDraft()throws Exception{
        var inputs=new ArrayList<LlmConversation>();var responses=new ArrayDeque<>(List.of(draft(),draft(),done()));var drafts=new AtomicInteger();var calls=new ArrayList<String>();
        var worker=new BlueprintDesigner(c->{inputs.add(c);return responses.remove();},a->{String op=a.get("op").getAsString();calls.add(op);return op.equals("lint")?LINT:drafts.incrementAndGet()==1?"TOOL_ERROR: component_conflict house.floor vs house.stairs":DRAFT;},()->true,(k,t)->{},5);
        var result=worker.run("Two-story house","ONLY BLUEPRINT API","FLAT SITE","No existing design");
        assertEquals("completed",result.status());assertEquals(List.of("draft","draft","lint"),calls);
        assertEquals(2,inputs.getFirst().messages().size());
        String prompt=inputs.getFirst().messages().toString();assertTrue(prompt.contains("Two-story house"));assertTrue(prompt.contains("FLAT SITE"));assertFalse(prompt.contains("inventory"));
        assertTrue(inputs.get(1).messages().stream().anyMatch(m->m.content().contains("component_conflict")));
        assertFalse(inputs.get(2).messages().stream().filter(m->m.role().equals("tool")).anyMatch(m->m.content().contains("\"cells\"")));
        assertEquals(2,result.lint().get("revision").getAsInt());
    }
    @Test void blocksWorldMutationAndPinsLintInputs()throws Exception{
        var responses=new ArrayDeque<>(List.of(tool("blueprint","{\"op\":\"commit\",\"revision\":1}"),draft(),tool("blueprint","{\"op\":\"lint\",\"origin\":[999,0,0],\"rules\":[]}"),done()));
        var calls=new ArrayList<JsonObject>();var worker=new BlueprintDesigner(c->responses.remove(),a->{calls.add(a.deepCopy());return a.get("op").getAsString().equals("lint")?LINT:DRAFT;},()->true,(k,t)->{},6);
        worker.run("house","API","site","");
        assertEquals(3,calls.size());assertTrue(calls.stream().noneMatch(a->a.get("op").getAsString().equals("commit")));
        assertTrue(calls.stream().noneMatch(a->a.has("rules")||a.has("origin")));
    }
    @Test void cancelledModelResponseCannotWriteDraft()throws Exception{
        var active=new AtomicBoolean(true);var count=new AtomicInteger();
        var worker=new BlueprintDesigner(c->{active.set(false);return draft();},a->{count.incrementAndGet();return DRAFT;},active::get,(k,t)->{},3);
        assertThrows(CancellationException.class,()->worker.run("brief","api","site",""));assertEquals(0,count.get());
    }
    @Test void rejectedRevisionCannotBecomeSuccessfulThroughGet()throws Exception{
        var responses=new ArrayDeque<>(List.of(draft(),tool("blueprint","{\"op\":\"get\"}"),done()));
        var worker=new BlueprintDesigner(c->responses.remove(),a->a.get("op").getAsString().equals("draft")?"TOOL_ERROR: conflict":DRAFT,()->true,(k,t)->{},3);
        assertEquals("iteration_limit",worker.run("brief","api","site","").status());
    }
    @Test void recordsPromptAssistantTextAndDeniedCalls()throws Exception{
        var entries=new ArrayList<String>();var call=new PlannerToolCall("one","blueprint",jsonArgs(),null);
        var responses=new ArrayDeque<>(List.of(new PlannerResponse("I will inspect it",call,new JsonPrimitive("raw tool preamble")),draft(),done()));
        var worker=new BlueprintDesigner(c->responses.remove(),a->a.get("op").getAsString().equals("lint")?LINT:DRAFT,()->true,(k,t)->entries.add(k+":"+t),4);
        worker.run("build brief","authoring library","site","");
        assertTrue(entries.stream().anyMatch(e->e.startsWith("system:")&&e.contains("authoring library")));
        assertTrue(entries.contains("assistant_raw:\"raw tool preamble\""));assertTrue(entries.contains("assistant:I will inspect it"));assertTrue(entries.stream().anyMatch(e->e.startsWith("tool_result:TOOL_ERROR:")));
        assertTrue(entries.contains("assistant:House ready for review"));
    }
    private static JsonObject jsonArgs(){var a=new JsonObject();a.addProperty("op","commit");return a;}
    @Test void finalLintFailureIsNotReportedAsCompleted()throws Exception{
        var responses=new ArrayDeque<>(List.of(draft(),done()));
        var worker=new BlueprintDesigner(c->responses.remove(),a->a.get("op").getAsString().equals("lint")?"TOOL_ERROR: unloaded_geometry":DRAFT,()->true,(k,t)->{},4);
        assertEquals("needs_review",worker.run("brief","api","site","").status());
    }
}
