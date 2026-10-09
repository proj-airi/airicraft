package ai.moeru.airicraft.blueprint;

import java.util.HashSet;
import java.util.Set;

/** Failed physical approaches stay excluded until observed construction changes geometry. */
final class FailedRepairApproaches {
    private record Approach(ConstructionEscape.Position position, String before, String after,
                            ConstructionEscape.Position stance, boolean tower) {
        static Approach of(AccessRepairSearch.Edit edit) {
            return new Approach(edit.position(), edit.before(), edit.after(), edit.stance(), edit.tower());
        }
    }
    private final Set<Approach> rejected = new HashSet<>();
    void reject(AccessRepairSearch.Edit edit) { rejected.add(Approach.of(edit)); }
    boolean allows(AccessRepairSearch.Edit edit) { return !rejected.contains(Approach.of(edit)); }
    boolean allows(ConstructionEscape.Position position, String before, String after,
                   ConstructionEscape.Position stance, boolean tower) {
        return !rejected.contains(new Approach(position, before, after, stance, tower));
    }
    void clear() { rejected.clear(); }
}
