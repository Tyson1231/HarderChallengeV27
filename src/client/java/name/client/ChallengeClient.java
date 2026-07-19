package name.client;

import name.BloodMoonStatePayload;
import name.HarderChallenge;
import name.HunterStatePayload;
import name.hunterbot.HunterBotControlPayload;
import name.client.hunterbot.HunterBotClientState;
import name.client.hunterbot.HunterBotController;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.api.ClientModInitializer;

public final class ChallengeClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(
                BloodMoonStatePayload.TYPE,
                (payload, context) -> BloodMoonClientState.setActive(payload.active())
        );

        ClientPlayNetworking.registerGlobalReceiver(
                HunterBotControlPayload.TYPE,
                (payload, context) -> {
                    HarderChallenge.LOGGER.info(
                            "RECEIVED HUNTER PACKET: {} seq={}",
                            payload.mode(),
                            payload.sequence()
                    );

                    context.client().execute(() ->
                            HunterBotClientState.accept(payload)
                    );
                }
        );

        HarderChallenge.LOGGER.info("HUNTER RECEIVER REGISTERED");

        ClientPlayNetworking.registerGlobalReceiver(
                HunterBotControlPayload.TYPE,
                (payload, context) -> {
                    System.out.println("RECEIVED HUNTER PACKET: " + payload.mode() + " seq=" + payload.sequence());
                    context.client().execute(() -> HunterBotClientState.accept(payload));
                }
        );
        HunterBotController.initialize();
        ChallengeHud.initialize();
        System.out.println("========== HARDER CHALLENGE CLIENT LOADED ==========");
    }
}
