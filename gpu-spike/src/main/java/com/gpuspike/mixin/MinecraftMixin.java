package com.gpuspike.mixin;

import com.gpuspike.GpuCubeRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the test cube after the complete GUI pass, so it is in front of the screen. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "renderFrame", at = @At("RETURN"))
    private void gpuSpike$afterFrame(boolean renderLevel, CallbackInfo ci) {
        GpuCubeRenderer.renderOverlay();
    }
}
