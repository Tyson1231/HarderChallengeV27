package name.client.hunterbot.navigation;

import net.minecraft.core.BlockPos;

import java.util.List;

public record HunterPath(List<BlockPos> positions, boolean reachesGoal) {
    public static HunterPath empty() {
        return new HunterPath(List.of(), false);
    }
}
