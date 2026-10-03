package ai.moeru.airicraft.blueprint;

import java.util.*;

/** Beam-bounded best-first search over legal geometry edits, never over movement ticks. */
public final class AccessRepairSearch {
    private AccessRepairSearch() { }
    public record Edit(ConstructionEscape.Position position,String before,String after,double cost,ConstructionEscape.Position stance,boolean tower) {
        public Edit(ConstructionEscape.Position position,String before,String after,double cost,ConstructionEscape.Position stance){this(position,before,after,cost,stance,false);}
        public Edit(ConstructionEscape.Position position,String before,String after,double cost){this(position,before,after,cost,null);}
        public Edit {
            Objects.requireNonNull(position);Objects.requireNonNull(before);Objects.requireNonNull(after);
            if(!Double.isFinite(cost) || cost<=0 || before.equals(after))throw new IllegalArgumentException("invalid_repair_edit");
        }
    }
    public record Limits(int depth,int beam,int expansions) {
        public Limits {if(depth<1 || depth>16 || beam<1 || beam>256 || expansions<1 || expansions>4096)throw new IllegalArgumentException("invalid_repair_limits");}
    }
    public interface Model {
        /** Only edits executable from a reachable stance in this hypothetical world, with available resources. */
        List<Edit> executableEdits(List<Edit> path);
        boolean goal(List<Edit> path);
        double estimate(List<Edit> path);
        /** Must include geometry, reachable region, and resource usage that affects future legality. */
        Object stateKey(List<Edit> path);
    }
    public record Result(boolean found,List<Edit> edits,double cost,int expanded,String reason) { }
    private record Node(List<Edit> path,double cost,double score,long order) { }
    public static Result find(Model model,Limits limits) {
        var order=Comparator.comparingDouble(Node::score).thenComparingDouble(Node::cost).thenComparingLong(Node::order);
        var frontier=new ArrayList<Node>();frontier.add(new Node(List.of(),0,0,0));
        var best=new HashMap<Object,Double>();best.put(model.stateKey(List.of()),0.0);
        int expanded=0;long sequence=0;
        while(!frontier.isEmpty()) {
            frontier.sort(order);var node=frontier.removeFirst();
            if(model.goal(node.path()))return new Result(true,node.path(),node.cost(),expanded,"found");
            if(expanded>=limits.expansions())return new Result(false,List.of(),0,expanded,"expansion_limit");
            if(node.path().size()>=limits.depth())continue;
            expanded++;
            for(var edit:model.executableEdits(node.path())) {
                var path=new ArrayList<>(node.path());path.add(edit);var immutable=List.copyOf(path);
                double cost=node.cost()+edit.cost();var key=model.stateKey(immutable);
                if(best.getOrDefault(key,Double.POSITIVE_INFINITY)<=cost)continue;
                best.put(key,cost);double estimate=model.estimate(immutable);
                if(!Double.isFinite(estimate) || estimate<0)throw new IllegalArgumentException("invalid_repair_estimate");
                frontier.add(new Node(immutable,cost,cost+estimate,++sequence));
            }
            frontier.sort(order);
            if(frontier.size()>limits.beam())frontier.subList(limits.beam(),frontier.size()).clear();
        }
        return new Result(false,List.of(),0,expanded,"bounded_search_exhausted");
    }
}
