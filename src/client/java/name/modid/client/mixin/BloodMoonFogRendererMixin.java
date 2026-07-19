package name.modid.client.mixin;

import name.client.BloodMoonClientState;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FogRenderer.class)
public abstract class BloodMoonFogRendererMixin {

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void harderchallenge$makeFogCrimson(
            Camera camera,
            int renderDistance,
            DeltaTracker deltaTracker,
            float darkenWorldAmount,
            ClientLevel level,
            CallbackInfoReturnable<FogData> cir
    ) {
        if (!BloodMoonClientState.isActive()) {
            return;
        }

        FogData fog = cir.getReturnValue();
        if (fog == null) {
            return;
        }

        Vector4f original = fog.color;
        float alpha = original == null ? 1.0F : original.w;
        fog.color = new Vector4f(0.34F, 0.018F, 0.025F, alpha);
    }
}
