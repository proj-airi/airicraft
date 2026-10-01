package ai.moeru.airicraft.blueprint;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionRepairLedgerTest {
    @Test void retainsDebtUntilObservedRestorationAndPreservesOriginalAcrossRepeatedEdits() {
        var ledger=new ConstructionRepairLedger();var p=new ConstructionEscape.Position(1,2,3);
        ledger.beforeEdit(p,"minecraft:stone","wall");
        ledger.beforeEdit(p,"minecraft:air","temporary");
        assertEquals("minecraft:stone",ledger.entries().get(p).requiredState());
        ledger.reconcile(pos->"minecraft:air");assertEquals(1,ledger.entries().size());
        ledger.reconcile(pos->"unknown_unloaded");assertEquals(1,ledger.entries().size());
        ledger.reconcile(pos->"minecraft:stone");assertTrue(ledger.entries().isEmpty());
    }
    @Test void scaffoldDebtRequiresRestoringOriginalAir() {
        var ledger=new ConstructionRepairLedger();var p=new ConstructionEscape.Position(1,2,3);
        ledger.beforeEdit(p,"minecraft:air","scaffold");
        ledger.reconcile(pos->"minecraft:dirt");assertFalse(ledger.entries().isEmpty());
        ledger.reconcile(pos->"minecraft:air");assertTrue(ledger.entries().isEmpty());
    }
}
