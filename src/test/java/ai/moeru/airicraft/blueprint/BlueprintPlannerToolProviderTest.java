package ai.moeru.airicraft.blueprint;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.llm.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BlueprintPlannerToolProviderTest {
    @Test void mainPlannerSeesBriefInterfaceButCannotAuthorDirectly(){
        var provider=new BlueprintPlannerToolProvider(AgentConfig.LlmConfig.defaults(),false);
        var tools=PlannerToolRegistry.isolated(provider);
        assertEquals(2,tools.openAiTools().size());
        var schema=new Gson().toJson(provider.openAiTools());
        assertTrue(schema.contains("design_blueprint"));assertTrue(schema.contains("commit"));assertFalse(schema.contains("rule_docs"));
        var args=new JsonObject();args.addProperty("op","draft");args.addProperty("source","untrusted source");
        assertTrue(provider.execute(new PlannerToolCall("a","blueprint",args,null)).join().startsWith("TOOL_ERROR: use design_blueprint"));
    }
    @Test void badRequestsNeverStartWorker(){
        var provider=new BlueprintPlannerToolProvider(AgentConfig.LlmConfig.defaults(),false);
        var args=JsonParser.parseString("{\"brief\":\"house\",\"site\":[1.5,2,3]}").getAsJsonObject();
        assertTrue(provider.execute(new PlannerToolCall("a","design_blueprint",args,null)).join().contains("integers"));
        args=JsonParser.parseString("{\"brief\":\"house\",\"site\":[1,2,3],\"revise\":\"unknown\"}").getAsJsonObject();
        assertTrue(provider.execute(new PlannerToolCall("b","design_blueprint",args,null)).join().contains("does not match"));
    }
}
