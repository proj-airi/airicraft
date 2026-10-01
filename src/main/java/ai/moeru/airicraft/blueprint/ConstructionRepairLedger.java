package ai.moeru.airicraft.blueprint;

import java.util.*;
import java.util.function.Function;

/** Restoration obligations are recorded before dispatch and cleared only by world observation. */
public final class ConstructionRepairLedger {
    public record Debt(String requiredState,String component,String intermediateState) { }
    private final Map<ConstructionEscape.Position,Debt> debts=new LinkedHashMap<>();
    public void restore(Map<ConstructionEscape.Position,Debt> saved) {
        if(!debts.isEmpty())throw new IllegalStateException("repair_ledger_already_initialized");
        debts.putAll(saved);
    }
    public void beforeEdit(ConstructionEscape.Position position,String requiredState,String component) {
        beforeEdit(position,requiredState,component,null);
    }
    public void beforeEdit(ConstructionEscape.Position position,String requiredState,String component,String intermediateState) {
        Objects.requireNonNull(position);Objects.requireNonNull(requiredState);
        debts.compute(position,(p,old)->new Debt(old==null?requiredState:old.requiredState(),old==null?component:old.component(),intermediateState));
    }
    public void reconcile(Function<ConstructionEscape.Position,String> observedState) {
        debts.entrySet().removeIf(entry->entry.getValue().requiredState().equals(observedState.apply(entry.getKey())));
    }
    public Map<ConstructionEscape.Position,Debt> entries(){return Collections.unmodifiableMap(new LinkedHashMap<>(debts));}
}
