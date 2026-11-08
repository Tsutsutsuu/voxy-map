package com.voxymap.mixin;

import com.voxymap.Gpu3DMap;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the GPU 3D terrain before the GUI batch, so map labels/buttons stay above it. */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void voxymap$gpu3d(CallbackInfo ci) {
        try {
            Gpu3DMap.render();
        } catch (Throwable t) {
            System.out.println("[voxymap] gpu3d render failed: " + t);
            t.printStackTrace();
        }
    }
}
