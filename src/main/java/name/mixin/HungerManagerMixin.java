package name.mixin;

import name.DifficultyManager;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(FoodData.class)
public class HungerManagerMixin {

    @ModifyVariable(
            method = "eat",
            at = @At("HEAD"),
            argsOnly = true
    )
    private int harderchallenge$halveFood(int food) {
        if (DifficultyManager.getStage() >= 5) {
            return Math.max(0, food / 2);
        }
        return food;
    }
}
