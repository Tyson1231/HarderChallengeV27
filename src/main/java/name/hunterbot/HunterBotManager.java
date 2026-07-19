package name.hunterbot;

import com.mojang.brigadier.arguments.StringArgumentType;
import name.HarderChallenge;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Phase-one control channel for the real Baritone Hunter client.
 *
 * This class deliberately does not implement pathing. It owns bot identity,
 * target identity, server-side visibility, last-seen memory, and the packet
 * stream that the ported Baritone client will consume.
 */
public final class HunterBotManager {

    private static final int UPDATE_INTERVAL_TICKS = 2;
    private static final int LOST_SIGHT_REPEATS = 10;

    private static String botName = "HunterBot";
    private static UUID botId;
    private static UUID targetId;
    private static Vec3 lastSeenPosition;
    private static String lastSeenDimension = "";
    private static boolean hadSight;
    private static int lostSightPacketsRemaining;
    private static int tickCounter;
    private static int sequence;

    private HunterBotManager() {
    }

    public static void initialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player.getGameProfile().name().equalsIgnoreCase(botName)) {
                botId = player.getUUID();
                HarderChallenge.LOGGER.info("Hunter bot client connected as {} ({})", player.getGameProfile().name(), botId);
                send(player, idlePayload());
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (handler.getPlayer().getUUID().equals(botId)) {
                HarderChallenge.LOGGER.info("Hunter bot client disconnected.");
                botId = null;
                hadSight = false;
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(HunterBotManager::tick);
        registerCommands();
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("hunterbot")
                        .requires(source -> true)
                        .then(Commands.literal("status")
                                .executes(context -> {
                                    context.getSource().sendSuccess(
                                            () -> Component.literal(status(context.getSource().getServer())), false);
                                    return 1;
                                }))
                        .then(Commands.literal("target")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(context -> {
                                            MinecraftServer server = context.getSource().getServer();
                                            String name = StringArgumentType.getString(context, "player");
                                            ServerPlayer target = server.getPlayerList().getPlayerByName(name);
                                            if (target == null) {
                                                context.getSource().sendFailure(Component.literal("Player not found: " + name));
                                                return 0;
                                            }
                                            if (target.getUUID().equals(botId)) {
                                                context.getSource().sendFailure(Component.literal("The Hunter bot cannot target itself."));
                                                return 0;
                                            }
                                            targetId = target.getUUID();
                                            lastSeenPosition = null;
                                            lastSeenDimension = "";
                                            hadSight = false;
                                            lostSightPacketsRemaining = 0;
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Hunter bot target set to " + target.getGameProfile().name() + "."), false);
                                            return 1;
                                        })))
                        .then(Commands.literal("clear")
                                .executes(context -> {
                                    targetId = null;
                                    lastSeenPosition = null;
                                    hadSight = false;
                                    lostSightPacketsRemaining = 0;
                                    ServerPlayer bot = findBot(context.getSource().getServer());
                                    if (bot != null) {
                                        send(bot, cancelPayload());
                                    }
                                    context.getSource().sendSuccess(() -> Component.literal("Hunter bot target cleared."), false);
                                    return 1;
                                }))
                        .then(Commands.literal("name")
                                .then(Commands.argument("username", StringArgumentType.word())
                                        .executes(context -> {
                                            botName = StringArgumentType.getString(context, "username");
                                            botId = null;
                                            ServerPlayer possibleBot = context.getSource().getServer().getPlayerList().getPlayerByName(botName);
                                            if (possibleBot != null) {
                                                botId = possibleBot.getUUID();
                                            }
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Hunter bot username set to " + botName + "."), false);
                                            return 1;
                                        })))));
    }

    private static void tick(MinecraftServer server) {
        if (++tickCounter % UPDATE_INTERVAL_TICKS != 0) {
            return;
        }

        ServerPlayer bot = findBot(server);
        if (bot == null) {
            return;
        }

        if (targetId == null) {
            send(bot, idlePayload());
            return;
        }

        ServerPlayer target = server.getPlayerList().getPlayer(targetId);
        if (target == null || !target.isAlive()) {
            send(bot, cancelPayload());
            return;
        }

        boolean sameDimension = bot.level() == target.level();
        boolean visible = sameDimension && bot.hasLineOfSight(target);

        if (visible) {
            Vec3 position = target.position();
            lastSeenPosition = position;
            lastSeenDimension = target.level().dimension().identifier().toString();
            hadSight = true;
            lostSightPacketsRemaining = LOST_SIGHT_REPEATS;
            send(bot, new HunterBotControlPayload(
                    nextSequence(),
                    HunterBotControlPayload.Mode.VISIBLE_PURSUIT,
                    target.getGameProfile().name(),
                    lastSeenDimension,
                    position.x,
                    position.y,
                    position.z,
                    target.getBbWidth(),
                    target.getBbHeight(),
                    true,
                    true
            ));
            return;
        }

        if (hadSight && lastSeenPosition != null && lostSightPacketsRemaining-- > 0) {
            send(bot, new HunterBotControlPayload(
                    nextSequence(),
                    HunterBotControlPayload.Mode.LAST_SEEN_SEARCH,
                    target.getGameProfile().name(),
                    lastSeenDimension,
                    lastSeenPosition.x,
                    lastSeenPosition.y,
                    lastSeenPosition.z,
                    target.getBbWidth(),
                    target.getBbHeight(),
                    true,
                    true
            ));
            return;
        }

        if (hadSight) {
            hadSight = false;
            send(bot, cancelPayload());
        }
    }

    private static ServerPlayer findBot(MinecraftServer server) {
        if (botId != null) {
            ServerPlayer byId = server.getPlayerList().getPlayer(botId);
            if (byId != null) {
                return byId;
            }
        }
        ServerPlayer byName = server.getPlayerList().getPlayerByName(botName);
        if (byName != null) {
            botId = byName.getUUID();
        }
        return byName;
    }

    private static void send(ServerPlayer bot, HunterBotControlPayload payload) {
        if (ServerPlayNetworking.canSend(bot, HunterBotControlPayload.TYPE)) {

            HarderChallenge.LOGGER.info(
                    "Sending {} to {} seq={}",
                    payload.mode(),
                    bot.getGameProfile().name(),
                    payload.sequence()
            );

            ServerPlayNetworking.send(bot, payload);
        }
    }

    private static HunterBotControlPayload idlePayload() {
        return new HunterBotControlPayload(nextSequence(), HunterBotControlPayload.Mode.IDLE,
                "", "", 0, 0, 0, 0, 0, false, false);
    }

    private static HunterBotControlPayload cancelPayload() {
        return new HunterBotControlPayload(nextSequence(), HunterBotControlPayload.Mode.CANCEL,
                "", "", 0, 0, 0, 0, 0, false, false);
    }

    private static int nextSequence() {
        return ++sequence;
    }

    private static String status(MinecraftServer server) {
        ServerPlayer bot = findBot(server);
        ServerPlayer target = targetId == null ? null : server.getPlayerList().getPlayer(targetId);
        return "Bot=" + (bot == null ? "offline (expected name: " + botName + ")" : bot.getGameProfile().name())
                + " | Target=" + (target == null ? "none/offline" : target.getGameProfile().name())
                + " | Sight=" + hadSight
                + " | LastSeen=" + (lastSeenPosition == null ? "none" : String.format("%.1f %.1f %.1f", lastSeenPosition.x, lastSeenPosition.y, lastSeenPosition.z));
    }
}
