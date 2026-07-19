package name;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Semi-random world-event director. Stages remain predictable; this system
 * controls temporary environmental events based on player condition.
 */
public final class EventDirector {

    public enum WorldEvent {
        NONE,
        EARTHQUAKE,
        ASHFALL,
        LIGHTNING_STORM,
        METEOR_SHOWER,
        BLOOD_MOON,
        TORNADO,
        BLACK_HOLE
    }

    private static final int TICKS_PER_SECOND = 20;
    private static final int EVENT_DURATION = 60 * TICKS_PER_SECOND;
    private static final int DIRECTOR_CHECK_INTERVAL = 20 * TICKS_PER_SECOND;
    private static final int MIN_EVENT_GAP = 150 * TICKS_PER_SECOND;

    private static final RandomSource RANDOM = RandomSource.create();



    private static WorldEvent activeEvent = WorldEvent.NONE;
    private static int eventTicksRemaining;
    private static long ticksSinceLastEvent = MIN_EVENT_GAP;

    private EventDirector() {
    }

    public static WorldEvent getActiveEvent() {
        return activeEvent;
    }

    public static int getEventTicksRemaining() {
        return eventTicksRemaining;
    }

    public static void forceStart(MinecraftServer server, WorldEvent event) {
        if (HunterManager.isActive()) return;
        if (event == null || event == WorldEvent.NONE) return;
        if (activeEvent != WorldEvent.NONE) {
            finishEvent(server);
        }
        startEvent(server, event);
    }

    public static void forceStop(MinecraftServer server) {
        if (activeEvent != WorldEvent.NONE) {
            finishEvent(server);
        }
    }

    public static void tick(MinecraftServer server, long runTicks, int stage) {
        ticksSinceLastEvent++;

        if (activeEvent != WorldEvent.NONE) {
            runActiveEvent(server);
            eventTicksRemaining--;
            if (eventTicksRemaining <= 0) {
                finishEvent(server);
            }
            return;
        }

        if (stage < 2 || ticksSinceLastEvent < MIN_EVENT_GAP) {
            return;
        }

        if (runTicks % DIRECTOR_CHECK_INTERVAL != 0) {
            return;
        }

        int pressure = calculatePressure(server, stage);
        int chancePercent = pressure >= 75 ? 38 : pressure >= 50 ? 27 : pressure >= 30 ? 18 : 10;
        if (RANDOM.nextInt(100) >= chancePercent) {
            return;
        }

        startEvent(server, chooseEvent(stage, pressure));
    }

    private static int calculatePressure(MinecraftServer server, int stage) {
        int score = Math.min(55, stage * 5);
        int players = 0;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            players++;
            float healthRatio = player.getHealth() / Math.max(1.0F, player.getMaxHealth());
            if (healthRatio > 0.85F) score += 12;
            else if (healthRatio < 0.35F) score -= 25;

            if (player.getFoodData().getFoodLevel() >= 17) score += 6;
            else if (player.getFoodData().getFoodLevel() <= 6) score -= 12;

            int armorPieces = 0;
            if (!player.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) armorPieces++;
            if (!player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) armorPieces++;
            if (!player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()) armorPieces++;
            if (!player.getItemBySlot(EquipmentSlot.FEET).isEmpty()) armorPieces++;
            score += armorPieces * 3;
        }

