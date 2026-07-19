package name.mixin;

import name.DifficultyManager;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * UNVERIFIED - this is the one category of change I cannot confirm without your
 * actual project open. "Creeper" is the correct Mojang class name (was
 * CreeperEntity in Yarn), but the exact explosion method name/signature needs
 * checking directly:
 *
 *   1. In IntelliJ, Ctrl+Click (or Cmd+Click) on "Creeper" in the import above
 *      to jump to its decompiled source.
 *   2. Find the method that builds an explosion power and calls something like
 *      level.explode(...).
 *   3. Update the "method" string below to match its real name, and confirm
 *      the local variable holding the power is still the first one created
 *      (ordinal = 0) - if not, adjust that too.
 *
 * Alternatively, mcsrc.dev lets you browse 26.2's decompiled source in a
 * browser without opening IntelliJ at all - search "Creeper" there directly.
 */
@Mixin(Creeper.class)
public class CreeperExplosionMixin {

    @ModifyVariable(method = "explodeCreeper", at = @At("STORE"), ordinal = 0)
    private float harderchallenge$biggerExplosion(float power) {
        if (DifficultyManager.getStage() >= 4) {
            return power * 1.75F;
        }
        return power;
    }
}
