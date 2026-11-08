package com.voxymap.mixin;

import com.voxymap.MapScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hide HUD/crosshair while the GPU 3D map owns the screen. */
@Mixin(Hud.class)
public abstract class HudMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void voxymap$hideHudIn3d(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
        if (Minecraft.getInstance().gui.screen() instanceof MapScreen map && map.isGpu3dView()) {
            ci.cancel();
        }
    }
}
