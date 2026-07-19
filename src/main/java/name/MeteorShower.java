package name;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Creates visible magma-block meteors using block-display entities. Each meteor is
 * one moving object made from a small sphere of magma blocks, then becomes a crater
 * when it reaches the selected impact point.
 */
public final class MeteorShower {

    private static final RandomSource RANDOM = RandomSource.create();
    private static final List<ActiveMeteor> ACTIVE_METEORS = new ArrayList<>();
    private static int nextMeteorId;

    private MeteorShower() {
    }

    public static void tick(MinecraftServer server, int eventTicksRemaining) {
        // Spawn two or three meteors near every player every 4 seconds. Each meteor
        // receives its own impact point, flight path, visual entity tag, and crater.
        if (eventTicksRemaining % 80 == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                int meteorCount = RANDOM.nextIntBetweenInclusive(2, 3);
                for (int i = 0; i < meteorCount; i++) {
                    spawnMeteor(player);
                }
            }
        }

        Iterator<ActiveMeteor> iterator = ACTIVE_METEORS.iterator();
        while (iterator.hasNext()) {
            ActiveMeteor meteor = iterator.next();
            meteor.tick();
            if (meteor.finished) {
                iterator.remove();
            }
        }
    }

    public static void clear() {
        for (ActiveMeteor meteor : ACTIVE_METEORS) {
            command(meteor.level, "kill @e[type=minecraft:block_display,tag=" + meteor.tag + "]");
        }
        ACTIVE_METEORS.clear();
    }

    private static void spawnMeteor(ServerPlayer player) {
        ServerLevel level = player.level();
        BlockPos impact = findSurfaceNearPlayer(player, 8, 24);
        if (impact == null) return;

        String tag = "hc_meteor_" + nextMeteorId++;

        // Start high and to one side so the meteor crosses the player's view instead
        // of dropping as a perfectly vertical column.
        double directionX = RANDOM.nextBoolean() ? 1.0 : -1.0;
        double directionZ = RANDOM.nextBoolean() ? 1.0 : -1.0;
        double startX = impact.getX() + 38.0 * directionX;
        // Start far above the impact point so players get a clear warning and time to dodge.
        double startY = Math.min(level.getMaxY() - 10.0, impact.getY() + 105.0);
        double startZ = impact.getZ() + 30.0 * directionZ;

        int flightTicks = 72;
        double velocityX = (impact.getX() + 0.5 - startX) / flightTicks;
        double velocityY = (impact.getY() + 1.5 - startY) / flightTicks;
        double velocityZ = (impact.getZ() + 0.5 - startZ) / flightTicks;

        // A compact 3-D sphere: center, six faces, twelve edges, and eight corners.
        int[][] sphereOffsets = {
                {0, 0, 0},
                {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
                {1, 1, 0}, {1, -1, 0}, {-1, 1, 0}, {-1, -1, 0},
                {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1},
                {0, 1, 1}, {0, 1, -1}, {0, -1, 1}, {0, -1, -1},
                {1, 1, 1}, {1, 1, -1}, {1, -1, 1}, {1, -1, -1},
                {-1, 1, 1}, {-1, 1, -1}, {-1, -1, 1}, {-1, -1, -1},
                {2, 0, 0}, {-2, 0, 0}, {0, 2, 0}, {0, -2, 0}, {0, 0, 2}, {0, 0, -2}
        };

        for (int[] offset : sphereOffsets) {
            double x = startX + offset[0];
            double y = startY + offset[1];
            double z = startZ + offset[2];
            command(level,
                    "summon minecraft:block_display " + decimal(x) + " " + decimal(y) + " " + decimal(z)
                            + " {block_state:{Name:\"minecraft:magma_block\"},Tags:[\"" + tag
                            + "\"],view_range:128.0f,shadow_radius:0.0f,shadow_strength:0.0f}");
        }

        command(level, "particle minecraft:flash " + decimal(startX) + " " + decimal(startY)
                + " " + decimal(startZ) + " 0 0 0 0 1 force");
        command(level, "execute positioned " + decimal(startX) + " " + decimal(startY) + " "
                + decimal(startZ)
                + " run playsound minecraft:entity.blaze.shoot master @a[distance=..160] ~ ~ ~ 2.5 0.45");

        ACTIVE_METEORS.add(new ActiveMeteor(level, impact, tag, startX, startY, startZ,
                velocityX, velocityY, velocityZ, flightTicks));
    }

    private static BlockPos findSurfaceNearPlayer(ServerPlayer player, int minDistance, int maxDistance) {
        ServerLevel level = player.level();

        for (int attempt = 0; attempt < 32; attempt++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            int distance = RANDOM.nextIntBetweenInclusive(minDistance, maxDistance);
            int x = player.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = player.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            int startY = Math.max(level.getMinY() + 3,
                    Math.min(level.getMaxY() - 4, player.getBlockY() + 24));

            for (int y = startY; y >= Math.max(level.getMinY() + 2, player.getBlockY() - 30); y--) {
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

    private static void createCrater(ServerLevel level, BlockPos impact, int radius) {
        int cx = impact.getX();
        int cy = impact.getY();
        int cz = impact.getZ();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                if (horizontal > radius + RANDOM.nextDouble() * 0.65) continue;

                int depth = Math.max(1, (int) Math.round((radius - horizontal) * 0.75) + 1);
                for (int dy = 2; dy >= -depth; dy--) {
                    BlockPos pos = new BlockPos(cx + dx, cy + dy, cz + dz);
                    if (pos.getY() > level.getMinY() + 3 && !level.getBlockState(pos).isAir()) {
                        level.destroyBlock(pos, false);
                    }
                }

                BlockPos floor = new BlockPos(cx + dx, cy - depth - 1, cz + dz);
                if (floor.getY() <= level.getMinY() + 3) continue;

                if (horizontal <= 1.6 && RANDOM.nextInt(3) == 0) {
                    level.setBlockAndUpdate(floor.above(), Blocks.LAVA.defaultBlockState());
                } else {
                    level.setBlockAndUpdate(floor, Blocks.MAGMA_BLOCK.defaultBlockState());
                }
            }
        }
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static void command(ServerLevel level, String command) {
        if (level == null || level.getServer() == null) return;
        level.getServer().getCommands().performPrefixedCommand(
                level.getServer().createCommandSourceStack().withLevel(level), command);
    }

    private static final class ActiveMeteor {
        private final ServerLevel level;
        private final BlockPos impact;
        private final String tag;
        private final double velocityX;
        private final double velocityY;
        private final double velocityZ;
        private final int totalFlightTicks;

        private double x;
        private double y;
        private double z;
        private int age;
        private boolean finished;

        private ActiveMeteor(ServerLevel level, BlockPos impact, String tag,
                             double x, double y, double z,
                             double velocityX, double velocityY, double velocityZ,
                             int totalFlightTicks) {
            this.level = level;
            this.impact = impact;
            this.tag = tag;
            this.x = x;
            this.y = y;
            this.z = z;
            this.velocityX = velocityX;
            this.velocityY = velocityY;
            this.velocityZ = velocityZ;
            this.totalFlightTicks = totalFlightTicks;
        }

        private void tick() {
            if (finished) return;

            age++;
            x += velocityX;
            y += velocityY;
            z += velocityZ;

            command(level, "execute as @e[type=minecraft:block_display,tag=" + tag
                    + "] at @s run tp @s ~" + decimal(velocityX)
                    + " ~" + decimal(velocityY) + " ~" + decimal(velocityZ));

            // Dense trail at the moving center of the magma sphere.
            command(level, "particle minecraft:flame " + decimal(x) + " " + decimal(y) + " "
                    + decimal(z) + " 1.4 1.4 1.4 0.08 42 force");
            command(level, "particle minecraft:large_smoke " + decimal(x) + " " + decimal(y) + " "
                    + decimal(z) + " 1.8 1.8 1.8 0.025 28 force");
            if (age % 3 == 0) {
                command(level, "particle minecraft:lava " + decimal(x) + " " + decimal(y) + " "
                        + decimal(z) + " 1.1 1.1 1.1 0.06 12 force");
            }

            if (age >= totalFlightTicks) {
                impact();
            }
        }

        private void impact() {
            command(level, "kill @e[type=minecraft:block_display,tag=" + tag + "]");
            createCrater(level, impact, 6);

            int ix = impact.getX();
            int iy = impact.getY();
            int iz = impact.getZ();

            command(level, "particle minecraft:explosion_emitter " + ix + " " + (iy + 1) + " "
                    + iz + " 0 0 0 0 4 force");
            command(level, "particle minecraft:flame " + ix + " " + (iy + 2) + " " + iz
                    + " 5 3 5 0.12 520 force");
            command(level, "particle minecraft:campfire_cosy_smoke " + ix + " " + (iy + 4) + " "
                    + iz + " 4 5 4 0.035 280 force");
            command(level, "execute positioned " + ix + " " + iy + " " + iz
                    + " run playsound minecraft:entity.generic.explode master @a[distance=..160] ~ ~ ~ 4.0 0.5");
            command(level, "execute positioned " + ix + " " + iy + " " + iz
                    + " run playsound minecraft:entity.lightning_bolt.thunder master @a[distance=..192] ~ ~ ~ 2.4 0.4");

            // Immediate explosion for damage and knockback. The crater itself is generated
            // directly above so it remains consistent in both the Overworld and Nether.
            command(level, "summon minecraft:tnt " + ix + " " + (iy + 1) + " " + iz
                    + " {fuse:1,explosion_power:7.0f}");
            finished = true;
        }
    }
}
