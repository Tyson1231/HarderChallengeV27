package name.client.hunterbot;

import name.client.hunterbot.process.HunterProcessManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** Client bootstrap for the autonomous Hunter account. */
public final class HunterBotController {
    private static final HunterProcessManager PROCESSES = new HunterProcessManager();

    private HunterBotController() {
    }

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client ->
                PROCESSES.tick(client, HunterBotClientState.latest()));
    }
}
