package ai.moeru.airicraft.blueprint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionRepairStoreTest {
    @TempDir Path dir;
    @Test void reloadsAndClearsObligationsAcrossStoreInstances() throws Exception {
        var path=dir.resolve("build.json");var original=Map.of(new ConstructionEscape.Position(2,3,4),new ConstructionRepairLedger.Debt("minecraft:air","temporary_access","minecraft:dirt"));
        new ConstructionRepairStore(path).save(original);
        assertEquals(original,new ConstructionRepairStore(path).load());
        new ConstructionRepairStore(path).save(Map.of());
        assertTrue(new ConstructionRepairStore(path).load().isEmpty());
    }
    @Test void corruptJournalFailsInsteadOfForgettingRepairDebt() throws Exception {
        var path=dir.resolve("build.json");Files.writeString(path,"{broken");
        assertThrows(java.io.IOException.class,()->new ConstructionRepairStore(path).load());
        assertEquals("{broken",Files.readString(path));
    }
}
