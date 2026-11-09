package com.voxymap.mixin;

import com.voxymap.MapScreen;
import com.voxymap.VoxyMapConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the fullscreen map is open the world behind it is completely covered, yet GameRenderer
 * still draws the whole level every frame. Flipping the level-render flag off for our screen skips
 * that work while the screen itself keeps rendering.
 *
 * <p>With {@code debugEnabled} on, the first frames after the world render resumes are traced. This
 * is the only known place where the Fabric 26.1.2 + Voxy 0.2.18 combination can hit an
 * intermittent driver fail-fast; the trace pins down the stage if it ever happens again.</p>
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    private static boolean voxymap$lastSkip;
    private static int voxymap$watchFrames;

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean voxymap$skipWorldBehindMap(boolean advanceGameTime) {
        boolean skip = advanceGameTime
                && VoxyMapConfig.SKIP_WORLD_RENDER.get()
                && Minecraft.getInstance().gui.screen() instanceof MapScreen;
        if (skip != voxymap$lastSkip) {
            voxymap$lastSkip = skip;
            if (VoxyMapConfig.DEBUG_ENABLED.get()) {
                System.out.println("[voxymap] world render skip=" + skip);
                if (!skip) voxymap$watchFrames = 12;
            }
        }
        return skip ? false : advanceGameTime;
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void voxymap$frameBegin(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        if (voxymap$watchFrames > 0) {
            if (voxymap$watchFrames == 12) System.out.println("[voxymap] resume: " + voxymap$glState());
            System.out.println("[voxymap] frame begin");
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void voxymap$frameEnd(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        if (voxymap$watchFrames > 0) {
            System.out.println("[voxymap] frame end");
            voxymap$watchFrames--;
        }
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void voxymap$levelBegin(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (voxymap$watchFrames > 0) System.out.println("[voxymap] world render begin");
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void voxymap$levelEnd(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (voxymap$watchFrames > 0) System.out.println("[voxymap] world render end");
    }

    private static String voxymap$glState() {
        try {
            return "gl err=0x" + Integer.toHexString(org.lwjgl.opengl.GL11.glGetError())
                    + " depthTest=" + org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST)
                    + " depthFunc=0x" + Integer.toHexString(org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_DEPTH_FUNC))
                    + " cull=" + org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_CULL_FACE)
                    + " blend=" + org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND)
                    + " activeTex=" + org.lwjgl.opengl.GL11.glGetInteger(0x84E0)
                    + " program=" + org.lwjgl.opengl.GL11.glGetInteger(0x8B8D)
                    + " vao=" + org.lwjgl.opengl.GL11.glGetInteger(0x85B5)
                    + " fbo=" + org.lwjgl.opengl.GL11.glGetInteger(0x8CA6);
        } catch (Throwable t) {
            return "gl state query failed: " + t;
        }
    }
}