        if (players == 0) return 0;
        return Math.max(0, Math.min(100, score / players));
    }

    private static WorldEvent chooseEvent(int stage, int pressure) {
        int roll = RANDOM.nextInt(1000);

        // Blood Moon stays genuinely rare and cannot appear early.
        if (stage >= 7 && pressure >= 55 && roll < 18) {
            return WorldEvent.BLOOD_MOON;
        }
        // Rare late-game disaster. This is an independent 2.5% selection chance
        // after an event has already been triggered.
        if (stage >= 6 && RANDOM.nextInt(1000) < 25) return WorldEvent.BLACK_HOLE;
        if (stage >= 5 && roll < 155) return WorldEvent.TORNADO;
        if (stage >= 4 && roll < 300) return WorldEvent.METEOR_SHOWER;
        if (roll < 500) return WorldEvent.EARTHQUAKE;
        if (roll < 745) return WorldEvent.ASHFALL;
        return WorldEvent.LIGHTNING_STORM;
    }

    private static void startEvent(MinecraftServer server, WorldEvent event) {
        activeEvent = event;
        eventTicksRemaining = EVENT_DURATION;
        ticksSinceLastEvent = 0;

        String displayName = switch (event) {
            case EARTHQUAKE -> "EARTHQUAKE";
            case ASHFALL -> "ASHFALL";
            case LIGHTNING_STORM -> "LIGHTNING STORM";
            case METEOR_SHOWER -> "METEOR SHOWER";
            case BLOOD_MOON -> "BLOOD MOON";
            case TORNADO -> "TORNADO";
            case BLACK_HOLE -> "BLACK HOLE";
            default -> "WORLD EVENT";
        };

        command(server, "title @a title {\"text\":\"" + displayName + "\",\"color\":\"red\",\"bold\":true}");
        command(server, "title @a subtitle {\"text\":\"SURVIVE FOR 60 SECONDS\",\"color\":\"gold\"}");
        command(server, "playsound harderchallenge:cinematic_horn master @a ~ ~ ~ 1.0 0.72 1.0");

        if (event == WorldEvent.TORNADO) {
            TornadoEvent.start(server);
            setWeatherWhereSupported(server, "thunder 60");
        }

        if (event == WorldEvent.BLOOD_MOON) {
            command(server.overworld(), "time set midnight");
            command(server.overworld(), "weather thunder 60");
            syncBloodMoonState(server, true);
        }

        if (event == WorldEvent.BLACK_HOLE) {
            BlackHoleEvent.start(server);
        }
    }

    private static void runActiveEvent(MinecraftServer server) {
        switch (activeEvent) {
            case EARTHQUAKE -> tickEarthquake(server);
            case ASHFALL -> tickAshfall(server);
            case LIGHTNING_STORM -> tickLightning(server);
            case METEOR_SHOWER -> tickMeteorShower(server);
            case BLOOD_MOON -> tickBloodMoon(server);
            case TORNADO -> TornadoEvent.tick(server);
            case BLACK_HOLE -> BlackHoleEvent.tick(server, eventTicksRemaining);
            default -> { }
        }
    }

    private static void tickEarthquake(MinecraftServer server) {
        int elapsed = EVENT_DURATION - eventTicksRemaining;

        // Phase 1 (0-4 seconds): warning rumble and light shaking. Nothing opens yet.
        // Phase 2 (4-8 seconds): the shaking intensifies and dust/rubble appears.
        // Phase 3 (8+ seconds): fissures propagate gradually instead of deleting the
        // ground beneath the player all at once.
        boolean warningPhase = elapsed < 4 * TICKS_PER_SECOND;
        boolean crackingPhase = elapsed >= 4 * TICKS_PER_SECOND && elapsed < 8 * TICKS_PER_SECOND;
        boolean destructivePhase = elapsed >= 8 * TICKS_PER_SECOND;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.level();
            String playerName = player.getScoreboardName();

            // Small, rapidly changing horizontal pushes make the view and movement feel
            // unstable without teleporting the player or forcing motion sickness.
            double shakeStrength = warningPhase ? 0.025 : crackingPhase ? 0.055 : 0.075;
            if (elapsed % 2 == 0 && player.onGround()) {
                player.setDeltaMovement(player.getDeltaMovement().add(
                        (RANDOM.nextDouble() - 0.5) * shakeStrength,
                        crackingPhase || destructivePhase ? RANDOM.nextDouble() * 0.018 : 0.0,
                        (RANDOM.nextDouble() - 0.5) * shakeStrength));
                player.hurtMarked = true;
            }

            // Layer several low sounds at changing pitch so it feels like a continuous
            // underground rumble rather than an occasional explosion.
            if (elapsed % 12 == 0) {
                float volume = warningPhase ? 0.40F : crackingPhase ? 0.65F : 0.85F;
                float pitch = warningPhase ? 0.48F : crackingPhase ? 0.40F : 0.34F;
                command(server, "execute at " + playerName
                        + " run playsound minecraft:block.basalt.break master " + playerName
                        + " ~ ~ ~ " + volume + " " + pitch);
            }
            if (elapsed % 30 == 0) {
                command(server, "execute at " + playerName
                        + " run playsound minecraft:entity.minecart.riding master " + playerName
                        + " ~ ~ ~ 0.55 0.45");
            }

            if (elapsed % 4 == 0) {
                int dust = warningPhase ? 16 : crackingPhase ? 35 : 55;
                command(server, "execute at " + playerName
                        + " run particle minecraft:dust_plume ~ ~0.15 ~ 7 0.15 7 0.03 " + dust + " force " + playerName);
                command(server, "execute at " + playerName
                        + " run particle minecraft:block minecraft:stone ~ ~0.25 ~ 6 0.2 6 0.08 " + (dust / 2) + " force " + playerName);
            }

            if (warningPhase) {
                if (elapsed == 1) {
                    command(server, "title " + playerName + " actionbar {\"text\":\"THE EARTH BEGINS TO SHAKE...\",\"color\":\"dark_red\",\"bold\":true}");
                }
                continue;
            }

            // Before fissures begin, loose gravel appears and nearby mobs stumble.
            if (crackingPhase) {
                if (elapsed % 10 == 0) {
                    rattleSurface(level, player.blockPosition(), 5);
                }
                continue;
            }

            // Break exactly 150 individual blocks every tick. At the normal 20 TPS,
            // this attempts 3,000 block removals per second for each affected player.
            int destructiveElapsed = elapsed - 8 * TICKS_PER_SECOND;
            int blocksThisTick = 150;
            BlockPos leadingEdge = null;
            for (int i = 0; i < blocksThisTick; i++) {
                BlockPos broken = breakNextFissureBlock(level, player,
                        destructiveElapsed * blocksThisTick + i);
                if (broken != null) leadingEdge = broken;
            }

            // Dust and debris follow the leading edge of the moving crack instead of
            // appearing only around the player.
            if (leadingEdge != null && elapsed % 4 == 0) {
                level.sendParticles(ParticleTypes.DUST_PLUME,
                        leadingEdge.getX() + 0.5, leadingEdge.getY() + 1.0, leadingEdge.getZ() + 0.5,
                        12, 0.45, 0.25, 0.45, 0.035);
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()),
                        leadingEdge.getX() + 0.5, leadingEdge.getY() + 0.8, leadingEdge.getZ() + 0.5,
                        8, 0.35, 0.3, 0.35, 0.08);
            }

            // A tiny relative yaw/pitch change gives a light camera shake without
            // applying nausea or violently taking control away from the player.
            if (elapsed % 3 == 0) {
                double yawJolt = (RANDOM.nextDouble() - 0.5) * 0.70;
                double pitchJolt = (RANDOM.nextDouble() - 0.5) * 0.38;
                command(server, "execute as " + playerName + " at @s run tp @s ~ ~ ~ ~" + yawJolt + " ~" + pitchJolt);
            }

            if (elapsed % 40 == 0) {
                command(server, "execute at " + playerName
                        + " run playsound minecraft:entity.generic.explode master @a[distance=..48] ~ ~ ~ 0.55 0.55");
            }
        }
    }

    private static void rattleSurface(ServerLevel level, BlockPos center, int attempts) {
        for (int i = 0; i < attempts; i++) {
            int x = center.getX() + RANDOM.nextIntBetweenInclusive(-12, 12);
            int z = center.getZ() + RANDOM.nextIntBetweenInclusive(-12, 12);
            BlockPos top = findLocalSurface(level, x, z, center.getY());
            if (top == null) continue;
            BlockPos surface = top.below();
            if (level.getBlockState(surface).isSolid() && RANDOM.nextBoolean()) {
                level.setBlockAndUpdate(surface, Blocks.GRAVEL.defaultBlockState());
            }
        }
    }

    private static BlockPos breakNextFissureBlock(ServerLevel level, ServerPlayer player, int blockStep) {
        // Five connected blocks form each vertical slice, then the crack advances one
        // surface position. This method is called 35-50 times per tick, so several
        // complete slices are cut every game tick while blocks still disappear individually.
        int depthCycle = 5;
        int pathStep = blockStep / depthCycle;
        int depthStep = blockStep % depthCycle;

        double baseAngle = player.getId() * 1.73;
        double angle = baseAngle + pathStep * 0.055;
        int distance = 6 + (pathStep % 30);

        int sideWobble = ((pathStep * 31 + player.getId() * 7) % 5) - 2;
        int forwardX = (int) Math.round(Math.cos(angle) * distance);
        int forwardZ = (int) Math.round(Math.sin(angle) * distance);
        int sideX = (int) Math.round(-Math.sin(angle) * sideWobble);
        int sideZ = (int) Math.round(Math.cos(angle) * sideWobble);

        int x = player.getBlockX() + forwardX + sideX;
        int z = player.getBlockZ() + forwardZ + sideZ;
        BlockPos top = findLocalSurface(level, x, z, player.getBlockY());
        if (top == null) return null;

        BlockPos target = top.below(1 + depthStep);
        if (target.getY() <= level.getMinY() + 4) return null;
        if (level.getBlockState(target).isAir()) return null;

        level.destroyBlock(target, false);

        // Rare lava can appear only at the deepest point in a freshly cut column.
        if (depthStep == depthCycle - 1 && RANDOM.nextInt(55) == 0) {
            level.setBlockAndUpdate(target, Blocks.LAVA.defaultBlockState());
        }
        return target;
    }

    /**
     * Finds the walkable surface nearest the player's current Y level. Heightmaps point
     * to the Nether roof, so they make underground events appear to do nothing. This
     * local scan works in the Overworld, Nether caves, and the End.
     */
    private static BlockPos findLocalSurface(ServerLevel level, int x, int z, int referenceY) {
        int maxY = Math.min(level.getMaxY() - 3, referenceY + 12);
        int minY = Math.max(level.getMinY() + 3, referenceY - 24);

        for (int y = maxY; y >= minY; y--) {
            BlockPos floor = new BlockPos(x, y, z);
            if (level.getBlockState(floor).isSolid()
                    && level.getBlockState(floor.above()).isAir()
                    && level.getBlockState(floor.above(2)).isAir()) {
                return floor.above();
            }
        }

        // If the player is inside a cramped Nether tunnel, accept one air block above
        // the floor so the fissure can still form instead of silently failing.
        for (int y = maxY; y >= minY; y--) {
            BlockPos floor = new BlockPos(x, y, z);
            if (level.getBlockState(floor).isSolid()
                    && level.getBlockState(floor.above()).isAir()) {
                return floor.above();
            }
        }
        return null;
    }

    private static void tickAshfall(MinecraftServer server) {
        // Weather supplies natural distance haze; ash density ramps smoothly for 5 seconds.
        int elapsed = EVENT_DURATION - eventTicksRemaining;
        float ramp = Math.min(1.0F, elapsed / 100.0F);
        int particleCount = Math.max(12, (int) (220 * ramp));

        if (eventTicksRemaining == EVENT_DURATION - 1) {
            setWeatherWhereSupported(server, "rain 60");
        }
        if (eventTicksRemaining % 4 != 0) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String p = player.getScoreboardName();
            command(server, "execute at " + p + " run particle minecraft:ash ~ ~8 ~ 14 9 14 0.025 " + particleCount + " force " + p);
            if (eventTicksRemaining % 20 == 0) {
                int smokeCount = Math.max(4, (int) (35 * ramp));
                command(server, "execute at " + p + " run particle minecraft:smoke ~ ~5 ~ 12 5 12 0.01 " + smokeCount + " force " + p);
            }
        }
    }

    private static void tickLightning(MinecraftServer server) {
        if (eventTicksRemaining % 80 != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            strikeNear(player, 8, 22);
        }
    }

    private static void tickMeteorShower(MinecraftServer server) {
        MeteorShower.tick(server, eventTicksRemaining);
    }

    private static BlockPos findSurfaceNearPlayer(ServerPlayer player, int minDistance, int maxDistance) {
        ServerLevel level = player.level();
        for (int attempt = 0; attempt < 32; attempt++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            int distance = RANDOM.nextIntBetweenInclusive(minDistance, maxDistance);
            int x = player.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = player.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            int startY = Math.max(level.getMinY() + 3, Math.min(level.getMaxY() - 4, player.getBlockY() + 18));

            for (int y = startY; y >= Math.max(level.getMinY() + 2, player.getBlockY() - 24); y--) {
                BlockPos floor = new BlockPos(x, y, z);
                if (level.getBlockState(floor).isSolid()
                        && level.getBlockState(floor.above()).isAir()
                        && level.getBlockState(floor.above(2)).isAir()) {
                    return floor.above();
                }
            }
        }
        return player.blockPosition();
    }

    private static void createMeteorCrater(ServerLevel level, BlockPos impact, int radius) {
        int cx = impact.getX();
        int cy = impact.getY();
        int cz = impact.getZ();

        // Wide bowl rather than a tiny spherical hole.
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                if (horizontal > radius + RANDOM.nextDouble() * 0.7) continue;

                int depth = Math.max(1, (int) Math.round((radius - horizontal) * 0.72) + 1);
                for (int dy = 2; dy >= -depth; dy--) {
                    BlockPos pos = new BlockPos(cx + dx, cy + dy, cz + dz);
                    if (pos.getY() <= level.getMinY() + 3) continue;
                    if (!level.getBlockState(pos).isAir()) {
                        level.destroyBlock(pos, false);
                    }
                }

                // Magma-lined crater floor, with occasional lava in the inner core.
                BlockPos floor = new BlockPos(cx + dx, cy - depth - 1, cz + dz);
                if (floor.getY() > level.getMinY() + 3) {
                    if (horizontal <= 1.5 && RANDOM.nextInt(3) == 0) {
                        level.setBlockAndUpdate(floor.above(), Blocks.LAVA.defaultBlockState());
                    } else {
                        level.setBlockAndUpdate(floor, Blocks.MAGMA_BLOCK.defaultBlockState());
                    }
                }
            }
        }
    }

    private static void tickBloodMoon(MinecraftServer server) {
        // Keep the visual trigger active for the full event.
        if (eventTicksRemaining % 20 == 0) {
            command(server.overworld(), "time set midnight");
            command(server.overworld(), "weather thunder 60");
            syncBloodMoonState(server, true);
        }

        if (eventTicksRemaining % 100 == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                command(server, "execute at " + player.getScoreboardName()
                        + " run summon minecraft:zombie ~8 ~ ~ {PersistenceRequired:1b}");
                command(server, "execute at " + player.getScoreboardName()
                        + " run summon minecraft:skeleton ~-8 ~ ~ {PersistenceRequired:1b}");
            }
        }

        if (eventTicksRemaining % 160 == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                strikeNear(player, 6, 18);
            }
        }
    }

    private static void finishEvent(MinecraftServer server) {
        WorldEvent finished = activeEvent;
        if (finished == WorldEvent.TORNADO) {
            TornadoEvent.stop();
        }
        if (finished == WorldEvent.METEOR_SHOWER) {
            MeteorShower.clear();
        }
        if (finished == WorldEvent.BLOOD_MOON) {
            syncBloodMoonState(server, false);
        }
        if (finished == WorldEvent.BLACK_HOLE) {
            BlackHoleEvent.stop();
        }
        activeEvent = WorldEvent.NONE;
        eventTicksRemaining = 0;

        // Clear event-only visibility/weather effects. Normal gameplay gets no added fog.
        command(server, "effect clear @a minecraft:darkness");
        if (finished == WorldEvent.ASHFALL || finished == WorldEvent.BLOOD_MOON || finished == WorldEvent.LIGHTNING_STORM || finished == WorldEvent.TORNADO) {
            setWeatherWhereSupported(server, "clear");
        }
    }

    private static void strikeNear(ServerPlayer player, int minDistance, int maxDistance) {
        ServerLevel level = player.level();
        int dx = signedDistance(minDistance, maxDistance);
        int dz = signedDistance(minDistance, maxDistance);
        BlockPos target = level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                player.blockPosition().offset(dx, 0, dz));
        command(level, "summon minecraft:lightning_bolt " + target.getX() + " " + target.getY() + " " + target.getZ());
    }

    private static int signedDistance(int min, int max) {
        int value = RANDOM.nextIntBetweenInclusive(min, max);
        return RANDOM.nextBoolean() ? value : -value;
    }

    private static void setWeatherWhereSupported(MinecraftServer server, String weatherArguments) {
        // Vanilla weather only renders in dimensions that support skylight/weather.
        // Running this only in the Overworld avoids command errors in the Nether while
        // the particles, physics and destruction continue normally there.
        command(server.overworld(), "weather " + weatherArguments);
    }

    private static void command(ServerLevel level, String command) {
        if (level == null || level.getServer() == null) return;
        level.getServer().getCommands().performPrefixedCommand(
                level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput(), command);
    }

    private static void syncBloodMoonState(MinecraftServer server, boolean active) {
        BloodMoonStatePayload payload = new BloodMoonStatePayload(active);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    private static void command(MinecraftServer server, String command) {
        if (server == null) return;
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }
}
