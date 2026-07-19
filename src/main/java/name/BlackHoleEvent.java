package name;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A growing, fixed-position gravity well created separately for each player.
 * It begins far enough away to escape, but its pull radius and core grow for
 * the entire event. The hole does not teleport toward the player.
 */
public final class BlackHoleEvent {

    private static final int EVENT_TICKS = 60 * 20;
    private static final double MIN_SPAWN_DISTANCE = 68.0;
    private static final double MAX_SPAWN_DISTANCE = 82.0;
    private static final double START_PULL_RADIUS = 24.0;
    private static final double END_PULL_RADIUS = 112.0;
    private static final double START_CORE_RADIUS = 2.5;
    private static final double END_CORE_RADIUS = 9.0;

    private static final RandomSource RANDOM = RandomSource.create();
    private static final Map<UUID, Hole> HOLES = new HashMap<>();

    private BlackHoleEvent() {
    }

    public static void start(MinecraftServer server) {
        HOLES.clear();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            createHoleFor(player);
        }
    }

    public static void tick(MinecraftServer server, int eventTicksRemaining) {
        int elapsed = Math.max(0, EVENT_TICKS - eventTicksRemaining);
        double progress = Math.min(1.0, elapsed / (double) EVENT_TICKS);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Hole hole = HOLES.get(player.getUUID());
            if (hole == null || hole.level != player.level()) {
                hole = createHoleFor(player);
            }
            tickHole(player, hole, progress, elapsed);
        }
    }

    public static void stop() {
        HOLES.clear();
    }

    private static Hole createHoleFor(ServerPlayer player) {
        double angle = RANDOM.nextDouble() * Math.PI * 2.0;
        double distance = MIN_SPAWN_DISTANCE
                + RANDOM.nextDouble() * (MAX_SPAWN_DISTANCE - MIN_SPAWN_DISTANCE);

        Vec3 center = new Vec3(
                player.getX() + Math.cos(angle) * distance,
                player.getY() + 8.0,
                player.getZ() + Math.sin(angle) * distance
        );

        Hole hole = new Hole(player.level(), center);
        HOLES.put(player.getUUID(), hole);

        player.sendSystemMessage(Component.literal(
                "A BLACK HOLE HAS FORMED " + (int) Math.round(distance)
                        + " BLOCKS AWAY — RUN AWAY FROM IT!"), true);
        return hole;
    }

    private static void tickHole(ServerPlayer owner, Hole hole, double progress, int elapsed) {
        ServerLevel level = hole.level;
        Vec3 center = hole.center;

        double pullRadius = lerp(START_PULL_RADIUS, END_PULL_RADIUS, progress);
        double coreRadius = lerp(START_CORE_RADIUS, END_CORE_RADIUS, progress);

        renderVortex(level, center, pullRadius, coreRadius, progress, elapsed);

        // Apply gravity to every nearby entity. Spectators are ignored so server
        // operators can safely observe or recover a run.
        //
        // Some Minecraft 26.2 entity iterators can expose a null entry after an
        // entity is removed during the same tick. We therefore skip null entries
        // and postpone discarding consumed entities until iteration is complete.
        List<Entity> entitiesToDiscard = new ArrayList<>();

        for (Entity entity : level.getAllEntities()) {
            if (entity == null || !entity.isAlive() || entity.isSpectator()) {
                continue;
            }

            Vec3 delta = center.subtract(entity.position());
            double distance = delta.length();
            if (distance > pullRadius || distance < 0.001) {
                continue;
            }

            if (distance <= coreRadius) {
                if (entity instanceof ServerPlayer player) {
                    // Near-certain death, but damage is applied in pulses so armor,
                    // effects and totems still interact naturally with the event.
                    if (elapsed % 4 == 0) {
                        player.hurt(level.damageSources().flyIntoWall(), 8.0F);
                    }
                } else {
                    entitiesToDiscard.add(entity);
                }
                continue;
            }

            double proximity = 1.0 - (distance / pullRadius);
            double strength = 0.018 + proximity * proximity * (0.20 + progress * 0.34);
            Vec3 pull = delta.normalize().scale(strength);

            // Items and experience-like lightweight entities are pulled much harder,
            // making the vortex visibly consume loose debris.
            if (entity instanceof ItemEntity) {
                pull = pull.scale(2.4);
            }

            entity.setDeltaMovement(entity.getDeltaMovement().add(pull));
            entity.hurtMarked = true;
        }

        for (Entity entity : entitiesToDiscard) {
            if (entity != null && entity.isAlive()) {
                entity.discard();
            }
        }

        if (elapsed % 20 == 0) {
            double ownerDistance = owner.position().distanceTo(center);
            int secondsLeft = Math.max(0, (EVENT_TICKS - elapsed) / 20);
            owner.sendSystemMessage(Component.literal(
                    "BLACK HOLE: " + (int) Math.round(ownerDistance)
                            + " blocks away | radius " + (int) Math.round(pullRadius)
                            + " | " + secondsLeft + "s"), true);
        }
    }

    private static void renderVortex(
            ServerLevel level,
            Vec3 center,
            double pullRadius,
            double coreRadius,
            double progress,
            int elapsed
    ) {
        // Dense dark core.
        int coreParticles = 18 + (int) Math.round(progress * 42.0);
        level.sendParticles(ParticleTypes.SQUID_INK,
                center.x, center.y, center.z,
                coreParticles,
                coreRadius * 0.32, coreRadius * 0.32, coreRadius * 0.32,
                0.01);

        level.sendParticles(ParticleTypes.LARGE_SMOKE,
                center.x, center.y, center.z,
                8 + (int) Math.round(progress * 18.0),
                coreRadius * 0.45, coreRadius * 0.45, coreRadius * 0.45,
                0.015);

        // Rotating accretion ring. The ring grows more slowly than the gravity field,
        // so the visible object remains readable instead of filling the whole screen.
        double ringRadius = 4.5 + progress * 14.0;
        int points = 26 + (int) Math.round(progress * 24.0);
        double rotation = elapsed * 0.105;

        for (int i = 0; i < points; i++) {
            double angle = rotation + Math.PI * 2.0 * i / points;
            double wobble = Math.sin(angle * 3.0 + elapsed * 0.04) * (0.25 + progress * 0.6);
            double x = center.x + Math.cos(angle) * ringRadius;
            double y = center.y + wobble;
            double z = center.z + Math.sin(angle) * ringRadius;

            level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                    x, y, z, 1,
                    0.08, 0.08, 0.08, 0.0);
        }

        // Sparse inward streaks make the expanding danger radius visible at a distance.
        if (elapsed % 2 == 0) {
            for (int i = 0; i < 10; i++) {
                double angle = RANDOM.nextDouble() * Math.PI * 2.0;
                double radius = coreRadius + RANDOM.nextDouble() * Math.min(pullRadius * 0.55, 42.0);
                double x = center.x + Math.cos(angle) * radius;
                double y = center.y + RANDOM.nextDouble() * 12.0 - 6.0;
                double z = center.z + Math.sin(angle) * radius;
                level.sendParticles(ParticleTypes.PORTAL,
                        x, y, z, 1,
                        0.0, 0.0, 0.0, 0.04);
            }
        }
    }

    private static double lerp(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    private record Hole(ServerLevel level, Vec3 center) {
    }
}
