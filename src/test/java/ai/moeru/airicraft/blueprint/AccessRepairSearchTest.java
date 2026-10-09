package ai.moeru.airicraft.blueprint;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AccessRepairSearchTest {
    private static final ConstructionEscape.Position A=new ConstructionEscape.Position(0,0,0);
    private static final ConstructionEscape.Position B=new ConstructionEscape.Position(0,1,0);
    private static final AccessRepairSearch.Edit LOWER=new AccessRepairSearch.Edit(A,"stone","air",8);
    private static final AccessRepairSearch.Edit UPPER=new AccessRepairSearch.Edit(B,"stone","air",8);
    private AccessRepairSearch.Model model(boolean secondReachable) {
        return new AccessRepairSearch.Model(){
            public List<AccessRepairSearch.Edit> executableEdits(List<AccessRepairSearch.Edit> path) {
                if(path.isEmpty())return List.of(LOWER);
                return secondReachable && path.size()==1?List.of(UPPER):List.of();
            }
            public boolean goal(List<AccessRepairSearch.Edit> path){return path.size()==2;}
            public double estimate(List<AccessRepairSearch.Edit> path){return 2-path.size();}
            public Object stateKey(List<AccessRepairSearch.Edit> path){return path;}
        };
    }
    @Test void findsTwoBlockOpeningButNeverUsesAnUnexecutableSecondEdit() {
        assertEquals(List.of(LOWER,UPPER),AccessRepairSearch.find(model(true),new AccessRepairSearch.Limits(2,8,20)).edits());
        assertFalse(AccessRepairSearch.find(model(false),new AccessRepairSearch.Limits(2,8,20)).found());
    }
    @Test void obeysDepthAndExpansionBounds() {
        assertFalse(AccessRepairSearch.find(model(true),new AccessRepairSearch.Limits(1,8,20)).found());
        var result=AccessRepairSearch.find(model(true),new AccessRepairSearch.Limits(6,30,1));
        assertFalse(result.found());assertEquals(1,result.expanded());
    }
    @Test void prefersCheaperCompleteRepair() {
        var expensive=new AccessRepairSearch.Edit(A,"stone","air",20);
        var scaffold=new AccessRepairSearch.Edit(B,"air","dirt",2);
        var model=new AccessRepairSearch.Model(){
            public List<AccessRepairSearch.Edit> executableEdits(List<AccessRepairSearch.Edit> p){return List.of(expensive,scaffold);}
            public boolean goal(List<AccessRepairSearch.Edit> p){return !p.isEmpty();}
            public double estimate(List<AccessRepairSearch.Edit> p){return 0;}
            public Object stateKey(List<AccessRepairSearch.Edit> p){return p;}
        };
        assertEquals(List.of(scaffold),AccessRepairSearch.find(model,new AccessRepairSearch.Limits(2,8,20)).edits());
    }
}
