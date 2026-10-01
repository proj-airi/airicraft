package ai.moeru.airicraft.blueprint;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BlueprintServiceLeaseTest {
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
    @Test void worldLeaveClearsPreviewAndRejectsOldWorker(){
        var service=new BlueprintService();String lease=service.beginDesign();service.publishDesigner(lease,"{\"status\":\"running\"}");
        service.worldLeft();assertFalse(service.designActive(lease));
        service.publishDesigner(lease,"{\"status\":\"completed\"}");
        assertTrue(service.dashboardSnapshot().contains("World session ended"));assertFalse(service.dashboardSnapshot().contains("completed"));
    }
}
