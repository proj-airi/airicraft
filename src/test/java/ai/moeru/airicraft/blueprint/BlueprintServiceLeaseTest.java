package ai.moeru.airicraft.blueprint;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BlueprintServiceLeaseTest {
    @Test void recorderReceivesEntriesAndWorldCancellationButRejectsLateEntries(){
        var service=new BlueprintService();var entries=new java.util.ArrayList<com.google.gson.JsonObject>();service.designerRecorder(entries::add);
        String lease=service.beginDesign();var event=com.google.gson.JsonParser.parseString("{\"kind\":\"brief\",\"text\":\"house\"}").getAsJsonObject();service.recordDesigner(lease,event);event.addProperty("text","changed afterwards");
        service.worldLeft();service.recordDesigner(lease,event);
        assertEquals(2,entries.size());assertEquals("house",entries.getFirst().get("text").getAsString());assertEquals("status",entries.getLast().get("kind").getAsString());assertTrue(entries.getLast().get("text").getAsString().contains("cancelled"));
    }

    @Test void workerExclusivelyOwnsDraftAndCancelledLeaseCannotPublishOrExecute(){
        var service=new BlueprintService();String lease=service.beginDesign();
        assertThrows(IllegalStateException.class,service::beginDesign);
        assertTrue(service.execute(BlueprintDesigner.json("commit")).join().contains("designer_busy"));
        service.publishDesigner(lease,"{\"status\":\"running\"}");
        service.endDesign(lease,true);
        service.publishDesigner(lease,"{\"status\":\"completed\"}");
        assertFalse(service.dashboardSnapshot().contains("completed"));
        assertTrue(service.executeDesigner(lease,BlueprintDesigner.json("draft")).isCompletedExceptionally());
        String next=service.beginDesign();assertNotEquals(lease,next);assertTrue(service.designActive(next));
    }
    @Test void samplingPreservesPublishedDraftAndAdvice() throws Exception {
        var service=new BlueprintService();
        var draft=new Blueprint(com.google.gson.JsonParser.parseString("{\"id\":\"house\"}").getAsJsonObject(),null);
        var lint=com.google.gson.JsonParser.parseString("{\"rules\":[]}");
        for(var entry:java.util.Map.of("draft",draft,"source","function design(input) {}","lastLint",lint,"revision",4).entrySet()) {
            var field=BlueprintService.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(service,entry.getValue());
        }
        service.rememberTerrain(new com.google.gson.JsonObject(),java.util.Map.of("0,0",13),net.minecraft.util.math.BlockPos.ORIGIN,null);
        for(var entry:java.util.Map.of("draft",draft,"source","function design(input) {}","lastLint",lint,"revision",4).entrySet()) {
            var field=BlueprintService.class.getDeclaredField(entry.getKey());field.setAccessible(true);assertEquals(entry.getValue(),field.get(service));
        }
    }
    @Test void worldLeaveClearsPreviewAndRejectsOldWorker(){
        var service=new BlueprintService();String lease=service.beginDesign();service.publishDesigner(lease,"{\"status\":\"running\"}");
        service.worldLeft();assertFalse(service.designActive(lease));
        service.publishDesigner(lease,"{\"status\":\"completed\"}");
        assertTrue(service.dashboardSnapshot().contains("World session ended"));assertFalse(service.dashboardSnapshot().contains("completed"));
    }
}
