package name.client.hunterbot.navigation;

import name.HarderChallenge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.List;

/** Owns planning, following, replanning and stuck recovery. */
public final class HunterNavigator {
    private static final int MAXIMUM_VISITED_NODES = 12_000;
    private static final int REPLAN_INTERVAL_TICKS = 20;
    private static final int TARGET_MOVE_REPLAN_DISTANCE_SQUARED = 3 * 3;

    private final HunterPathfinder pathfinder = new HunterPathfinder();
    private final HunterMovementController movement = new HunterMovementController();
    private final HunterStuckDetector stuckDetector = new HunterStuckDetector();

    private HunterGoal goal;
    private List<BlockPos> path = List.of();
    private BlockPos plannedGoal;
    private int ticksSincePlan;

    public void setGoal(HunterGoal newGoal) {
        goal = newGoal;
    }

    public void cancel(Minecraft minecraft) {
        goal = null;
        path = List.of();
        plannedGoal = null;
        movement.release(minecraft);
    }

    public void tick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            movement.release(minecraft);
            return;
        }

        if (goal == null) {
            return;
        }

        BlockPos current = player.blockPosition();
        if (goal.reached(current)) {
            movement.release(minecraft);
            path = List.of();
            return;
        }

        boolean targetMoved = plannedGoal == null
                || plannedGoal.distSqr(goal.position()) >= TARGET_MOVE_REPLAN_DISTANCE_SQUARED;
        boolean stuck = stuckDetector.tick(player.position(), !path.isEmpty());
        boolean expired = ++ticksSincePlan >= REPLAN_INTERVAL_TICKS;

        if (path.isEmpty() || targetMoved || stuck || expired) {
            replan(minecraft, current);
        }

        if (movement.tick(minecraft, path)) {
            path = List.of();
        }
    }

    private void replan(Minecraft minecraft, BlockPos start) {
        HunterPath result = pathfinder.calculate(minecraft.level, start, goal, MAXIMUM_VISITED_NODES);
        path = result.positions();
        plannedGoal = goal.position();
        ticksSincePlan = 0;
        stuckDetector.reset(minecraft.player.position());
        movement.begin(path, minecraft.player);

        HarderChallenge.LOGGER.debug("Hunter path planned: {} nodes, reachesGoal={}",
                path.size(), result.reachesGoal());
    }
}
