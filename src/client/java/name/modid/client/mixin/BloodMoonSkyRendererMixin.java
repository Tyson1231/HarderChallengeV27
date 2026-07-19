package name.modid.client.mixin;

import name.client.BloodMoonClientState;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public abstract class BloodMoonSkyRendererMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void harderchallenge$makeSkyCrimson(
            ClientLevel level,
            float partialTick,
            Camera camera,
            SkyRenderState state,
            CallbackInfo ci
    ) {
        if (!BloodMoonClientState.isActive()) {
            return;
        }

        // This changes Minecraft's actual sky render state rather than drawing over the HUD.
        // ARGB: fully opaque, deep luminous crimson.
        state.skyColor = 0xFF8F0712;
        state.sunriseAndSunsetColor = 0xFFCC1428;
        state.starBrightness = Math.max(state.starBrightness, 0.65F);
        state.rainBrightness = Math.min(state.rainBrightness, 0.35F);
    }
}
