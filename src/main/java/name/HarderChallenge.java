package name;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import name.hunterbot.HunterBotControlPayload;
import name.hunterbot.HunterBotManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HarderChallenge implements ModInitializer {

    public static final String MOD_ID = "harderchallenge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Harder Challenge initializing - stages will begin 6 minutes after server start.");

        DifficultyManager.registerCommands();

        PayloadTypeRegistry.clientboundPlay().register(
                BloodMoonStatePayload.TYPE,
                BloodMoonStatePayload.CODEC
        );

        PayloadTypeRegistry.clientboundPlay().register(
                HunterStatePayload.TYPE,
                HunterStatePayload.CODEC
        );

        PayloadTypeRegistry.clientboundPlay().register(
                HunterBotControlPayload.TYPE,
                HunterBotControlPayload.CODEC
        );

        HunterBotManager.initialize();

        // Runs once every server tick (20 ticks/sec).
        ServerTickEvents.END_SERVER_TICK.register(DifficultyManager::onServerTick);
    }
}
