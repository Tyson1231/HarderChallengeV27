package name.mixin;

import name.DifficultyManager;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * UNVERIFIED. "RangedBowAttackGoal" is my best-informed guess for the Mojang
 * name of what was BowAttackGoal in Yarn. Confirm via autocomplete/mcsrc.dev
 * the same way as the other two mixins in this package.
 */
@Mixin(RangedBowAttackGoal.class)
public class SkeletonShootIntervalMixin {

    @ModifyVariable(method = "tick", at = @At("STORE"))
    private int harderchallenge$fasterShots(int cooldown) {
        if (DifficultyManager.getStage() >= 7) {
            return cooldown / 2;
        }
        return cooldown;
    }
}
