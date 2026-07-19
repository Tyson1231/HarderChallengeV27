package name;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Native Fabric tornado world event. The funnel is rendered with vanilla particles,
 * while suction, orbiting, throwing and light terrain damage run server-side.
 *
 * V2 changes:
 * - Pull strength grows continuously during the 60-second event.
 * - All particles, sounds and movement are executed in the tornado's actual dimension.
 * - Ground tracking works in enclosed dimensions such as the Nether instead of locking
 *   onto the Nether roof through a heightmap lookup.
 */
public final class TornadoEvent {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final double BASE_EFFECT_RADIUS = 30.0;
    private static final double MAX_EFFECT_RADIUS = 40.0;
    private static final double CORE_RADIUS = 5.5;
    private static final int FUNNEL_HEIGHT = 52;
    private static final int EXPECTED_EVENT_TICKS = 60 * 20;

    private static ServerLevel level;
    private static Vec3 center;
    private static Vec3 travelDirection;
    private static int age;
    private static UUID targetPlayerId;
    private static final Map<UUID, Integer> PLAYER_LIFT_TICKS = new HashMap<>();
    private static final int FLING_AFTER_TICKS = 10 * 20;

    private TornadoEvent() {
    }

    public static void start(MinecraftServer server) {
        ServerPlayer target = pickTarget(server);
        if (target == null) return;

        level = target.level();
        targetPlayerId = target.getUUID();
        Vec3 spawn = findOpenSpawnNear(target);
        if (spawn == null) {
            stop();
            return;
        }

        double x = spawn.x;
        double z = spawn.z;
        center = spawn;

        Vec3 towardPlayer = new Vec3(target.getX() - x, 0.0, target.getZ() - z).normalize();
        Vec3 sideways = new Vec3(-towardPlayer.z, 0.0, towardPlayer.x)
                .scale(RANDOM.nextDouble() * 0.55 - 0.275);
        travelDirection = towardPlayer.add(sideways).normalize();
        age = 0;

    }

    public static void stop() {
        level = null;
        center = null;
        travelDirection = null;
        age = 0;
        targetPlayerId = null;
        PLAYER_LIFT_TICKS.clear();
    }

    public static void tick(MinecraftServer server) {
        if (center == null || level == null || travelDirection == null) {
            start(server);
            if (center == null) return;
        }

        age++;
        followTarget(server);
        moveTornado();
        affectEntities();

        if (age % 2 == 0) renderFunnel();
        if (age % 5 == 0) renderGroundDebris();
        if (age % 3 == 0) pickUpTerrainBlocks();
        if (age % 45 == 0) playWind();
        if (age % 40 == 0) retarget(server);
    }

    private static double eventProgress() {
        return Math.min(1.0, age / (double) EXPECTED_EVENT_TICKS);
    }

    private static double effectRadius() {
        return BASE_EFFECT_RADIUS + (MAX_EFFECT_RADIUS - BASE_EFFECT_RADIUS) * eventProgress();
    }

    private static double forceMultiplier() {
        // Starts noticeably dangerous, then grows to nearly triple the opening force.
        // Smoothstep prevents the first few seconds from jumping too abruptly.
        double progress = eventProgress();
        double smooth = progress * progress * (3.0 - 2.0 * progress);
        return 0.70 + 1.25 * smooth;
    }

    private static void followTarget(MinecraftServer server) {
        ServerPlayer target = targetPlayerId == null ? null : server.getPlayerList().getPlayer(targetPlayerId);
        if (target == null || target.level() != level || !target.isAlive() || target.isSpectator()) {
            target = pickTargetInLevel(server, level);
            if (target == null) return;
            targetPlayerId = target.getUUID();
        }

        Vec3 desired = new Vec3(target.getX() - center.x, 0.0, target.getZ() - center.z);
        if (desired.lengthSqr() < 0.25) return;

        // Strong continuous steering makes the tornado actively chase the selected player,
        // while a small amount of inertia keeps its path from looking robotic.
        double steering = 0.045 + eventProgress() * 0.035;
        travelDirection = travelDirection.scale(1.0 - steering)
                .add(desired.normalize().scale(steering))
                .normalize();
    }

