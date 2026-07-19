package name;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * First playable Hunter prototype.
 *
 * The Hunter silently stalks one player before committing to combat. While the
 * encounter is active, DifficultyManager freezes the stage clock and prevents
 * normal events/mutations from ticking. This version intentionally uses a
 * stable vanilla Husk body so the stalking and encounter lock can be tested
 * before a custom player-shaped entity and renderer are added.
 */
public final class HunterManager {

    private enum Phase {
        STALKING,
        ATTACKING
    }

    private static final RandomSource RANDOM = RandomSource.create();

    private static final int MIN_STALK_TICKS = 6 * 60 * 20;
    private static final int MAX_STALK_TICKS = 10 * 60 * 20;
    private static final double MIN_SPAWN_DISTANCE = 120.0;
    private static final double MAX_SPAWN_DISTANCE = 180.0;
    private static final double MIN_STALK_DISTANCE = 35.0;
    private static final double MAX_STALK_DISTANCE = 70.0;
    private static final double FORCED_ATTACK_DISTANCE = 15.0;
    private static final int COVER_SEARCH_ATTEMPTS = 28;
    private static final double MIN_COVER_DISTANCE = 24.0;
    private static final double MAX_COVER_DISTANCE = 62.0;

    // First building prototype: the Hunter can place simple cobblestone
    // scaffolding when normal navigation cannot reach a remembered target.
    private static final int BUILD_COOLDOWN_TICKS = 4;
    private static final int BUILD_AFTER_STUCK_TICKS = 6 * 20;
    private static final int BUILDER_REPLAN_TICKS = 10;
    private static final int BUILDER_MAX_STEPS = 48;
    private static final int BREAK_COOLDOWN_TICKS = 6;
    private static final int BREAK_AFTER_STUCK_TICKS = 4 * 20;

    // Adaptive Hunter-only chunk simulation. Radius 0 = 1x1, radius 1 = 3x3,
    // radius 2 = 5x5. Five-by-five is a hard maximum.
    private static final int MAX_FORCED_CHUNK_RADIUS = 2;
    private static final int CHUNK_SHRINK_DELAY_TICKS = 5 * 20;
    private static final Set<Long> forcedHunterChunks = new HashSet<>();
    private static ServerLevel forcedChunkLevel;
    private static int forcedChunkRadius = -1;
    private static int forcedChunkCenterX = Integer.MIN_VALUE;
    private static int forcedChunkCenterZ = Integer.MIN_VALUE;
    private static int chunkShrinkDelay;

    // Optional developer monitoring tools. The live monitor prints Hunter data
    // in the action bar. The camera can attach to the Hunter without changing
    // the hunted player's game mode, location, rotation, velocity, or AI-visible
    // state. While viewing, the original player body is held perfectly still at
    // the exact state it had when the camera was attached.
    private static final Set<UUID> debugMonitorPlayers = new HashSet<>();
    private static UUID debugSpectatorPlayerId;
    private static Vec3 debugViewerAnchorPosition;
    private static float debugViewerAnchorYaw;
    private static float debugViewerAnchorPitch;

    private static boolean active;
    private static boolean outlineEnabled;
    private static UUID targetPlayerId;
    private static UUID hunterId;
    private static Phase phase = Phase.STALKING;
    private static int stalkTicksRemaining;
    private static int ticksActive;
    private static Vec3 lastHunterPosition = Vec3.ZERO;
    private static int stuckTicks;
    private static Vec3 currentCoverPosition;
    private static int coverRefreshCooldown;
    private static int buildCooldown;
    private static int builderReplanCooldown;
    private static int builderStepsRemaining;
    private static Vec3 builderDestination;
    private static int breakCooldown;

    // Hunter memory: recent places where it saw or strongly believed the player was.
    // These survive sight loss and drive searching instead of perfect tracking.
    private static final int MEMORY_LIMIT = 8;
    private static final Deque<Vec3> rememberedPlayerLocations = new ArrayDeque<>();
    private static Vec3 lastKnownPlayerPosition;
    private static Vec3 currentSearchPosition;
    private static int unseenTicks;
    private static int memoryRecordCooldown;

    // Persistence watchdog. It is deliberately a fallback; vanilla persistence
    // is still the primary protection against despawning.
    private static int missingHunterTicks;
    private static final int WATCHDOG_RESPAWN_DELAY_TICKS = 40;


