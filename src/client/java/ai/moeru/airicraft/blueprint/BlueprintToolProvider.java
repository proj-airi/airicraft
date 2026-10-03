package ai.moeru.airicraft.blueprint;
import ai.moeru.airicraft.agent.llm.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

/** Normal planner/control entrypoint; dashboard never invokes this provider. */
public final class BlueprintToolProvider implements PlannerToolProvider {
    @Override public String id(){return "blueprint";}
    @Override public boolean available(){return true;}
    @Override public boolean handles(String name){return "blueprint".equals(name);}
    @Override public boolean isReadTool(String name){return false;}
    @Override public List<Map<String,Object>> openAiTools(){return List.of(toolForProvider("blueprint",
        "Mod-owned semantic blueprint session. Dashboard visualizes drafts read-only. op=draft runs JS function design(input) using Component, Assembly, Solid, Clearance, Room, Door, Window, Floor, Staircase, GableRoof, Foundation, Entrance, WalkRoute, Guardrail, WalkableArea constructors; no world effects. get returns tree/cells. explain uses position (local by default; world=true uses last commit). sample captures 16x16 terrain at origin, passed to next design as input.terrain. commit requires revision and origin, writes direct blocks only in creative Blueprint-* scratch worlds. prepare sets scratch world gameMode (creative by default, or survival), preserving inventory; view teleports camera to position with yaw/pitch; verify compares committed states. lint runs optional rules [{id,source}] defining check(ctx), or bundled advisory rules. rule_docs returns authoring and rule APIs. Lint never blocks commit. Use rule_docs to read the complete authoring API before drafting. Drafts, semantic provenance and committed designs are saved in this world. list discovers saved IDs; load selects blueprintId. Automatic integrity checks flag changed loaded cells; unloaded cells stay unknown. prepare/view are Codex-driver-only diagnostics.",
        propertiesForProvider(propForProvider("op",stringForProvider("draft|get|list|load|explain|sample|commit|prepare|view|verify|save|lint|rule_docs")),
          propForProvider("gameMode",Map.of("type","string","enum",List.of("creative","survival"))),
          propForProvider("rules",Map.of("type","array","items",Map.of("type","object"))),
          propForProvider("blueprintId",stringForProvider("Saved blueprint ID for load.")),propForProvider("source",stringForProvider("JavaScript defining design(input).")),
          propForProvider("position",Map.of("type","array","items",Map.of("type","integer"),"minItems",3,"maxItems",3)),
          propForProvider("origin",Map.of("type","array","items",Map.of("type","integer"),"minItems",3,"maxItems",3)),
          propForProvider("revision",Map.of("type","integer")),propForProvider("world",Map.of("type","boolean")),
          propForProvider("yaw",Map.of("type","number")),propForProvider("pitch",Map.of("type","number"))),List.of("op")));}
    @Override public CompletableFuture<String> execute(PlannerToolCall call) {
        return BlueprintService.instance().execute(call.arguments());
    }
}
