package com.gpuspike.mixin;

import com.gpuspike.GpuCubeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hide the hotbar/crosshair while the transparent cube test UI is open. */
@Mixin(Hud.class)
public abstract class HudMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void gpuSpike$hideHud(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
        if (Minecraft.getInstance().gui.screen() instanceof GpuCubeScreen) {
            ci.cancel();
        }
    }
}
