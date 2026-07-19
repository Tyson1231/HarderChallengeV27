package name.modid.client.mixin;

import name.client.BloodMoonClientState;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.MobCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class BloodMoonLivingEntityRendererMixin {

    @Inject(method = "getModelTint", at = @At("RETURN"), cancellable = true)
    private void harderchallenge$tintHostileBodyRed(
            LivingEntityRenderState state,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (!BloodMoonClientState.isActive()
                || state.entityType == null
                || state.entityType.getCategory() != MobCategory.MONSTER) {
            return;
        }

        // Model tint multiplies the mob's actual texture color, so the complete body
        // becomes crimson while its texture detail remains visible.
        cir.setReturnValue(0xFFFF3038);
    }
}
