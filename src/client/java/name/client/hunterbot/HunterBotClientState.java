package name.client.hunterbot;

import name.HarderChallenge;
import name.hunterbot.HunterBotControlPayload;

/** Shared hand-off point between networking and the incoming Baritone process. */
public final class HunterBotClientState {
    private static volatile HunterBotControlPayload latest;

    private HunterBotClientState() {
    }

    public static void accept(HunterBotControlPayload payload) {
        HunterBotControlPayload previous = latest;
        latest = payload;
        if (previous == null || previous.mode() != payload.mode()) {
            HarderChallenge.LOGGER.info("Hunter bot mode: {} (target={}, seq={})",
                    payload.mode(), payload.targetName(), payload.sequence());
        }
    }

    public static HunterBotControlPayload latest() {
        return latest;
    }
}
