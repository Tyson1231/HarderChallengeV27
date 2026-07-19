package name.client.hunterbot.navigation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Executes a calculated path through normal client movement controls. */
public final class HunterMovementController {
    private int pathIndex;

    public void begin(List<BlockPos> path, LocalPlayer player) {
        pathIndex = path.size() > 1 ? 1 : 0;
    }

    public boolean tick(Minecraft minecraft, List<BlockPos> path) {
        LocalPlayer player = minecraft.player;
        if (player == null || path.isEmpty() || pathIndex >= path.size()) {
            release(minecraft);
            return true;
        }

        BlockPos next = path.get(pathIndex);
        Vec3 target = Vec3.atCenterOf(next);
        Vec3 delta = target.subtract(player.position());

        if (delta.x * delta.x + delta.z * delta.z < 0.32 * 0.32
                && Math.abs(delta.y) < 1.6) {
            pathIndex++;
            if (pathIndex >= path.size()) {
                release(minecraft);
                return true;
            }
            next = path.get(pathIndex);
            target = Vec3.atCenterOf(next);
            delta = target.subtract(player.position());
        }

        float targetYaw = (float) (Mth.atan2(delta.z, delta.x) * (180.0 / Math.PI)) - 90.0F;
        player.setYRot(rotateToward(player.getYRot(), targetYaw, 18.0F));
        player.setYHeadRot(player.getYRot());

        minecraft.options.keyUp.setDown(true);
        minecraft.options.keySprint.setDown(delta.horizontalDistanceSqr() > 4.0);
        minecraft.options.keyJump.setDown(next.getY() > player.blockPosition().getY() || player.horizontalCollision);
        minecraft.options.keyShift.setDown(false);
        return false;
    }

    public void release(Minecraft minecraft) {
        minecraft.options.keyUp.setDown(false);
        minecraft.options.keyDown.setDown(false);
        minecraft.options.keyLeft.setDown(false);
        minecraft.options.keyRight.setDown(false);
        minecraft.options.keyJump.setDown(false);
        minecraft.options.keySprint.setDown(false);
        minecraft.options.keyShift.setDown(false);
    }

    private static float rotateToward(float current, float target, float maximumStep) {
        float difference = Mth.wrapDegrees(target - current);
        return current + Mth.clamp(difference, -maximumStep, maximumStep);
    }
}
