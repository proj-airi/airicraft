package ai.moeru.airicraft.blueprint;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BlueprintPublicationTest {
    private static Object field(BlueprintService service,String name) throws Exception {
        var f=BlueprintService.class.getDeclaredField(name);f.setAccessible(true);return f.get(service);
    }
    private static void publish(BlueprintService service) throws Exception {
        var m=BlueprintService.class.getDeclaredMethod("publish",long.class);m.setAccessible(true);m.invoke(service,0L);
    }
    @SuppressWarnings("unchecked")
    @Test void unchangedMaintenanceReusesPublishedSnapshotButIntegrityAndErrorsRemainFresh() throws Exception {
        var service=new BlueprintService();
        var designs=(Map<String,JsonObject>)field(service,"savedDesigns");
        var design=new JsonObject();design.addProperty("source","x".repeat(100_000));designs.put("house",design);
        publish(service);var first=field(service,"dashboardSnapshot");
        publish(service);assertSame(first,field(service,"dashboardSnapshot"),"Unchanged maintenance should not rebuild the design payload");
        var integrity=(Map<String,JsonObject>)field(service,"integrity");
        var check=new JsonObject();check.addProperty("mismatchCount",1);integrity.put("house",check);
        publish(service);
        var snapshot=JsonParser.parseString(service.dashboardSnapshot()).getAsJsonObject();
        assertEquals(1,snapshot.getAsJsonObject("integrity").getAsJsonObject("house").get("mismatchCount").getAsInt());
        assertEquals(100_000,snapshot.getAsJsonArray("savedDesigns").get(0).getAsJsonObject().get("source").getAsString().length());
        var error=BlueprintService.class.getDeclaredField("storageError");error.setAccessible(true);error.set(service,"disk \"error\"");
        publish(service);assertEquals("disk \"error\"",JsonParser.parseString(service.dashboardSnapshot()).getAsJsonObject().get("storageError").getAsString());
        service.worldLeft();assertFalse(service.dashboardSnapshot().contains("mismatchCount"));
    }
    @SuppressWarnings("unchecked")
    @Test void everyControlPublicationRefreshesDesignEvenAtSameRevision() throws Exception {
        var service=new BlueprintService();
        var designs=(Map<String,JsonObject>)field(service,"savedDesigns");
        var design=new JsonObject();design.addProperty("source","initial");designs.put("house",design);
        publish(service);
        var publish=BlueprintService.class.getDeclaredMethod("publish",long.class,String.class);publish.setAccessible(true);
        for(String op:java.util.List.of("draft","load","commit","sample","lint","save","get")){
            design.addProperty("source",op);
            publish.invoke(service,0L,op);
            assertEquals(op,JsonParser.parseString(service.dashboardSnapshot()).getAsJsonObject()
                .getAsJsonArray("savedDesigns").get(0).getAsJsonObject().get("source").getAsString());
        }
        Object stable=field(service,"dashboardSnapshot");
        publish.invoke(service,0L,"maintenance");assertSame(stable,field(service,"dashboardSnapshot"));
        service.worldLeft();
        assertNull(field(service,"dashboardDesignJson"));
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->publish.invoke(service,0L,"maintenance"));
        assertTrue(service.dashboardSnapshot().contains("World closed"));
    }
}
