package ai.moeru.airicraft.blueprint;

/** Pure scoring of a candidate building footprint from sampled columns; lower is better. */
public final class SiteScore {
    private SiteScore() { }
    public record Result(double score,int range,int obstacleBlocks,int wetColumns,double meanHeight,boolean usable) { }
    /** Footprints with more than a quarter of their columns under water are not usable. */
    public static Result score(int[][] heights,int[][] obstacles,boolean[][] wet) {
        int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE,columns=0,wetColumns=0,blocks=0;double sum=0;
        for(int x=0;x<heights.length;x++)for(int z=0;z<heights[x].length;z++) {
            min=Math.min(min,heights[x][z]);max=Math.max(max,heights[x][z]);sum+=heights[x][z];columns++;
            blocks+=obstacles[x][z];if(wet[x][z])wetColumns++;
        }
        if(columns==0)throw new IllegalArgumentException("empty_footprint");
        int range=max-min;
        // Slope costs the most (foundation columns, digging); each tree block must be felled; water is nearly disqualifying.
        double score=range*3.0+blocks*0.5+wetColumns*8.0;
        return new Result(score,range,blocks,wetColumns,sum/columns,wetColumns*4<=columns);
    }
}
