package name.client.hunterbot.navigation;

import net.minecraft.core.BlockPos;

/** A destination accepted by the Hunter planner. */
public record HunterGoal(BlockPos position, int horizontalTolerance) {
    public HunterGoal(BlockPos position) {
        this(position, 1);
    }

    public boolean reached(BlockPos current) {
        int dx = current.getX() - position.getX();
        int dz = current.getZ() - position.getZ();
        return dx * dx + dz * dz <= horizontalTolerance * horizontalTolerance
                && Math.abs(current.getY() - position.getY()) <= 2;
    }
}