    /*
     * The Hunter ignores every source of damage except damage caused by the
     * hunted player. This covers falls, drowning, fire, lava, explosions,
     * suffocation, the void, other mobs, and projectiles fired by anyone else.
     * DamageSource#getEntity() resolves the owning attacker for projectiles, so
     * the hunted player's arrows and other owned projectiles still count.
     */
    static {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (hunterId == null || !entity.getUUID().equals(hunterId)) {
                return true;
            }

            Entity attacker = source.getEntity();
            return attacker instanceof ServerPlayer player
                    && targetPlayerId != null
                    && player.getUUID().equals(targetPlayerId);
        });

        // This is a second safety net for fatal environmental damage. ALLOW_DAMAGE
        // should already cancel it, but ALLOW_DEATH guarantees that falls, the
        // void, lava, drowning, suffocation, explosions, and mobs cannot finish
        // the Hunter even if another mod bypasses or reapplies damage.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (hunterId == null || !entity.getUUID().equals(hunterId)) {
                return true;
            }

            Entity attacker = source.getEntity();
            boolean killedByHuntedPlayer = attacker instanceof ServerPlayer player
                    && targetPlayerId != null
                    && player.getUUID().equals(targetPlayerId);

            if (!killedByHuntedPlayer) {
                entity.setHealth(Math.max(1.0F, entity.getMaxHealth()));
                entity.fallDistance = 0.0F;
                return false;
            }
            return true;
        });
    }

    private HunterManager() {
    }

    public static boolean isActive() {
        return active;
    }

    public static boolean isOutlineEnabled() {
        return outlineEnabled;
    }

    public static boolean setOutlineEnabled(MinecraftServer server, boolean enabled) {
        outlineEnabled = enabled;
        Entity hunter = findHunter(server);
        if (hunter != null && !hunter.isRemoved()) {
            hunter.setGlowingTag(enabled);
        }
        return outlineEnabled;
    }

    public static boolean toggleOutline(MinecraftServer server) {
        return setOutlineEnabled(server, !outlineEnabled);
    }

    public static String getStatus() {
        if (!active) {
            return "inactive";
        }

        return phase.name().toLowerCase() + " | stalk time: "
                + Math.max(0, stalkTicksRemaining / 20) + "s";
    }

    public static String getDebugInfo(MinecraftServer server, ServerPlayer viewer) {
        Entity entity = findHunter(server);
        if (!(entity instanceof Husk hunter) || !hunter.isAlive()) {
            return "Hunter not found.";
        }

        ChunkPos chunk = hunter.chunkPosition();
        int width = Math.max(1, forcedChunkRadius * 2 + 1);
        double distance = viewer == null ? 0.0 : hunter.distanceTo(viewer);
        String movement = stuckTicks >= BUILD_AFTER_STUCK_TICKS
                ? "STUCK/BUILDING"
                : (hunter.getNavigation().isDone() ? "IDLE" : "MOVING");

        return String.format(
                "Hunter XYZ: %.1f, %.1f, %.1f | Distance: %.1f | Phase: %s | Chunk: %d, %d | Forced: %dx%d | %s | Stuck: %d",
                hunter.getX(), hunter.getY(), hunter.getZ(), distance, phase.name(),
                chunk.x(), chunk.z(), width, width, movement, stuckTicks
        );
    }

    public static boolean toggleDebugMonitor(ServerPlayer player) {
        UUID id = player.getUUID();
        if (!debugMonitorPlayers.add(id)) {
            debugMonitorPlayers.remove(id);
            return false;
        }
        return true;
    }

    public static boolean isDebugMonitorEnabled(ServerPlayer player) {
        return debugMonitorPlayers.contains(player.getUUID());
    }

    public static boolean startSpectatingHunter(MinecraftServer server, ServerPlayer player) {
        Entity entity = findHunter(server);
        if (!(entity instanceof Husk hunter) || !hunter.isAlive()) {
            return false;
        }

        if (debugSpectatorPlayerId != null) {
            restoreDebugSpectator(server);
        }

        debugSpectatorPlayerId = player.getUUID();
        debugViewerAnchorPosition = player.position();
        debugViewerAnchorYaw = player.getYRot();
        debugViewerAnchorPitch = player.getXRot();

        // This is deliberately camera-only. The player remains in the same game
        // mode and the server continues to see their body at the exact same place
        // and orientation. That prevents camera movement from changing stalking,
        // line-of-sight reactions, targeting, memory, navigation, or building.
        stabilizeDebugViewer(player);
        player.setCamera(hunter);
        return true;
    }

    public static boolean stopSpectatingHunter(MinecraftServer server, ServerPlayer requester) {
        if (debugSpectatorPlayerId == null) {
            return false;
        }
        if (requester != null && !requester.getUUID().equals(debugSpectatorPlayerId)) {
            return false;
        }
        restoreDebugSpectator(server);
        return true;
    }

    public static boolean start(MinecraftServer server, ServerPlayer target) {
        if (active || target == null || !target.isAlive()) {
            return false;
        }

        EventDirector.forceStop(server);

        ServerLevel level = target.level();
        BlockPos spawnPos = chooseSpawnPosition(level, target);
        Husk hunter = EntityTypes.HUSK.spawn(level, spawnPos, EntitySpawnReason.COMMAND);
        if (hunter == null) {
            return false;
        }

        configureHunter(hunter);
        // Disable vanilla long-range player acquisition while stalking.
        setBaseAttribute(hunter, Attributes.FOLLOW_RANGE, 1.0);

        active = true;
        targetPlayerId = target.getUUID();
        hunterId = hunter.getUUID();
        phase = Phase.STALKING;
        stalkTicksRemaining = MIN_STALK_TICKS
                + RANDOM.nextInt(MAX_STALK_TICKS - MIN_STALK_TICKS + 1);
        ticksActive = 0;
        stuckTicks = 0;
        currentCoverPosition = null;
        coverRefreshCooldown = 0;
        buildCooldown = 0;
        builderReplanCooldown = 0;
        builderStepsRemaining = 0;
        builderDestination = null;
        breakCooldown = 0;
        lastHunterPosition = hunter.position();
        rememberedPlayerLocations.clear();
        // The Hunter begins with only a rough clue, not the player's exact live
        // coordinates. Exact positions enter memory only after direct line of sight.
        lastKnownPlayerPosition = createApproximateClue(target.position());
        rememberPlayerPosition(lastKnownPlayerPosition);
        currentSearchPosition = null;
        unseenTicks = 0;
        memoryRecordCooldown = 0;
        missingHunterTicks = 0;
        releaseHunterChunks();
        chunkShrinkDelay = 0;

        updateHunterChunkLoading(hunter);
        syncHunterState(server, true);

        return true;
    }

    public static void stop(MinecraftServer server) {
        Entity hunter = findHunter(server);
        if (hunter != null && hunter.isAlive()) {
            hunter.discard();
        }

        clearState(server);
    }

    public static void tick(MinecraftServer server) {
        if (!active) {
            return;
        }

        ticksActive++;
        if (ticksActive % 20 == 0) {
            syncHunterState(server, true);
        }

        ServerPlayer target = server.getPlayerList().getPlayer(targetPlayerId);
        stabilizeDebugViewer(server);
        Entity entity = findHunter(server);

        if (target == null || !target.isAlive()) {
            if (entity != null && entity.isAlive()) {
                entity.discard();
            }
            clearState(server);
            return;
        }

        if (entity == null || entity.isRemoved()) {
            missingHunterTicks++;
            if (missingHunterTicks >= WATCHDOG_RESPAWN_DELAY_TICKS) {
                respawnMissingHunter(target);
                missingHunterTicks = 0;
            }
            return;
        }

        if (!(entity instanceof Husk hunter)) {
            missingHunterTicks++;
            return;
        }

        // A confirmed death ends the encounter. A merely missing/removed entity is
        // handled by the watchdog above so accidental despawning cannot end it.
        if (!hunter.isAlive() || hunter.getHealth() <= 0.0F) {
            clearState(server);
            return;
        }

        missingHunterTicks = 0;

        // Environmental damage is cancelled by ALLOW_DAMAGE. Clearing the
        // accumulated fall distance also prevents a stale fall from being
        // applied later after unusual teleports or camera debugging.
        hunter.fallDistance = 0.0F;

        updateDebugMonitors(server, hunter);

        // Spectator camera does not pause the Hunter. Its AI, navigation, memory,
        // and advanced builder continue to tick while the camera follows it.

        // Keep the Hunter completely silent in both phases.
        hunter.setSilent(true);
        if (buildCooldown > 0) {
            buildCooldown--;
        }
        if (breakCooldown > 0) {
            breakCooldown--;
        }

        if (hunter.level() != target.level() && !isTargetBeingViewedThroughCamera()) {
            teleportBehindTarget(hunter, target, 55.0);
        }

        // Prefer normal ground navigation first. Construction is only a fallback
        // after navigation has genuinely failed or the Hunter cannot escape water.
        if (phase == Phase.STALKING) {
            tickStalking(hunter, target);
        } else {
            tickAttacking(hunter, target);
        }

        updateStuckRecovery(hunter, target);
        tickAdvancedBuilder(hunter, target);
        updateHunterChunkLoading(hunter);
    }

    private static void tickStalking(Husk hunter, ServerPlayer target) {
        stalkTicksRemaining--;
        if (coverRefreshCooldown > 0) {
            coverRefreshCooldown--;
        }
        // The vanilla Husk target selector can silently reacquire the player and
        // overwrite our stalking navigation. Clear any target it selected during
        // the entity tick and cancel that direct pursuit before applying our own
        // cover/search movement.
        boolean vanillaAcquiredTarget = hunter.getTarget() != null;
        hunter.setTarget(null);
        hunter.setAggressive(false);
        if (vanillaAcquiredTarget) {
            hunter.getNavigation().stop();
        }

        double distance = hunter.distanceTo(target);
        boolean targetCanSeeHunter = !isTargetBeingViewedThroughCamera()
                && isLookingAt(target, hunter)
                && target.hasLineOfSight(hunter);
        updateHunterMemory(hunter, target);

        // Getting close or damaging the Hunter immediately begins combat.
        if ((!isTargetBeingViewedThroughCamera() && distance <= FORCED_ATTACK_DISTANCE)
                || hunter.getHealth() < hunter.getMaxHealth()
                || stalkTicksRemaining <= 0) {
            beginAttack(hunter, target);
            return;
        }

        if (targetCanSeeHunter) {
            // Do not simply freeze in the open. Search for a nearby position with
            // solid terrain between the player and the Hunter, then slip behind it.
            Vec3 cover = findCoverPosition(hunter, target);
            if (cover != null) {
                currentCoverPosition = cover;
                coverRefreshCooldown = 30;
                hunter.getNavigation().moveTo(cover.x, cover.y, cover.z, 1.28);
            } else {
                hunter.getNavigation().stop();
            }
            return;
        }

        // If a cover point was chosen, keep moving toward it until reached or
        // until it no longer actually hides the Hunter from the player.
        if (currentCoverPosition != null) {
            if (hunter.position().distanceToSqr(currentCoverPosition) <= 2.5 * 2.5) {
                hunter.getNavigation().stop();
                currentCoverPosition = null;
            } else if (isPositionHiddenFromPlayer(target, currentCoverPosition)) {
                hunter.getNavigation().moveTo(currentCoverPosition.x, currentCoverPosition.y, currentCoverPosition.z, 1.16);
                return;
            } else {
                currentCoverPosition = null;
            }
        }

        // When the Hunter has lost sight for a while, it searches remembered
        // locations instead of receiving the player's exact live coordinates.
        if (unseenTicks > 5 * 20) {
            Vec3 search = chooseMemorySearchPosition(hunter);
            if (search != null) {
                currentSearchPosition = search;
                hunter.getNavigation().moveTo(search.x, search.y, search.z, 1.12);
                return;
            }
        }

        // When too far away but it still has recent information, silently follow
        // toward cover around the last known location.
        if (distance > MAX_STALK_DISTANCE) {
            Vec3 cover = unseenTicks <= 5 * 20 ? findCoverPosition(hunter, target) : null;
            Vec3 destination = cover != null
                    ? cover
                    : (lastKnownPlayerPosition != null ? lastKnownPlayerPosition : pointBehind(target, 45.0));
            currentCoverPosition = cover;
            hunter.getNavigation().moveTo(destination.x, destination.y, destination.z, 1.18);
            return;
        }

        // When too close during stalking, back away to preserve the unsettling gap.
        if (distance < MIN_STALK_DISTANCE) {
            Vec3 away = hunter.position().subtract(target.position()).normalize();
            Vec3 retreat = target.position().add(away.scale(52.0));
            hunter.getNavigation().moveTo(retreat.x, retreat.y, retreat.z, 1.25);
            return;
        }

        // Reposition occasionally, preferring terrain that blocks the player's view.
        if (ticksActive % 60 == 0 && coverRefreshCooldown <= 0) {
            Vec3 cover = findCoverPosition(hunter, target);
            Vec3 destination = cover != null
                    ? cover
                    : pointBehind(target, 45.0 + RANDOM.nextDouble() * 18.0);
            currentCoverPosition = cover;
            coverRefreshCooldown = 40;
            hunter.getNavigation().moveTo(destination.x, destination.y, destination.z, 1.05);
        }
    }

    private static Vec3 findCoverPosition(Husk hunter, ServerPlayer target) {
        ServerLevel level = target.level();
        Vec3 best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int attempt = 0; attempt < COVER_SEARCH_ATTEMPTS; attempt++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            double distance = MIN_COVER_DISTANCE
                    + RANDOM.nextDouble() * (MAX_COVER_DISTANCE - MIN_COVER_DISTANCE);

            int x = (int) Math.floor(target.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(target.getZ() + Math.sin(angle) * distance);
            BlockPos surface = level.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    new BlockPos(x, 0, z)
            );

            Vec3 candidate = new Vec3(surface.getX() + 0.5, surface.getY(), surface.getZ() + 0.5);
            if (!isPositionHiddenFromPlayer(target, candidate)) {
                continue;
            }

            double pathCost = hunter.position().distanceTo(candidate);
            double targetDistance = target.position().distanceTo(candidate);
            double behindBonus = isBehindPlayer(target, candidate) ? 24.0 : 0.0;
            double score = behindBonus + targetDistance * 0.35 - pathCost * 0.18;

            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        return best;
    }

    private static boolean isPositionHiddenFromPlayer(ServerPlayer target, Vec3 position) {
        Vec3 start = target.getEyePosition();
        Vec3 end = position.add(0.0, 1.45, 0.0);
        BlockHitResult hit = target.level().clip(new ClipContext(
                start,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                target
        ));

        return hit.getType() == HitResult.Type.BLOCK
                && hit.getLocation().distanceToSqr(end) > 1.5 * 1.5;
    }

    private static boolean isBehindPlayer(ServerPlayer target, Vec3 position) {
        Vec3 toPosition = position.subtract(target.position());
        Vec3 horizontal = new Vec3(toPosition.x, 0.0, toPosition.z);
        if (horizontal.lengthSqr() < 0.0001) {
            return false;
        }

        Vec3 look = target.getLookAngle();
        Vec3 viewHorizontal = new Vec3(look.x, 0.0, look.z);
        if (viewHorizontal.lengthSqr() < 0.0001) {
            return false;
        }

        return viewHorizontal.normalize().dot(horizontal.normalize()) < -0.25;
    }

    private static void tickAttacking(Husk hunter, ServerPlayer target) {
        updateHunterMemory(hunter, target);
        boolean canSeeTarget = canUseLiveTarget(hunter, target);

        if (canSeeTarget) {
            // Visible pursuit mode: exact coordinates are allowed only for this
            // tick while direct line of sight exists. The Hunter commits to the
            // player instead of selecting random memory-search points.
            currentSearchPosition = null;
            currentCoverPosition = null;
            builderDestination = target.position();
            hunter.setTarget(target);
            hunter.setAggressive(true);

            BlockPos targetFeet = target.blockPosition();
            var path = hunter.getNavigation().createPath(targetFeet, 1);
            boolean groundPathReaches = path != null && path.canReach();
            double verticalDifference = target.getY() - hunter.getY();

            // Run normally whenever vanilla ground navigation can truly reach the
            // visible player. Construction is reserved for gaps, walls, water,
            // trees, cliffs, and large height differences.
            if (groundPathReaches && verticalDifference < 2.25) {
                builderStepsRemaining = 0;
                builderDestination = null;
                if (ticksActive % 5 == 0 || hunter.getNavigation().isDone()) {
                    hunter.getNavigation().moveTo(target, 1.52);
                }
            } else {
                // Start the local Baritone-style planner immediately rather than
                // running circles at the base of an unreachable structure.
                builderDestination = target.position();
                builderStepsRemaining = Math.max(builderStepsRemaining, BUILDER_MAX_STEPS);
                builderReplanCooldown = 0;
                hunter.getNavigation().stop();
            }
        } else {
            // No sight means no live-coordinate building. Calmly navigate toward
            // the last legitimate sighting and remembered search locations.
            builderDestination = null;
            builderStepsRemaining = 0;
            hunter.setTarget(null);
            hunter.setAggressive(false);
            Vec3 search = chooseMemorySearchPosition(hunter);
            if (search != null && (ticksActive % 20 == 0 || hunter.getNavigation().isDone())) {
                hunter.getNavigation().moveTo(search.x, search.y, search.z, 1.28);
            }
        }

        // If the player gets extremely far away, recover distance without placing
        // the Hunter directly beside them.
        if (!isTargetBeingViewedThroughCamera()
                && hunter.distanceToSqr(target) > 190.0 * 190.0) {
            teleportBehindTarget(hunter, target, 70.0);
        }
    }

    private static void beginAttack(Husk hunter, ServerPlayer target) {
        phase = Phase.ATTACKING;
        // Restore combat range, but never convert a debug camera attachment into
        // perfect target knowledge. While the camera is attached, combat begins
        // by searching the last legitimate sighting instead.
        setBaseAttribute(hunter, Attributes.FOLLOW_RANGE, 256.0);
        if (canUseLiveTarget(hunter, target)) {
            hunter.setTarget(target);
            hunter.setAggressive(true);
            hunter.getNavigation().moveTo(target, 1.45);
        } else {
            hunter.setTarget(null);
            hunter.setAggressive(false);
            Vec3 search = chooseMemorySearchPosition(hunter);
            if (search != null) {
                hunter.getNavigation().moveTo(search.x, search.y, search.z, 1.28);
            }
        }
    }

    private static void configureHunter(Husk hunter) {
        hunter.setSilent(true);
        hunter.setGlowingTag(outlineEnabled);
        hunter.setPersistenceRequired();
        hunter.setCanBreakDoors(true);
        hunter.setCustomName(Component.literal("The Hunter").withStyle(ChatFormatting.DARK_RED));
        hunter.setCustomNameVisible(false);

        setBaseAttribute(hunter, Attributes.MAX_HEALTH, 20.0);
        setBaseAttribute(hunter, Attributes.MOVEMENT_SPEED, 0.36);
        setBaseAttribute(hunter, Attributes.ATTACK_DAMAGE, 13.0);
        setBaseAttribute(hunter, Attributes.ARMOR, 16.0);
        setBaseAttribute(hunter, Attributes.ARMOR_TOUGHNESS, 6.0);
        setBaseAttribute(hunter, Attributes.KNOCKBACK_RESISTANCE, 0.85);
        setBaseAttribute(hunter, Attributes.FOLLOW_RANGE, 256.0);

        hunter.setHealth(hunter.getMaxHealth());
        hunter.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));
        hunter.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
        hunter.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
        hunter.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS));
        hunter.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.NETHERITE_BOOTS));
    }

    private static void setBaseAttribute(Husk hunter, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double value) {
        AttributeInstance instance = hunter.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private static BlockPos chooseSpawnPosition(ServerLevel level, ServerPlayer target) {
        // Try several rings and angles so the Hunter strongly prefers dry,
        // two-block-high ground instead of spawning in an ocean or inside terrain.
        for (int attempt = 0; attempt < 48; attempt++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            double distance = MIN_SPAWN_DISTANCE
                    + RANDOM.nextDouble() * (MAX_SPAWN_DISTANCE - MIN_SPAWN_DISTANCE);

            int x = (int) Math.floor(target.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(target.getZ() + Math.sin(angle) * distance);
            BlockPos surface = level.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    new BlockPos(x, 0, z)
            );

            if (isValidHunterSpawn(level, surface)) {
                return surface;
            }
        }

        // Last-resort fallback. The builder can still escape if this location is wet.
        double angle = RANDOM.nextDouble() * Math.PI * 2.0;
        int x = (int) Math.floor(target.getX() + Math.cos(angle) * MIN_SPAWN_DISTANCE);
        int z = (int) Math.floor(target.getZ() + Math.sin(angle) * MIN_SPAWN_DISTANCE);
        return level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(x, 0, z)
        );
    }

    private static boolean isValidHunterSpawn(ServerLevel level, BlockPos feet) {
        return level.getWorldBorder().isWithinBounds(feet)
                && level.getFluidState(feet).isEmpty()
                && level.getFluidState(feet.above()).isEmpty()
                && level.getFluidState(feet.below()).isEmpty()
                && level.getBlockState(feet).canBeReplaced()
                && level.getBlockState(feet.above()).canBeReplaced()
                && !level.getBlockState(feet.below()).canBeReplaced();
    }

    private static Vec3 pointBehind(ServerPlayer target, double distance) {
        Vec3 look = target.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
        if (horizontal.lengthSqr() < 0.0001) {
            horizontal = new Vec3(0.0, 0.0, 1.0);
        }

        return target.position().subtract(horizontal.normalize().scale(distance));
    }

    private static boolean isLookingAt(ServerPlayer player, Entity hunter) {
        Vec3 toHunter = hunter.getEyePosition().subtract(player.getEyePosition()).normalize();
        Vec3 view = player.getLookAngle().normalize();
        double distance = player.distanceTo(hunter);
        double threshold = distance < 25.0 ? 0.72 : 0.88;
        return view.dot(toHunter) >= threshold;
    }

    private static void updateStuckRecovery(Husk hunter, ServerPlayer target) {
        if (ticksActive % 20 != 0) {
            return;
        }

        boolean shouldBeMoving =
                !hunter.getNavigation().isDone()
                        || currentCoverPosition != null
                        || currentSearchPosition != null
                        || phase == Phase.ATTACKING;

        if (shouldBeMoving
                && hunter.position().distanceToSqr(lastHunterPosition) < 0.30 * 0.30) {
            stuckTicks += 20;
        } else {
            stuckTicks = 0;
        }

        lastHunterPosition = hunter.position();

        if (phase == Phase.ATTACKING
                && stuckTicks >= BUILD_AFTER_STUCK_TICKS
                && buildCooldown <= 0
                && tryBuildPath(hunter, target)) {
            buildCooldown = BUILD_COOLDOWN_TICKS;
            stuckTicks = Math.max(0, stuckTicks - 30);
            lastHunterPosition = hunter.position();
            return;
        }

        if (stuckTicks >= 10 * 20) {
            if (phase == Phase.STALKING) {
                // During stalking, never teleport to the player's live position.
                // Abandon the failed route and immediately try a grounded,
                // reachable memory-search destination instead.
                hunter.getNavigation().stop();
                currentCoverPosition = null;
                currentSearchPosition = null;
                coverRefreshCooldown = 0;

                Vec3 replacementDestination = chooseMemorySearchPosition(hunter);
                if (replacementDestination != null) {
                    boolean started = hunter.getNavigation().moveTo(
                            replacementDestination.x,
                            replacementDestination.y,
                            replacementDestination.z,
                            1.18
                    );

                    if (!started) {
                        currentSearchPosition = null;
                    }
                } else if (hunter.distanceToSqr(target)
                        > MAX_STALK_DISTANCE * MAX_STALK_DISTANCE) {
                    Vec3 fallback = findCoverPosition(hunter, target);
                    if (fallback != null) {
                        currentCoverPosition = fallback;
                        hunter.getNavigation().moveTo(
                                fallback.x,
                                fallback.y,
                                fallback.z,
                                1.18
                        );
                    }
                }
            } else if (!isTargetBeingViewedThroughCamera()
                    && hunter.distanceToSqr(target) > 190.0 * 190.0) {
                // Combat-only emergency recovery, and only at extreme distance.
                teleportBehindTarget(hunter, target, 72.0);
            }
            stuckTicks = 0;
            lastHunterPosition = hunter.position();
        }
    }

    /**
     * Baritone-inspired local builder. This is intentionally a server-side mob
     * planner rather than the real Baritone client library. It repeatedly chooses
     * a remembered destination, lays bridges through water and gaps, pillars up,
     * and creates short stair steps instead of waiting for vanilla navigation.
     */
    private static boolean tickAdvancedBuilder(Husk hunter, ServerPlayer target) {
        if (!(hunter.level() instanceof ServerLevel level)) {
            return false;
        }

        if (builderReplanCooldown > 0) {
            builderReplanCooldown--;
        }

        Vec3 intendedDestination = chooseBuilderDestination(hunter, target);
        double destinationDistanceSqr = intendedDestination == null
                ? 0.0
                : hunter.position().distanceToSqr(intendedDestination);

        boolean visiblePursuit = phase == Phase.ATTACKING
                && canUseLiveTarget(hunter, target);

        // While the player is visible, the planner may engage immediately when
        // a normal route cannot reach them. Without sight, it becomes conservative
        // and builds only as a genuine escape fallback.
        boolean waterEscapeNeeded = hunter.isInWater()
                && (visiblePursuit || stuckTicks >= 8 * 20)
                && destinationDistanceSqr > 4.0 * 4.0;
        boolean navigationTrulyFailed = stuckTicks >= BUILD_AFTER_STUCK_TICKS
                && destinationDistanceSqr > 4.0 * 4.0;
        boolean visibleRouteNeedsConstruction = false;
        if (visiblePursuit && intendedDestination != null) {
            BlockPos visibleGoal = BlockPos.containing(
                    intendedDestination.x,
                    intendedDestination.y,
                    intendedDestination.z
            );
            var visiblePath = hunter.getNavigation().createPath(visibleGoal, 1);
            double verticalDifference = intendedDestination.y - hunter.getY();
            visibleRouteNeedsConstruction = visiblePath == null
                    || !visiblePath.canReach()
                    || verticalDifference >= 2.25;
        }
        boolean needsBuilder = builderStepsRemaining > 0
                || waterEscapeNeeded
                || navigationTrulyFailed
                || visibleRouteNeedsConstruction;

        if (!needsBuilder) {
            builderDestination = null;
            builderStepsRemaining = 0;
            return false;
        }

        if (builderDestination == null || builderReplanCooldown <= 0) {
            builderDestination = chooseBuilderDestination(hunter, target);
            builderReplanCooldown = BUILDER_REPLAN_TICKS;
            if (builderStepsRemaining <= 0) {
                builderStepsRemaining = BUILDER_MAX_STEPS;
            }
        }

        if (builderDestination == null) {
            return false;
        }

        Vec3 delta = builderDestination.subtract(hunter.position());
        Vec3 horizontal = new Vec3(delta.x, 0.0, delta.z);
        if (horizontal.lengthSqr() < 2.0 * 2.0) {
            builderDestination = null;
            builderStepsRemaining = 0;
            return false;
        }

        horizontal = horizontal.normalize();

        // The moment vanilla navigation can make progress on solid ground, stop
        // construction completely and let the Hunter run. This prevents the
        // builder state from continuing for dozens of blocks after one gap.
        if (!hunter.isInWater()
                && !level.getBlockState(hunter.blockPosition().below()).canBeReplaced()) {
            BlockPos groundGoal = BlockPos.containing(
                    builderDestination.x,
                    builderDestination.y,
                    builderDestination.z
            );
            var groundPath = hunter.getNavigation().createPath(groundGoal, 1);
            double verticalDifferenceToGoal = builderDestination.y - hunter.getY();
            if (groundPath != null && groundPath.canReach()
                    && (!visiblePursuit || verticalDifferenceToGoal < 2.25)) {
                builderDestination = null;
                builderStepsRemaining = 0;
                stuckTicks = 0;
                hunter.getNavigation().moveTo(
                        intendedDestination.x,
                        intendedDestination.y,
                        intendedDestination.z,
                        phase == Phase.ATTACKING ? 1.52 : 1.18
                );
                return false;
            }
        }

        if (buildCooldown > 0) {
            pushHunterToward(hunter, horizontal, hunter.isInWater() ? 0.12 : 0.08);
            return true;
        }

        BlockPos feet = hunter.blockPosition();
        BlockPos belowFeet = feet.below();
        BlockPos forwardFeet = BlockPos.containing(
                hunter.getX() + horizontal.x * 1.15,
                hunter.getY(),
                hunter.getZ() + horizontal.z * 1.15
        );
        BlockPos forwardSupport = forwardFeet.below();
        double verticalDifference = builderDestination.y - hunter.getY();

        // Clear a two-block-high tunnel only after ordinary navigation has
        // genuinely failed. Breaking is preferred over wasteful bridging when
        // solid blocks are directly blocking the remembered route.
        if ((visiblePursuit || stuckTicks >= BREAK_AFTER_STUCK_TICKS) && breakCooldown <= 0) {
            if (tryBreakObstruction(level, hunter, horizontal)) {
                hunter.getNavigation().stop();
                hunter.getNavigation().moveTo(
                        builderDestination.x,
                        builderDestination.y,
                        builderDestination.z,
                        phase == Phase.ATTACKING ? 1.45 : 1.18
                );
                breakCooldown = BREAK_COOLDOWN_TICKS;
                stuckTicks = Math.max(0, stuckTicks - 20);
                return true;
            }
        }

        // Never create a floating platform. A scaffold block must attach to
        // existing terrain or to a previously placed bridge/pillar block.
        if (level.getBlockState(belowFeet).canBeReplaced()
                && canReplaceWithScaffold(level, belowFeet)
                && hasScaffoldAnchor(level, belowFeet)) {
            placeScaffold(level, belowFeet);
            hunter.setDeltaMovement(horizontal.x * 0.12, 0.30, horizontal.z * 0.12);
            consumeBuilderStep();
            return true;
        }

        // Bridge only after prolonged navigation failure, only across an actual
        // missing floor, and only when the new block connects to solid terrain.
        boolean actualGapAhead = level.getBlockState(forwardSupport).canBeReplaced();
        boolean hunterHasStableFooting = !level.getBlockState(belowFeet).canBeReplaced();
        if ((visiblePursuit || stuckTicks >= BUILD_AFTER_STUCK_TICKS)
                && actualGapAhead
                && hunterHasStableFooting
                && canReplaceWithScaffold(level, forwardSupport)
                && hasScaffoldAnchor(level, forwardSupport)
                && hasBodyRoom(level, forwardFeet)) {
            placeScaffold(level, forwardSupport);
            hunter.getNavigation().moveTo(
                    forwardFeet.getX() + 0.5,
                    forwardFeet.getY(),
                    forwardFeet.getZ() + 0.5,
                    1.35
            );
            pushHunterToward(hunter, horizontal, 0.13);
            consumeBuilderStep();
            return true;
        }

        // When the visible player is substantially above (for example on a
        // tree), stop circling at the base and climb with an anchored pillar or
        // staircase. This mode is unavailable once line of sight is lost.
        if (visiblePursuit && verticalDifference > 2.25) {
            BlockPos pillarBlock = feet.below();
            if (canReplaceWithScaffold(level, pillarBlock)
                    && hasScaffoldAnchor(level, pillarBlock)) {
                placeScaffold(level, pillarBlock);
                hunter.setDeltaMovement(horizontal.x * 0.06, 0.42, horizontal.z * 0.06);
                consumeBuilderStep();
                return true;
            }
        }

        // Build a staircase when the remembered destination is higher.
        if (verticalDifference > 1.25) {
            BlockPos stairSupport = forwardFeet;
            BlockPos stairFeet = forwardFeet.above();
            if (canReplaceWithScaffold(level, stairSupport)
                    && hasScaffoldAnchor(level, stairSupport)
                    && hasBodyRoom(level, stairFeet)) {
                placeScaffold(level, stairSupport);
                hunter.setDeltaMovement(horizontal.x * 0.18, 0.42, horizontal.z * 0.18);
                consumeBuilderStep();
                return true;
            }

            if (canReplaceWithScaffold(level, belowFeet) && hasScaffoldAnchor(level, belowFeet)) {
                placeScaffold(level, belowFeet);
                hunter.setDeltaMovement(horizontal.x * 0.05, 0.42, horizontal.z * 0.05);
                consumeBuilderStep();
                return true;
            }
        }

        // If the floor exists, hand movement back to navigation but keep the
        // builder active long enough to re-evaluate the next obstruction.
        boolean moved = hunter.getNavigation().moveTo(
                builderDestination.x,
                builderDestination.y,
                builderDestination.z,
                phase == Phase.ATTACKING ? 1.45 : 1.18
        );
        if (!moved) {
            pushHunterToward(hunter, horizontal, hunter.isInWater() ? 0.16 : 0.10);
        }
        builderStepsRemaining--;
        if (builderStepsRemaining <= 0) {
            builderDestination = null;
        }
        return true;
    }

    /**
     * Breaks only the blocks occupying the Hunter's immediate body corridor.
     * It never mines toward the player's live coordinates unless the player is
     * visible; builderDestination already enforces the memory rule.
     */
    private static boolean tryBreakObstruction(ServerLevel level, Husk hunter, Vec3 horizontal) {
        BlockPos feet = hunter.blockPosition();

        int stepX;
        int stepZ;
        if (Math.abs(horizontal.x) >= Math.abs(horizontal.z)) {
            stepX = horizontal.x >= 0.0 ? 1 : -1;
            stepZ = 0;
        } else {
            stepX = 0;
            stepZ = horizontal.z >= 0.0 ? 1 : -1;
        }

        BlockPos frontFeet = feet.offset(stepX, 0, stepZ);
        BlockPos frontHead = frontFeet.above();
        BlockPos currentHead = feet.above();

        // Escape suffocation or a spawn inside terrain first.
        if (canHunterBreak(level, currentHead)) {
            return breakHunterBlock(level, hunter, currentHead);
        }
        if (canHunterBreak(level, feet)) {
            return breakHunterBlock(level, hunter, feet);
        }

        // Open a normal two-block-high passage. Head blocks are removed first so
        // the Hunter does not walk forward and suffocate beneath a low ceiling.
        if (canHunterBreak(level, frontHead)) {
            return breakHunterBlock(level, hunter, frontHead);
        }
        if (canHunterBreak(level, frontFeet)) {
            return breakHunterBlock(level, hunter, frontFeet);
        }

        // If the immediate corridor is open but a one-block rise has no headroom,
        // clear the block above that step instead of starting another bridge.
        BlockPos raisedHead = frontHead.above();
        if (!level.getBlockState(frontFeet).canBeReplaced()
                && canHunterBreak(level, raisedHead)) {
            return breakHunterBlock(level, hunter, raisedHead);
        }

        return false;
    }

    private static boolean canHunterBreak(ServerLevel level, BlockPos pos) {
        if (!level.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }

        var state = level.getBlockState(pos);
        if (state.isAir() || state.canBeReplaced() || state.hasBlockEntity()) {
            return false;
        }

        // Preserve unbreakable or world-critical blocks. Containers and other
        // block entities are also protected above so the Hunter cannot erase
        // chests, furnaces, spawners, command blocks, or modded machines.
        return !state.is(Blocks.BEDROCK)
                && !state.is(Blocks.BARRIER)
                && !state.is(Blocks.END_PORTAL)
                && !state.is(Blocks.END_PORTAL_FRAME)
                && !state.is(Blocks.NETHER_PORTAL)
                && !state.is(Blocks.REINFORCED_DEEPSLATE)
                && !state.is(Blocks.STRUCTURE_BLOCK)
                && !state.is(Blocks.JIGSAW)
                && !state.is(Blocks.COMMAND_BLOCK)
                && !state.is(Blocks.CHAIN_COMMAND_BLOCK)
                && !state.is(Blocks.REPEATING_COMMAND_BLOCK);
    }

    private static boolean breakHunterBlock(ServerLevel level, Husk hunter, BlockPos pos) {
        // No drops prevents the Hunter from flooding the world with mined items.
        // Passing the Hunter as the breaking entity keeps block callbacks grounded.
        return level.destroyBlock(pos, false, hunter);
    }

    private static Vec3 chooseBuilderDestination(Husk hunter, ServerPlayer target) {
        // Exact live coordinates are permitted only while the Hunter can directly
        // see the player. Once sight is lost, construction follows memory/search.
        if (canUseLiveTarget(hunter, target)) {
            return target.position();
        }
        if (currentSearchPosition != null) {
            return currentSearchPosition;
        }
        if (lastKnownPlayerPosition != null) {
            return lastKnownPlayerPosition;
        }
        return rememberedPlayerLocations.peekLast();
    }

    private static void consumeBuilderStep() {
        buildCooldown = BUILD_COOLDOWN_TICKS;
        builderStepsRemaining--;
        stuckTicks = Math.max(0, stuckTicks - 20);
        if (builderStepsRemaining <= 0) {
            builderDestination = null;
        }
    }

    private static void pushHunterToward(Husk hunter, Vec3 horizontal, double speed) {
        Vec3 velocity = hunter.getDeltaMovement();
        hunter.setDeltaMovement(horizontal.x * speed, velocity.y, horizontal.z * speed);
    }

    private static boolean hasBodyRoom(ServerLevel level, BlockPos feet) {
        return level.getBlockState(feet).canBeReplaced()
                && level.getBlockState(feet.above()).canBeReplaced();
    }

    private static boolean canReplaceWithScaffold(ServerLevel level, BlockPos pos) {
        return level.getWorldBorder().isWithinBounds(pos)
                && level.getBlockState(pos).canBeReplaced()
                && level.getFluidState(pos).isEmpty();
    }

    private static boolean hasScaffoldAnchor(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            var state = level.getBlockState(neighbor);
            if (!state.isAir() && !state.canBeReplaced() && level.getFluidState(neighbor).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void placeScaffold(ServerLevel level, BlockPos pos) {
        level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3);
    }

    // Kept for compatibility with the older stuck-recovery call.
    private static boolean tryBuildPath(Husk hunter, ServerPlayer target) {
        builderDestination = chooseBuilderDestination(hunter, target);
        builderStepsRemaining = BUILDER_MAX_STEPS;
        builderReplanCooldown = 0;
        return tickAdvancedBuilder(hunter, target);
    }

    /**
     * Keeps a small moving simulation area around the Hunter so it continues to
     * think and move outside the player's simulation distance. The area expands
     * immediately and shrinks only after a short delay to avoid chunk thrashing.
     */
    private static void updateHunterChunkLoading(Husk hunter) {
        if (!(hunter.level() instanceof ServerLevel level)) {
            return;
        }

        int requestedRadius = chooseForcedChunkRadius(hunter);
        if (requestedRadius > forcedChunkRadius) {
            forcedChunkRadius = requestedRadius;
            chunkShrinkDelay = CHUNK_SHRINK_DELAY_TICKS;
        } else if (requestedRadius < forcedChunkRadius) {
            if (chunkShrinkDelay > 0) {
                chunkShrinkDelay--;
            } else {
                forcedChunkRadius = requestedRadius;
                chunkShrinkDelay = CHUNK_SHRINK_DELAY_TICKS;
            }
        } else {
            chunkShrinkDelay = CHUNK_SHRINK_DELAY_TICKS;
        }

        forcedChunkRadius = Math.max(0, Math.min(MAX_FORCED_CHUNK_RADIUS, forcedChunkRadius));
        ChunkPos center = hunter.chunkPosition();

        if (forcedChunkLevel == level
                && forcedChunkCenterX == center.x()
                && forcedChunkCenterZ == center.z()
                && forcedHunterChunks.size() == squareChunkCount(forcedChunkRadius)) {
            return;
        }

        Set<Long> desired = new HashSet<>();
        for (int dx = -forcedChunkRadius; dx <= forcedChunkRadius; dx++) {
            for (int dz = -forcedChunkRadius; dz <= forcedChunkRadius; dz++) {
                desired.add(ChunkPos.pack(center.x() + dx, center.z() + dz));
            }
        }

        if (forcedChunkLevel != null && forcedChunkLevel != level) {
            releaseHunterChunks();
        }

        forcedChunkLevel = level;

        for (long packed : new HashSet<>(forcedHunterChunks)) {
            if (!desired.contains(packed)) {
                level.setChunkForced(ChunkPos.getX(packed), ChunkPos.getZ(packed), false);
                forcedHunterChunks.remove(packed);
            }
        }

        for (long packed : desired) {
            if (forcedHunterChunks.add(packed)) {
                level.setChunkForced(ChunkPos.getX(packed), ChunkPos.getZ(packed), true);
            }
        }

        forcedChunkCenterX = center.x();
        forcedChunkCenterZ = center.z();
    }

    private static int chooseForcedChunkRadius(Husk hunter) {
        // 5x5 only for building/stuck recovery or other genuinely complex movement.
        if (buildCooldown > 0 || stuckTicks >= 2 * 20) {
            return 2;
        }

        // 3x3 while navigating, searching, hiding, or actively fighting.
        if (phase == Phase.ATTACKING
                || !hunter.getNavigation().isDone()
                || currentCoverPosition != null
                || currentSearchPosition != null) {
            return 1;
        }

        // 1x1 while idle or waiting.
        return 0;
    }

    private static int squareChunkCount(int radius) {
        int width = radius * 2 + 1;
        return width * width;
    }

    private static void releaseHunterChunks() {
        if (forcedChunkLevel != null) {
            for (long packed : forcedHunterChunks) {
                forcedChunkLevel.setChunkForced(ChunkPos.getX(packed), ChunkPos.getZ(packed), false);
            }
        }
        forcedHunterChunks.clear();
        forcedChunkLevel = null;
        forcedChunkRadius = -1;
        forcedChunkCenterX = Integer.MIN_VALUE;
        forcedChunkCenterZ = Integer.MIN_VALUE;
        chunkShrinkDelay = 0;
    }

    private static void updateDebugMonitors(MinecraftServer server, Husk hunter) {
        if (ticksActive % 10 != 0 || debugMonitorPlayers.isEmpty()) {
            return;
        }

        for (UUID playerId : new HashSet<>(debugMonitorPlayers)) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                debugMonitorPlayers.remove(playerId);
                continue;
            }
            player.sendSystemMessage(Component.literal(getDebugInfo(server, player)), true);
        }
    }

    private static void stabilizeDebugViewer(MinecraftServer server) {
        if (debugSpectatorPlayerId == null) {
            return;
        }

        ServerPlayer player = server.getPlayerList().getPlayer(debugSpectatorPlayerId);
        if (player == null) {
            debugSpectatorPlayerId = null;
            debugViewerAnchorPosition = null;
            return;
        }

        stabilizeDebugViewer(player);
    }

    private static void stabilizeDebugViewer(ServerPlayer player) {
        if (debugViewerAnchorPosition == null) {
            return;
        }

        // Client camera controls can otherwise leak rotation or movement packets
        // into the real player body. Reasserting the original body state means the
        // Hunter receives exactly the same target information it had before the
        // camera was attached.
        player.setPos(
                debugViewerAnchorPosition.x,
                debugViewerAnchorPosition.y,
                debugViewerAnchorPosition.z
        );
        player.setYRot(debugViewerAnchorYaw);
        player.setXRot(debugViewerAnchorPitch);
        player.setYHeadRot(debugViewerAnchorYaw);
        player.setDeltaMovement(Vec3.ZERO);
        player.hurtMarked = true;
    }

    private static void restoreDebugSpectator(MinecraftServer server) {
        UUID spectatorId = debugSpectatorPlayerId;
        debugSpectatorPlayerId = null;

        if (spectatorId != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(spectatorId);
            if (player != null) {
                stabilizeDebugViewer(player);
                player.setCamera(player);
            }
        }

        debugViewerAnchorPosition = null;
        debugViewerAnchorYaw = 0.0F;
        debugViewerAnchorPitch = 0.0F;
    }

    private static void teleportBehindTarget(Husk hunter, ServerPlayer target, double distance) {
        ServerLevel level = target.level();
        Vec3 behind = pointBehind(target, distance);
        BlockPos surface = level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                BlockPos.containing(behind.x, target.getY(), behind.z)
        );

        hunter.teleportTo(level,
                surface.getX() + 0.5,
                surface.getY(),
                surface.getZ() + 0.5,
                java.util.Set.of(),
                hunter.getYRot(),
                hunter.getXRot(),
                false);
    }

    private static Entity findHunter(MinecraftServer server) {
        if (hunterId == null) {
            return null;
        }

        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntityInAnyDimension(hunterId);
            if (entity != null) {
                return entity;
            }
        }

        return null;
    }

    private static void syncHunterState(MinecraftServer server, boolean isActive) {
        HunterStatePayload payload = new HunterStatePayload(isActive);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    private static boolean isTargetBeingViewedThroughCamera() {
        return debugSpectatorPlayerId != null
                && debugSpectatorPlayerId.equals(targetPlayerId);
    }

    private static boolean canUseLiveTarget(Husk hunter, ServerPlayer target) {
        return !isTargetBeingViewedThroughCamera()
                && hunter.hasLineOfSight(target);
    }

    private static void updateHunterMemory(Husk hunter, ServerPlayer target) {
        // Attaching the camera must not turn the camera-owner into live tracking
        // data. While viewing through the Hunter, preserve its last real sighting
        // and continue searching that memory exactly as if the player vanished.
        if (isTargetBeingViewedThroughCamera()) {
            unseenTicks++;
            return;
        }

        if (memoryRecordCooldown > 0) {
            memoryRecordCooldown--;
        }

        if (canUseLiveTarget(hunter, target)) {
            unseenTicks = 0;
            lastKnownPlayerPosition = target.position();
            currentSearchPosition = null;
            if (memoryRecordCooldown <= 0) {
                rememberPlayerPosition(target.position());
                memoryRecordCooldown = 5 * 20;
            }
        } else {
            unseenTicks++;
        }
    }

    private static Vec3 createApproximateClue(Vec3 exactPosition) {
        double angle = RANDOM.nextDouble() * Math.PI * 2.0;
        double radius = 28.0 + RANDOM.nextDouble() * 32.0;
        return exactPosition.add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
    }

    private static void rememberPlayerPosition(Vec3 position) {
        if (position == null) {
            return;
        }
        if (!rememberedPlayerLocations.isEmpty()
                && rememberedPlayerLocations.peekLast().distanceToSqr(position) < 12.0 * 12.0) {
            return;
        }
        rememberedPlayerLocations.addLast(position);
        while (rememberedPlayerLocations.size() > MEMORY_LIMIT) {
            rememberedPlayerLocations.removeFirst();
        }
    }

    private static Vec3 chooseMemorySearchPosition(Husk hunter) {
        if (!(hunter.level() instanceof ServerLevel level)) {
            return null;
        }

        if (currentSearchPosition != null
                && hunter.position().distanceToSqr(currentSearchPosition) > 4.0 * 4.0
                && !hunter.getNavigation().isDone()) {
            return currentSearchPosition;
        }

        Vec3 base = lastKnownPlayerPosition;
        if (!rememberedPlayerLocations.isEmpty() && RANDOM.nextFloat() < 0.35F) {
            int index = RANDOM.nextInt(rememberedPlayerLocations.size());
            int i = 0;
            for (Vec3 remembered : rememberedPlayerLocations) {
                if (i++ == index) {
                    base = remembered;
                    break;
                }
            }
        }

        if (base == null) {
            return null;
        }

        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            double radius = 4.0 + RANDOM.nextDouble() * 18.0;

            int x = (int) Math.floor(base.x + Math.cos(angle) * radius);
            int z = (int) Math.floor(base.z + Math.sin(angle) * radius);

            BlockPos surface = level.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    new BlockPos(x, 0, z)
            );

            Vec3 candidate = new Vec3(
                    surface.getX() + 0.5,
                    surface.getY(),
                    surface.getZ() + 0.5
            );

            var path = hunter.getNavigation().createPath(surface, 0);
            if (path != null && path.canReach()) {
                currentSearchPosition = candidate;
                return candidate;
            }
        }

        currentSearchPosition = null;
        return null;
    }

    private static void respawnMissingHunter(ServerPlayer target) {
        ServerLevel level = target.level();
        Vec3 anchor = lastKnownPlayerPosition != null ? lastKnownPlayerPosition : target.position();
        Vec3 behind = pointBehind(target, phase == Phase.STALKING ? 58.0 : 72.0);
        BlockPos spawnPos = level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                BlockPos.containing(behind.x, anchor.y, behind.z)
        );

        Husk replacement = EntityTypes.HUSK.spawn(level, spawnPos, EntitySpawnReason.COMMAND);
        if (replacement == null) {
            return;
        }

        configureHunter(replacement);
        setBaseAttribute(replacement, Attributes.FOLLOW_RANGE,
                phase == Phase.STALKING ? 1.0 : 256.0);
        hunterId = replacement.getUUID();
        lastHunterPosition = replacement.position();
        stuckTicks = 0;
        currentCoverPosition = null;
        currentSearchPosition = null;
        updateHunterChunkLoading(replacement);

        if (phase == Phase.ATTACKING) {
            setBaseAttribute(replacement, Attributes.FOLLOW_RANGE, 256.0);
            replacement.setTarget(target);
            replacement.setAggressive(true);
        }
    }

    private static void clearState(MinecraftServer server) {
        restoreDebugSpectator(server);
        releaseHunterChunks();
        syncHunterState(server, false);
        active = false;
        targetPlayerId = null;
        hunterId = null;
        phase = Phase.STALKING;
        stalkTicksRemaining = 0;
        ticksActive = 0;
        stuckTicks = 0;
        currentCoverPosition = null;
        coverRefreshCooldown = 0;
        buildCooldown = 0;
        builderReplanCooldown = 0;
        builderStepsRemaining = 0;
        builderDestination = null;
        breakCooldown = 0;
        rememberedPlayerLocations.clear();
        lastKnownPlayerPosition = null;
        currentSearchPosition = null;
        unseenTicks = 0;
        memoryRecordCooldown = 0;
        missingHunterTicks = 0;
        lastHunterPosition = Vec3.ZERO;
    }
}