    private static void moveTornado() {
        double turn = (RANDOM.nextDouble() - 0.5) * 0.018;
        double cos = Math.cos(turn);
        double sin = Math.sin(turn);
        travelDirection = new Vec3(
                travelDirection.x * cos - travelDirection.z * sin,
                0.0,
                travelDirection.x * sin + travelDirection.z * cos).normalize();

        // It becomes slightly faster as the event escalates, preventing players from
        // permanently escaping it by simply sprinting in a straight line.
        double speed = 0.14 + eventProgress() * 0.10;
        center = center.add(travelDirection.scale(speed));
        int groundY = findGroundY(level, center.x, center.y, center.z);
        center = new Vec3(center.x, groundY + 1.0, center.z);
    }

    private static void affectEntities() {
        double radius = effectRadius();
        double multiplier = forceMultiplier();
        AABB area = new AABB(
                center.x - radius, center.y - 8.0, center.z - radius,
                center.x + radius, center.y + FUNNEL_HEIGHT, center.z + radius);

        for (Entity entity : level.getEntities((Entity) null, area, e -> e.isAlive() && !e.isSpectator())) {
            Vec3 offset = center.subtract(entity.position());
            double horizontalDistance = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
            if (horizontalDistance > radius || horizontalDistance < 0.01) {
                if (entity instanceof ServerPlayer player) {
                    PLAYER_LIFT_TICKS.remove(player.getUUID());
                }
                continue;
            }

            double radialStrength = 1.0 - horizontalDistance / radius;
            Vec3 inward = new Vec3(offset.x, 0.0, offset.z).normalize();
            Vec3 tangent = new Vec3(-inward.z, 0.0, inward.x);

            double pull = (0.030 + 0.125 * radialStrength) * multiplier;
            double spin = (0.050 + 0.220 * radialStrength) * (0.85 + multiplier * 0.35);
            double liftBase = horizontalDistance < CORE_RADIUS
                    ? 0.18 + RANDOM.nextDouble() * 0.12
                    : Math.max(0.005, 0.055 * radialStrength);
            double lift = liftBase * (0.75 + multiplier * 0.45);

            Vec3 velocity = entity.getDeltaMovement()
                    .scale(Math.max(0.68, 0.88 - eventProgress() * 0.10))
                    .add(inward.scale(pull))
                    .add(tangent.scale(spin))
                    .add(0.0, lift, 0.0);

            if (entity instanceof ServerPlayer player && horizontalDistance < CORE_RADIUS
                    && entity.getY() > center.y + 5.0) {
                int heldTicks = PLAYER_LIFT_TICKS.merge(player.getUUID(), 1, Integer::sum);
                if (heldTicks >= FLING_AFTER_TICKS) {
                    // After ten seconds inside the funnel, launch the player outward and upward.
                    velocity = tangent.scale(2.0 + eventProgress() * 0.8)
                            .add(inward.scale(-1.65 - eventProgress() * 0.55))
                            .add(0.0, 1.15 + eventProgress() * 0.45, 0.0);
                    PLAYER_LIFT_TICKS.remove(player.getUUID());
                }
            } else if (entity instanceof ServerPlayer player) {
                PLAYER_LIFT_TICKS.remove(player.getUUID());
            } else if (horizontalDistance < CORE_RADIUS && entity.getY() > center.y + 8.0
                    && RANDOM.nextInt(Math.max(12, 38 - (int) (eventProgress() * 24))) == 0) {
                velocity = tangent.scale(1.05 + eventProgress() * 0.65)
                        .add(inward.scale(-0.45 - eventProgress() * 0.35))
                        .add(0.0, 0.50 + eventProgress() * 0.35, 0.0);
            }

            entity.setDeltaMovement(velocity);
            entity.hurtMarked = true;

            if (entity instanceof LivingEntity living && horizontalDistance < 2.2 && age % 20 == 0) {
                living.hurt(level.damageSources().flyIntoWall(), 2.0F + (float) eventProgress() * 2.0F);
            }
        }
    }

