package name.client.hunterbot.process;

import name.client.hunterbot.navigation.HunterGoal;
import name.client.hunterbot.navigation.HunterNavigator;
import name.hunterbot.HunterBotControlPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Hunter-only replacement for Baritone's generic process manager.
 * More processes (mine/build/elytra/combat) plug into this class later.
 */
public final class HunterProcessManager {
    private final HunterNavigator navigator = new HunterNavigator();
    private int lastSequence = Integer.MIN_VALUE;

    public void tick(Minecraft minecraft, HunterBotControlPayload payload) {
        if (payload == null) {
            return;
        }

        if (payload.sequence() != lastSequence) {
            lastSequence = payload.sequence();
            acceptCommand(minecraft, payload);
        }

        navigator.tick(minecraft);
    }

    private void acceptCommand(Minecraft minecraft, HunterBotControlPayload payload) {
        switch (payload.mode()) {
            case VISIBLE_PURSUIT -> navigator.setGoal(new HunterGoal(
                    BlockPos.containing(payload.x(), payload.y(), payload.z()), 1));
            case LAST_SEEN_SEARCH -> navigator.setGoal(new HunterGoal(
                    BlockPos.containing(payload.x(), payload.y(), payload.z()), 2));
            case IDLE, CANCEL -> navigator.cancel(minecraft);
        }
    }
}
