package com.gpuspike.mixin;

import com.gpuspike.GpuCubeRenderer;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draw the cube before the GUI batch, so screen text is rendered on top of it.
 * The world is already skipped by GameRendererMixin while the test screen is open.
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void gpuSpike$beforeGui(CallbackInfo ci) {
        try {
            GpuCubeRenderer.renderOverlay();
        } catch (Throwable t) {
            System.out.println("[gpu-spike] overlay failed: " + t);
            t.printStackTrace();
        }
    }
}
