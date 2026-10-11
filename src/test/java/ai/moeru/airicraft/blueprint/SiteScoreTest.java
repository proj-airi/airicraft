package ai.moeru.airicraft.blueprint;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SiteScoreTest {
    private static int[][] flat(int w,int d,int y){var h=new int[w][d];for(var row:h)java.util.Arrays.fill(row,y);return h;}
    @Test void flatClearGroundScoresZero(){
        var r=SiteScore.score(flat(4,4,70),new int[4][4],new boolean[4][4]);
        assertEquals(0,r.score());assertTrue(r.usable());
    }
    @Test void slopeAndTreesCostMoreThanFlat(){
        var slope=flat(4,4,70);slope[3][3]=74;
        var trees=new int[4][4];trees[0][0]=6;
        assertTrue(SiteScore.score(slope,new int[4][4],new boolean[4][4]).score()>SiteScore.score(flat(4,4,70),trees,new boolean[4][4]).score());
        assertEquals(4,SiteScore.score(slope,new int[4][4],new boolean[4][4]).range());
    }
    @Test void mostlyWaterIsNotUsable(){
        var wet=new boolean[4][4];for(int x=0;x<4;x++)for(int z=0;z<2;z++)wet[x][z]=true;
        assertFalse(SiteScore.score(flat(4,4,70),new int[4][4],wet).usable());
    }
}
