package name.client.hunterbot.navigation;

import net.minecraft.world.phys.Vec3;

/** Forces replanning when movement input has produced almost no displacement. */
public final class HunterStuckDetector {
    private static final int CHECK_INTERVAL_TICKS = 30;
    private static final double MINIMUM_PROGRESS_SQUARED = 0.20 * 0.20;

    private Vec3 checkpoint;
    private int ticks;

    public boolean tick(Vec3 currentPosition, boolean attemptingMovement) {
        if (!attemptingMovement) {
            reset(currentPosition);
            return false;
        }
        if (checkpoint == null) {
            reset(currentPosition);
            return false;
        }
        if (++ticks < CHECK_INTERVAL_TICKS) {
            return false;
        }

        boolean stuck = currentPosition.distanceToSqr(checkpoint) < MINIMUM_PROGRESS_SQUARED;
        reset(currentPosition);
        return stuck;
    }

    public void reset(Vec3 position) {
        checkpoint = position;
        ticks = 0;
    }
}
