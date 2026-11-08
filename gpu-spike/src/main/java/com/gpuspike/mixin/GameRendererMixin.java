package com.gpuspike.mixin;

import com.gpuspike.GpuCubeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Same world-skip mechanism as voxy-map while the fullscreen test UI is open. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean gpuSpike$skipWorld(boolean renderLevel) {
        return renderLevel && !(Minecraft.getInstance().gui.screen() instanceof GpuCubeScreen);
    }
}
