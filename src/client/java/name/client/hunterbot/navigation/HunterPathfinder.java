package name.client.hunterbot.navigation;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Bounded A* planner inspired by Baritone's cost-based path calculation. */
public final class HunterPathfinder {
    private static final int[][] CARDINAL_DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private final HunterMovementEvaluator movementEvaluator = new HunterMovementEvaluator();

    public HunterPath calculate(ClientLevel level, BlockPos start, HunterGoal goal, int maximumVisitedNodes) {
        PriorityQueue<HunterPathNode> open = new PriorityQueue<>();
        Map<BlockPos, Double> bestKnownCost = new HashMap<>();

        HunterPathNode first = new HunterPathNode(start, null, 0.0, heuristic(start, goal.position()));
        open.add(first);
        bestKnownCost.put(start, 0.0);

        HunterPathNode bestPartial = first;
        int visited = 0;

        while (!open.isEmpty() && visited++ < maximumVisitedNodes) {
            HunterPathNode current = open.poll();
            if (current.estimatedRemaining < bestPartial.estimatedRemaining) {
                bestPartial = current;
            }
            if (goal.reached(current.position)) {
                return new HunterPath(reconstruct(current), true);
            }

            for (int[] direction : CARDINAL_DIRECTIONS) {
                HunterMovementEvaluator.Move move = movementEvaluator.evaluate(
                        level, current.position, direction[0], direction[1]);
                if (move == null) {
                    continue;
                }

                double nextCost = current.costFromStart + move.cost();
                Double knownCost = bestKnownCost.get(move.destination());
                if (knownCost != null && knownCost <= nextCost) {
                    continue;
                }

                bestKnownCost.put(move.destination(), nextCost);
                open.add(new HunterPathNode(
                        move.destination(),
                        current,
                        nextCost,
                        heuristic(move.destination(), goal.position())
                ));
            }
        }

        return new HunterPath(reconstruct(bestPartial), false);
    }

    private static double heuristic(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX();
        double dy = from.getY() - to.getY();
        double dz = from.getZ() - to.getZ();
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 1.35;
    }

    private static List<BlockPos> reconstruct(HunterPathNode end) {
        List<BlockPos> result = new ArrayList<>();
        for (HunterPathNode node = end; node != null; node = node.parent) {
            result.add(node.position);
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }
}
