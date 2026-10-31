package com.voxymap.mixin;

import com.voxymap.MapScreen;
import com.voxymap.VoxyMapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * While the fullscreen map is open the world behind it is completely covered, yet GameRenderer
 * still draws the whole level every frame. Flipping the {@code renderLevel} flag off for our screen
 * skips that work while the screen itself keeps rendering.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean voxymap$skipWorldBehindMap(boolean renderLevel) {
        if (renderLevel
                && VoxyMapConfig.SKIP_WORLD_RENDER.get()
                && Minecraft.getInstance().gui.screen() instanceof MapScreen) {
            return false;
        }
        return renderLevel;
    }
}
