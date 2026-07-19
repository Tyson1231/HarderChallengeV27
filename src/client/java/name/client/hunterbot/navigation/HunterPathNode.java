package name.client.hunterbot.navigation;

import net.minecraft.core.BlockPos;

/** One A* search node. Costs deliberately mirror Baritone's weighted-cost approach. */
final class HunterPathNode implements Comparable<HunterPathNode> {
    final BlockPos position;
    final HunterPathNode parent;
    final double costFromStart;
    final double estimatedRemaining;

    HunterPathNode(BlockPos position, HunterPathNode parent, double costFromStart, double estimatedRemaining) {
        this.position = position;
        this.parent = parent;
        this.costFromStart = costFromStart;
        this.estimatedRemaining = estimatedRemaining;
    }

    double totalCost() {
        return costFromStart + estimatedRemaining;
    }

    @Override
    public int compareTo(HunterPathNode other) {
        return Double.compare(totalCost(), other.totalCost());
    }
}