    private static void renderFunnel() {
        for (int y = 0; y <= FUNNEL_HEIGHT; y += 3) {
            double progress = y / (double) FUNNEL_HEIGHT;
            double radius = 1.4 + Math.pow(progress, 1.35) * 11.5;
            int points = 7 + (int) (progress * 8.0);
            double phase = age * 0.22 + y * 0.37;

            for (int i = 0; i < points; i++) {
                double angle = phase + (Math.PI * 2.0 * i / points);
                double wobble = Math.sin(age * 0.08 + y * 0.3) * progress * 1.4;
                double x = center.x + Math.cos(angle) * radius + travelDirection.z * wobble;
                double z = center.z + Math.sin(angle) * radius - travelDirection.x * wobble;
                String particle = progress > 0.62 ? "minecraft:cloud" : "minecraft:large_smoke";
                command(level, "particle " + particle + " " + x + " " + (center.y + y) + " " + z
                        + " 0.22 0.35 0.22 0.015 2 force");
            }
        }
    }

    private static void renderGroundDebris() {
        command(level, "particle minecraft:block minecraft:dirt " + center.x + " " + (center.y + 1.2) + " " + center.z
                + " 7 2 7 0.24 95 force");
        command(level, "particle minecraft:dust_plume " + center.x + " " + (center.y + 2.0) + " " + center.z
                + " 6 2 6 0.12 55 force");
    }

    private static void pickUpTerrainBlocks() {
        // Sample the ground directly beneath and just ahead of the moving funnel so the
        // tornado visibly tears a path through the world rather than damaging random spots.
        int attempts = 10 + (int) Math.floor(eventProgress() * 10.0);
        for (int i = 0; i < attempts; i++) {
            double forward = RANDOM.nextDouble() * 14.0 - 4.0;
            double sideways = RANDOM.nextDouble() * 18.0 - 9.0;
            int x = (int) Math.floor(center.x + travelDirection.x * forward - travelDirection.z * sideways);
            int z = (int) Math.floor(center.z + travelDirection.z * forward + travelDirection.x * sideways);
            int groundY = findGroundY(level, x, center.y, z);

            BlockPos chosen = null;
            BlockState state = null;
            for (int y = groundY + 5; y >= groundY - 1; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState candidate = level.getBlockState(pos);
                if (candidate.isAir()) continue;
                float hardness = candidate.getDestroySpeed(level, pos);
                boolean vegetation = candidate.is(BlockTags.LEAVES) || candidate.is(BlockTags.LOGS);
                boolean movable = hardness >= 0.0F
                        && hardness <= (vegetation ? 6.0F : 3.0F + (float) eventProgress() * 2.0F);
                if (movable && !candidate.is(Blocks.BEDROCK)) {
                    chosen = pos;
                    state = candidate;
                }
                break;
            }

            if (chosen == null || state == null) continue;

            level.removeBlock(chosen, false);
            FallingBlockEntity debris = FallingBlockEntity.fall(level, chosen, state);

            Vec3 towardCore = center.subtract(debris.position());
            Vec3 horizontal = new Vec3(towardCore.x, 0.0, towardCore.z);
            Vec3 inward = horizontal.lengthSqr() < 0.001 ? Vec3.ZERO : horizontal.normalize();
            Vec3 tangent = new Vec3(-inward.z, 0.0, inward.x);
            double strength = forceMultiplier();
            debris.setDeltaMovement(tangent.scale(0.35 + strength * 0.18)
                    .add(inward.scale(0.10 + strength * 0.08))
                    .add(0.0, 0.45 + RANDOM.nextDouble() * 0.35 + eventProgress() * 0.25, 0.0));
            debris.hurtMarked = true;
        }
    }

    private static void playWind() {
        command(level, "execute positioned " + center.x + " " + center.y + " " + center.z
                + " run playsound minecraft:weather.rain master @a[distance=..96] ~ ~ ~ 2.0 0.45");
        if (age % 180 == 0) {
            command(level, "execute positioned " + center.x + " " + center.y + " " + center.z
                    + " run playsound minecraft:entity.lightning_bolt.thunder master @a[distance=..128] ~ ~ ~ 1.2 0.55");
        }
    }

