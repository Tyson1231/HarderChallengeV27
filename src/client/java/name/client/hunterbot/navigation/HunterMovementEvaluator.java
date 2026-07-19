package name.client.hunterbot.navigation;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Converts the local block environment into legal Hunter movement edges.
 * Initial traversal supports walking, one-block jumps, safe drops and water.
 */
public final class HunterMovementEvaluator {
    private static final int MAX_SAFE_DROP = 3;

    public Move evaluate(ClientLevel level, BlockPos from, int dx, int dz) {
        BlockPos horizontal = from.offset(dx, 0, dz);

        if (canStand(level, horizontal)) {
            return new Move(horizontal, 1.0);
        }

        BlockPos jump = horizontal.above();
        if (isPassable(level, from.above(2)) && canStand(level, jump)) {
            return new Move(jump, 1.65);
        }

        for (int drop = 1; drop <= MAX_SAFE_DROP; drop++) {
            BlockPos candidate = horizontal.below(drop);
            if (canStand(level, candidate)) {
                return new Move(candidate, 1.0 + drop * 0.45);
            }
            if (!isPassable(level, candidate)) {
                break;
            }
        }

        return null;
    }

    public boolean canStand(ClientLevel level, BlockPos feet) {
        return isPassable(level, feet)
                && isPassable(level, feet.above())
                && isSupporting(level, feet.below());
    }

    public boolean isPassable(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir()
                || state.getCollisionShape(level, pos).isEmpty()
                || !state.getFluidState().isEmpty();
    }

    private boolean isSupporting(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
    }

    public record Move(BlockPos destination, double cost) {
    }
}
