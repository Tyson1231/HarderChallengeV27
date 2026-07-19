package name;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Native mutant variants for Harder Challenge. These do not require another mod:
 * ordinary hostile mobs have a stage-based chance to evolve once when they spawn.
 * Mutants are larger, named, visibly particle-marked and receive type-specific stats.
 */
public final class EvolutionMutations {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final Set<UUID> CHECKED_ENTITIES = new HashSet<>();
    private static final Set<UUID> MUTANT_ENTITIES = new HashSet<>();

    private EvolutionMutations() {
    }

    public static void tick(MinecraftServer server, int stage, long ticks) {
        if (stage < 3 || ticks % 20 != 0) return;

        for (ServerLevel level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (!(entity instanceof LivingEntity living) || !(entity instanceof Monster)) continue;

                UUID entityId = living.getUUID();
                if (CHECKED_ENTITIES.add(entityId)) {
                    tryMutate(living, stage);
                }

                if (MUTANT_ENTITIES.contains(entityId) && ticks % 10 == 0) {
                    level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                            living.getX(), living.getY() + living.getBbHeight() * 0.6, living.getZ(),
                            2, living.getBbWidth() * 0.35, living.getBbHeight() * 0.25,
                            living.getBbWidth() * 0.35, 0.015);
                }
            }
        }
    }

    private static void tryMutate(LivingEntity mob, int stage) {
        MutantKind kind = kindFor(mob, stage);
        if (kind == null) return;

        int chance = Math.min(24, kind.baseChance + Math.max(0, stage - kind.unlockStage) * 2);
        if (RANDOM.nextInt(100) >= chance) return;

        MUTANT_ENTITIES.add(mob.getUUID());
        mob.setCustomName(Component.literal(kind.displayName));
        mob.setCustomNameVisible(true);

        addMultiplier(mob, Attributes.SCALE, kind.id("scale"), kind.scaleBonus);
        addMultiplier(mob, Attributes.MAX_HEALTH, kind.id("health"), kind.healthBonus);
        addMultiplier(mob, Attributes.ATTACK_DAMAGE, kind.id("damage"), kind.damageBonus);
        addMultiplier(mob, Attributes.MOVEMENT_SPEED, kind.id("speed"), kind.speedBonus);
        addMultiplier(mob, Attributes.KNOCKBACK_RESISTANCE, kind.id("knockback"), kind.knockbackBonus);
        mob.setHealth(mob.getMaxHealth());
    }

    private static MutantKind kindFor(LivingEntity mob, int stage) {
        if ((mob.getType() == EntityTypes.ZOMBIE || mob.getType() == EntityTypes.HUSK
                || mob.getType() == EntityTypes.DROWNED) && stage >= 3) {
            return MutantKind.ZOMBIE;
        }
        if (mob.getType() == EntityTypes.SKELETON && stage >= 5) {
            return MutantKind.SKELETON;
        }
        if (mob.getType() == EntityTypes.CREEPER && stage >= 7) {
            return MutantKind.CREEPER;
        }
        if (mob.getType() == EntityTypes.SPIDER && stage >= 9) {
            return MutantKind.SPIDER;
        }
        return null;
    }

    private static void addMultiplier(LivingEntity mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                      Identifier id, double amount) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance == null || instance.getModifier(id) != null) return;
        instance.addPermanentModifier(new AttributeModifier(
                id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    private enum MutantKind {
        ZOMBIE("Mutant Zombie", "mutant_zombie", 3, 12, 0.55, 1.50, 0.65, 0.10, 0.55),
        SKELETON("Mutant Skeleton", "mutant_skeleton", 5, 10, 0.35, 1.15, 0.30, 0.18, 0.35),
        CREEPER("Mutant Creeper", "mutant_creeper", 7, 8, 0.45, 1.35, 0.20, 0.24, 0.50),
        SPIDER("Mutant Spider", "mutant_spider", 9, 10, 0.40, 1.20, 0.45, 0.30, 0.40);

        final String displayName;
        final String path;
        final int unlockStage;
        final int baseChance;
        final double scaleBonus;
        final double healthBonus;
        final double damageBonus;
        final double speedBonus;
        final double knockbackBonus;

        MutantKind(String displayName, String path, int unlockStage, int baseChance,
                   double scaleBonus, double healthBonus, double damageBonus,
                   double speedBonus, double knockbackBonus) {
            this.displayName = displayName;
            this.path = path;
            this.unlockStage = unlockStage;
            this.baseChance = baseChance;
            this.scaleBonus = scaleBonus;
            this.healthBonus = healthBonus;
            this.damageBonus = damageBonus;
            this.speedBonus = speedBonus;
            this.knockbackBonus = knockbackBonus;
        }

        Identifier id(String stat) {
            return Identifier.fromNamespaceAndPath(HarderChallenge.MOD_ID, path + "_" + stat);
        }
    }
}