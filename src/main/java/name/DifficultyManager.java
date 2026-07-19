package name;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Drives the timed difficulty ramp. See README for the full stage list.
 *
 * NOTE ON CONFIDENCE: class names here (ServerLevel, Player, MobEffectInstance,
 * Attributes, Component, Commands.literal, AttributeModifier) were verified
 * against the current official Fabric docs for Minecraft 26.1/26.2 at the time
 * this was written. A handful of method names below (marked inline) are my
 * best-informed guess based on stable Mojang-mapping conventions but were NOT
 * individually verified - if the compiler flags one, start typing the object
 * + "." in IntelliJ and let autocomplete show you the real method name; it's
 * almost always a near-miss (e.g. getDayTime vs dayTime) rather than something
 * totally different.
 */
public class DifficultyManager {

    private static final int TICKS_PER_MINUTE = 20 * 60;
    private static final int STAGE_INTERVAL_MINUTES = 6;
    private static final int MAX_STAGE = 12;

    private static final Identifier DAMAGE_MODIFIER_ID =
            Identifier.fromNamespaceAndPath(HarderChallenge.MOD_ID, "double_damage");

    private static long tickCounter = 0;
    private static int currentStage = 0;
    private static boolean quietRulesApplied = false;

    public static int getStage() {
        return currentStage;
    }

    public static void onServerTick(MinecraftServer server) {
        if (!quietRulesApplied) {
            // Prevent internal particle/effect/item commands from flooding the player's chat.
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamerule sendCommandFeedback false");
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamerule logAdminCommands false");
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamerule commandBlockOutput false");
            quietRulesApplied = true;
        }

        if (HunterManager.isActive()) {
            HunterManager.tick(server);
            return;
        }

        tickCounter++;

        int elapsedMinutes = (int) (tickCounter / TICKS_PER_MINUTE);
        int newStage = Math.min(elapsedMinutes / STAGE_INTERVAL_MINUTES, MAX_STAGE);

        if (newStage != currentStage) {
            currentStage = newStage;
            announceStage(server, currentStage);
        }

        EventDirector.tick(server, tickCounter, currentStage);
        EvolutionMutations.tick(server, currentStage, tickCounter);

        if (currentStage <= 0) {
            return;
        }

        if (tickCounter % 100 == 0) {
            for (ServerLevel level : server.getAllLevels()) {
                applyHostileMobEffects(level);
                enforcePermanentNight(level);
            }
            applyPlayerEffects(server);
        }

        if (currentStage >= 9 && tickCounter % 60 == 0) {
            forceHostileSpawns(server);
        }

        if (currentStage >= 8 && tickCounter % (TICKS_PER_MINUTE * 2) == 0) {
            pulseDarkness(server);
        }

        applyStageEquipment(server);
        applyInvisibilityCycle(server);
    }