    private static void retarget(MinecraftServer server) {
        ServerPlayer target = targetPlayerId == null ? null : server.getPlayerList().getPlayer(targetPlayerId);
        if (target == null || target.level() != level || !target.isAlive() || target.isSpectator()) {
            target = pickTargetInLevel(server, level);
            if (target == null) return;
            targetPlayerId = target.getUUID();
        }
        Vec3 desired = new Vec3(target.getX() - center.x, 0.0, target.getZ() - center.z);
        if (desired.lengthSqr() < 1.0) return;
        travelDirection = travelDirection.scale(0.58).add(desired.normalize().scale(0.42)).normalize();
    }


    /**
     * Finds an actually open touchdown point. In the Nether, a random point 42 blocks
     * away is often buried inside netherrack, which made the funnel technically exist
     * but remain invisible. This samples multiple rings and only accepts a floor with
     * enough vertical air for the lower funnel.
     */
    private static Vec3 findOpenSpawnNear(ServerPlayer target) {
        ServerLevel targetLevel = target.level();
        for (int attempt = 0; attempt < 48; attempt++) {
            double distance = 22.0 + RANDOM.nextDouble() * 30.0;
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            int x = (int) Math.floor(target.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(target.getZ() + Math.sin(angle) * distance);

            int floorY = findGroundY(targetLevel, x, target.getY(), z);
            if (hasVerticalClearance(targetLevel, x, floorY, z, 12)) {
                level = targetLevel;
                return new Vec3(x + 0.5, floorY + 1.0, z + 0.5);
            }
        }

        // Last-resort touchdown close to the player, still requiring an open column.
        int x = target.getBlockX();
        int z = target.getBlockZ();
        int floorY = findGroundY(targetLevel, x, target.getY(), z);
        if (hasVerticalClearance(targetLevel, x, floorY, z, 8)) {
            level = targetLevel;
            return new Vec3(x + 0.5, floorY + 1.0, z + 0.5);
        }
        return null;
    }

    private static boolean hasVerticalClearance(ServerLevel level, int x, int floorY, int z, int height) {
        if (!level.getBlockState(new BlockPos(x, floorY, z)).isSolid()) return false;
        for (int y = 1; y <= height; y++) {
            if (!level.getBlockState(new BlockPos(x, floorY + y, z)).isAir()) return false;
        }
        return true;
    }

    private static ServerPlayer pickTarget(MinecraftServer server) {
        var players = server.getPlayerList().getPlayers();
        return players.isEmpty() ? null : players.get(RANDOM.nextInt(players.size()));
    }

    private static ServerPlayer pickTargetInLevel(MinecraftServer server, ServerLevel targetLevel) {
        var players = server.getPlayerList().getPlayers().stream()
                .filter(player -> player.level() == targetLevel)
                .toList();
        return players.isEmpty() ? null : players.get(RANDOM.nextInt(players.size()));
    }

    /**
     * Finds the nearest usable floor around a reference height. This is deliberately
     * not a heightmap lookup because Nether heightmaps commonly resolve to the roof.
     */
    private static int findGroundY(ServerLevel level, double x, double referenceY, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int startY = Math.max(level.getMinY() + 2,
                Math.min(level.getMaxY() - 3, (int) Math.floor(referenceY)));

        for (int distance = 0; distance <= 28; distance++) {
            int downY = startY - distance;
            if (downY > level.getMinY() + 1 && isUsableFloor(level, blockX, downY, blockZ)) {
                return downY;
            }

            int upY = startY + distance;
            if (distance > 0 && upY < level.getMaxY() - 2 && isUsableFloor(level, blockX, upY, blockZ)) {
                return upY;
            }
        }

        // Fall back near the reference height instead of snapping to a dimension roof.
        return startY - 1;
    }

    private static boolean isUsableFloor(ServerLevel level, int x, int y, int z) {
        BlockPos floor = new BlockPos(x, y, z);
        return level.getBlockState(floor).isSolid()
                && level.getBlockState(floor.above()).isAir()
                && level.getBlockState(floor.above(2)).isAir();
    }

    private static void command(ServerLevel commandLevel, String command) {
        if (commandLevel == null || commandLevel.getServer() == null) return;
        commandLevel.getServer().getCommands().performPrefixedCommand(
                commandLevel.getServer().createCommandSourceStack().withLevel(commandLevel), command);
    }
}
