package com.voxymap.mixin;

import com.voxymap.MapScreen;
import com.voxymap.RefreshDebug;
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
 * <p>Pausing the world renderer for minutes and then resuming could make the NVIDIA driver
 * fail-fast on 26.1.2 (see {@link #voxymap$keepalive}), so one hidden world frame is let through
 * per second while the map is open. With {@code debugEnabled} on, the first frames after the world
 * render resumes are traced and the available VRAM is logged.</p>
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    private static boolean voxymap$lastWantSkip;
    private static long voxymap$lastWorldFrameNs;
    private static int voxymap$watchFrames;

    private static final long KEEPALIVE_INTERVAL_NS = 1_000_000_000L;

    /**
     * Let one world frame through per second while the map covers the screen.
     *
     * <p>Pausing the world renderer for a long time and then resuming made Voxy request an
     * impossible amount of GPU memory from the NVIDIA driver on 26.1.2 (Voxy 0.2.18); the driver
     * then fail-fasts (nvoglv64, exit 0xc0000409) with no Java crash report. The game was using
     * only ~3.4 GB of an 8 GB card at the time, so this is a bad resume-time allocation, not
     * capacity exhaustion. Voxy 0.2.18 and 0.2.19 have byte-identical geometry allocation code,
     * so 26.2 has the same edge case; one hidden frame per second (the map is opaque) keeps the
     * render ring and upload queues warm on both versions.</p>
     */
    private static final boolean voxymap$keepalive = true;

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean voxymap$skipWorldBehindMap(boolean advanceGameTime) {
        boolean wantSkip = advanceGameTime
                && VoxyMapConfig.SKIP_WORLD_RENDER.get()
                && Minecraft.getInstance().gui.screen() instanceof MapScreen;
        long now = System.nanoTime();
        boolean skip = wantSkip;
        if (wantSkip && voxymap$keepalive) {
            if (voxymap$lastWorldFrameNs == 0L) voxymap$lastWorldFrameNs = now;
            if (now - voxymap$lastWorldFrameNs >= KEEPALIVE_INTERVAL_NS) {
                skip = false;
                voxymap$lastWorldFrameNs = now;
            }
        }
        if (!skip) voxymap$lastWorldFrameNs = now;
        if (wantSkip != voxymap$lastWantSkip) {
            voxymap$lastWantSkip = wantSkip;
            if (VoxyMapConfig.DEBUG_ENABLED.get()) {
                System.out.println("[voxymap] world render skip=" + wantSkip);
                if (!wantSkip) voxymap$watchFrames = 12;
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
                    + " fbo=" + org.lwjgl.opengl.GL11.glGetInteger(0x8CA6)
                    + " vram=" + RefreshDebug.vramInfo();
        } catch (Throwable t) {
            return "gl state query failed: " + t;
        }
    }
}