    private static void announceStage(MinecraftServer server, int stage) {
        String message = switch (stage) {
            case 1 -> "The Hunt begins: hostile mobs gain Speed I.";
            case 2 -> "Endless Night begins.";
            case 3 -> "Evolution begins: Mutant Zombies can now emerge.";
            case 4 -> "The Arsenal: armed mobs gain Sharpness I iron swords.";
            case 5 -> "Mutant Skeletons emerge; food now restores half as much hunger.";
            case 6 -> "You now have permanent Slowness I.";
            case 7 -> "Mutant Creepers emerge; skeletons fire twice as fast.";
            case 8 -> "Iron Legion: hostile mobs receive full iron armor.";
            case 9 -> "Mutant Spiders emerge and hostile mobs spawn constantly.";
            case 10 -> "Hostile mobs now have Strength II.";
            case 11 -> "Phantoms: mobs vanish for 10 seconds every minute.";
            case 12 -> "Final stage: mobs gain Speed II, Strength III, and Resistance I.";
            default -> null;
        };
        if (message != null) {
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack(),
                    "title @a actionbar {\"text\":\"STAGE " + stage + " - " + message.replace("\"", "") + "\",\"color\":\"gold\",\"bold\":true}"
            );

            // Custom cinematic horn supplied with the mod. The pitch drops slightly as stages rise.
            float hornPitch = Math.max(0.70F, 1.0F - (stage - 1) * 0.025F);
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack(),
                    "playsound harderchallenge:cinematic_horn master @a ~ ~ ~ 0.85 " + hornPitch + " 1.0"
            );
        }
    }

    private static void applyHostileMobEffects(ServerLevel level) {
        int speedAmplifier = currentStage >= 12 ? 1 : 0;
        int strengthAmplifier = currentStage >= 12 ? 2 : (currentStage >= 10 ? 1 : -1);
        boolean resistance = currentStage >= 12;

        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof LivingEntity living) || !(entity instanceof Monster)) {
                continue;
            }

            if (currentStage >= 1) {
                applyInfinite(living, MobEffects.SPEED, speedAmplifier);
            }
            if (strengthAmplifier >= 0) {
                applyInfinite(living, MobEffects.STRENGTH, strengthAmplifier);
            }
            if (resistance) {
                applyInfinite(living, MobEffects.RESISTANCE, 0);
            }
            if (currentStage >= 3 && living.getType() == EntityTypes.ZOMBIE) {
                applyGiantZombieStats(living);
            }

            // Keep every skeleton-family mob armed. Forced spawning and later equipment
            // passes must never leave skeletons trying to use a bow goal with empty hands.
            if (living instanceof AbstractSkeleton skeleton
                    && !skeleton.getMainHandItem().is(Items.BOW)) {
                skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            }
        }
    }

    private static void applyPlayerEffects(MinecraftServer server) {
        if (currentStage < 6) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            applyInfinite(player, MobEffects.SLOWNESS, 0);
        }
    }

    private static void applyInfinite(LivingEntity entity, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect, int amplifier) {
        MobEffectInstance existing = entity.getEffect(effect);
        if (existing != null && existing.getAmplifier() == amplifier && existing.isInfiniteDuration()) {
            return;
        }
        entity.addEffect(new MobEffectInstance(effect, MobEffectInstance.INFINITE_DURATION, amplifier, true, false, true));
    }

    private static void applyDoubleDamage(LivingEntity mob) {
        AttributeInstance attackDamage = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attackDamage == null || attackDamage.getModifier(DAMAGE_MODIFIER_ID) != null) {
            return;
        }
        attackDamage.addPermanentModifier(new AttributeModifier(
                DAMAGE_MODIFIER_ID,
                1.0,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    private static void enforcePermanentNight(ServerLevel level) {
        if (currentStage < 3 || level != level.getServer().overworld()) {
            return;
        }
        // dayTime()/setDayTime() are my best-informed guess for the Mojang names here -
        // check autocomplete on `level.` if these don't match.
        long timeOfDay = level.getGameTime() % 24000L;
        if (timeOfDay < 13000L) {
            level.getServer().getCommands().performPrefixedCommand(
                    level.getServer().createCommandSourceStack(),
                    "time set night"
            );
        }
    }

    private static void forceHostileSpawns(MinecraftServer server) {
        RandomSource random = RandomSource.create();
        List<EntityType<?>> pool = List.of(
                EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.SPIDER, EntityTypes.CREEPER, EntityTypes.HUSK);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.level();
            BlockPos base = player.blockPosition();

            for (int i = 0; i < 2; i++) {
                int dx = random.nextIntBetweenInclusive(-24, 24);
                int dz = random.nextIntBetweenInclusive(-24, 24);
                BlockPos pos = base.offset(dx, 0, dz);
                BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos);

                EntityType<?> type = pool.get(random.nextInt(pool.size()));
                type.spawn(level, ground, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
            }
        }
    }

    private static void pulseDarkness(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 200, 0, true, true, true));
        }
    }

    private static void maybeStrikeLightning(MinecraftServer server) {
        RandomSource random = RandomSource.create();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (random.nextInt(600) != 0) {
                continue;
            }
            ServerLevel level = player.level();
            BlockPos pos = player.blockPosition().offset(
                    random.nextIntBetweenInclusive(-10, 10), 0, random.nextIntBetweenInclusive(-10, 10));
            BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos);

            var lightning = EntityTypes.LIGHTNING_BOLT.create(level, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
            if (lightning == null) continue;
            lightning.setPos(
                    ground.getX() + 0.5,
                    ground.getY(),
                    ground.getZ() + 0.5
            );
            level.addFreshEntity(lightning);
        }
    }

    private static final Identifier GIANT_DAMAGE_ID =
            Identifier.fromNamespaceAndPath(HarderChallenge.MOD_ID, "giant_zombie_damage");
    private static final Identifier GIANT_SCALE_ID =
            Identifier.fromNamespaceAndPath(HarderChallenge.MOD_ID, "giant_zombie_scale");

    private static void applyGiantZombieStats(LivingEntity zombie) {
        AttributeInstance damage = zombie.getAttribute(Attributes.ATTACK_DAMAGE);
        if (damage != null && damage.getModifier(GIANT_DAMAGE_ID) == null) {
            damage.addPermanentModifier(new AttributeModifier(
                    GIANT_DAMAGE_ID, 0.20, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        AttributeInstance scale = zombie.getAttribute(Attributes.SCALE);
        if (scale != null && scale.getModifier(GIANT_SCALE_ID) == null) {
            scale.addPermanentModifier(new AttributeModifier(
                    GIANT_SCALE_ID, 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void applyStageEquipment(MinecraftServer server) {
        if (tickCounter % 100 != 0) return;

        if (currentStage >= 4) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    "item replace entity @e[type=minecraft:zombie] weapon.mainhand with minecraft:iron_sword[enchantments={sharpness:1}]");
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    "item replace entity @e[type=minecraft:husk] weapon.mainhand with minecraft:iron_sword[enchantments={sharpness:1}]");
        }

        if (currentStage >= 8) {
            String[] mobTypes = {"zombie", "husk", "drowned", "skeleton", "stray", "bogged", "piglin_brute"};
            String[] slots = {"armor.head", "armor.chest", "armor.legs", "armor.feet"};
            String[] items = {"iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots"};
            for (String mobType : mobTypes) {
                for (int i = 0; i < slots.length; i++) {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                            "item replace entity @e[type=minecraft:" + mobType + "] " + slots[i] + " with minecraft:" + items[i]);
                }
            }
        }
    }

    private static void applyInvisibilityCycle(MinecraftServer server) {
        if (currentStage < 11) return;
        long withinMinute = tickCounter % TICKS_PER_MINUTE;
        if (withinMinute < 10L * 20L) {
            if (tickCounter % 20 == 0) {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                        "effect give @e[type=minecraft:zombie] minecraft:invisibility 2 0 true");
            }
        } else if (withinMinute == 10L * 20L) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    "effect clear @e[type=minecraft:zombie] minecraft:invisibility");
        }
    }

    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(literal("hc")
                        .requires(source -> true)
                        .then(literal("setminutes")
                                .then(argument("minutes", IntegerArgumentType.integer(0))
                                        .executes(ctx -> {
                                            int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
                                            tickCounter = (long) minutes * TICKS_PER_MINUTE;
                                            currentStage = -1;
                                            ctx.getSource().sendSuccess(() -> Component.literal("Timer set to " + minutes + " minutes."), false);
                                            return 1;
                                        })))
                        .then(literal("stage")
                                .then(argument("stage", IntegerArgumentType.integer(0, MAX_STAGE))
                                        .executes(ctx -> {
                                            int stage = IntegerArgumentType.getInteger(ctx, "stage");
                                            currentStage = stage;
                                            tickCounter = (long) stage * STAGE_INTERVAL_MINUTES * TICKS_PER_MINUTE;
                                            announceStage(ctx.getSource().getServer(), stage);
                                            ctx.getSource().sendSuccess(() -> Component.literal("Difficulty set to Stage " + stage + "."), false);
                                            return 1;
                                        })))
                        .then(literal("harder")
                                .executes(ctx -> {
                                    currentStage = Math.min(MAX_STAGE, currentStage + 1);
                                    tickCounter = (long) currentStage * STAGE_INTERVAL_MINUTES * TICKS_PER_MINUTE;
                                    announceStage(ctx.getSource().getServer(), currentStage);
                                    ctx.getSource().sendSuccess(() -> Component.literal("Difficulty increased to Stage " + currentStage + "."), false);
                                    return 1;
                                }))
                        .then(literal("easier")
                                .executes(ctx -> {
                                    currentStage = Math.max(0, currentStage - 1);
                                    tickCounter = (long) currentStage * STAGE_INTERVAL_MINUTES * TICKS_PER_MINUTE;
                                    ctx.getSource().sendSuccess(() -> Component.literal("Difficulty decreased to Stage " + currentStage + "."), false);
                                    return 1;
                                }))
                        .then(literal("event")
                                .then(argument("name", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("earthquake");
                                            builder.suggest("ashfall");
                                            builder.suggest("lightning");
                                            builder.suggest("meteor");
                                            builder.suggest("bloodmoon");
                                            builder.suggest("tornado");
                                            builder.suggest("blackhole");
                                            builder.suggest("stop");
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "name").toLowerCase();
                                            MinecraftServer server = ctx.getSource().getServer();
                                            EventDirector.WorldEvent event = switch (name) {
                                                case "earthquake" -> EventDirector.WorldEvent.EARTHQUAKE;
                                                case "ashfall" -> EventDirector.WorldEvent.ASHFALL;
                                                case "lightning", "lightningstorm" -> EventDirector.WorldEvent.LIGHTNING_STORM;
                                                case "meteor", "meteorshower" -> EventDirector.WorldEvent.METEOR_SHOWER;
                                                case "bloodmoon", "blood_moon" -> EventDirector.WorldEvent.BLOOD_MOON;
                                                case "tornado", "twister" -> EventDirector.WorldEvent.TORNADO;
                                                case "blackhole", "black_hole", "black-hole" -> EventDirector.WorldEvent.BLACK_HOLE;
                                                case "stop", "none" -> EventDirector.WorldEvent.NONE;
                                                default -> null;
                                            };
                                            if (event == null) {
                                                ctx.getSource().sendFailure(Component.literal("Unknown event. Use earthquake, ashfall, lightning, meteor, bloodmoon, tornado, blackhole, or stop."));
                                                return 0;
                                            }
                                            if (event == EventDirector.WorldEvent.NONE) {
                                                EventDirector.forceStop(server);
                                                ctx.getSource().sendSuccess(() -> Component.literal("World event stopped."), false);
                                            } else {
                                                EventDirector.forceStart(server, event);
                                                ctx.getSource().sendSuccess(() -> Component.literal("Triggered " + event.name().replace('_', ' ') + "."), false);
                                            }
                                            return 1;
                                        })))
                        .then(literal("hunter")
                                .then(literal("spawn")
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            if (!HunterManager.start(ctx.getSource().getServer(), player)) {
                                                ctx.getSource().sendFailure(Component.literal("A Hunter encounter is already active or could not spawn."));
                                                return 0;
                                            }
                                            ctx.getSource().sendSuccess(() -> Component.literal("Hunter encounter started."), false);
                                            return 1;
                                        }))
                                .then(literal("stop")
                                        .executes(ctx -> {
                                            HunterManager.stop(ctx.getSource().getServer());
                                            ctx.getSource().sendSuccess(() -> Component.literal("Hunter encounter stopped."), false);
                                            return 1;
                                        }))
                                .then(literal("status")
                                        .executes(ctx -> {
                                            ctx.getSource().sendSuccess(() -> Component.literal("Hunter: " + HunterManager.getStatus()
                                                    + " | outline: " + (HunterManager.isOutlineEnabled() ? "on" : "off")), false);
                                            return 1;
                                        }))
                                .then(literal("debug")
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal(HunterManager.getDebugInfo(ctx.getSource().getServer(), player)),
                                                    false
                                            );
                                            return 1;
                                        }))
                                .then(literal("monitor")
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            boolean enabled = HunterManager.toggleDebugMonitor(player);
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Hunter live monitor " + (enabled ? "enabled." : "disabled.")), false);
                                            return 1;
                                        }))
                                .then(literal("spectate")
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            if (!HunterManager.startSpectatingHunter(ctx.getSource().getServer(), player)) {
                                                ctx.getSource().sendFailure(Component.literal("Hunter not found or not alive."));
                                                return 0;
                                            }
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Spectating the Hunter. Use /hc hunter return to go back."), false);
                                            return 1;
                                        }))
                                .then(literal("return")
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            if (!HunterManager.stopSpectatingHunter(ctx.getSource().getServer(), player)) {
                                                ctx.getSource().sendFailure(Component.literal("You are not currently spectating the Hunter."));
                                                return 0;
                                            }
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Returned from the Hunter camera."), false);
                                            return 1;
                                        }))
                                .then(literal("outline")
                                        .executes(ctx -> {
                                            boolean enabled = HunterManager.toggleOutline(ctx.getSource().getServer());
                                            ctx.getSource().sendSuccess(() -> Component.literal("Hunter outline " + (enabled ? "enabled." : "disabled.")), false);
                                            return 1;
                                        })
                                        .then(literal("on")
                                                .executes(ctx -> {
                                                    HunterManager.setOutlineEnabled(ctx.getSource().getServer(), true);
                                                    ctx.getSource().sendSuccess(() -> Component.literal("Hunter outline enabled."), false);
                                                    return 1;
                                                }))
                                        .then(literal("off")
                                                .executes(ctx -> {
                                                    HunterManager.setOutlineEnabled(ctx.getSource().getServer(), false);
                                                    ctx.getSource().sendSuccess(() -> Component.literal("Hunter outline disabled."), false);
                                                    return 1;
                                                }))))
                        .then(literal("status")
                                .executes(ctx -> {
                                    long minutes = tickCounter / TICKS_PER_MINUTE;
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Elapsed: " + minutes + " min | Stage " + currentStage + " | Event: " + EventDirector.getActiveEvent()), false);
                                    return 1;
                                }))));
    }
    public static long getTicksElapsed() {
        return tickCounter;
    }
}
