package com.voxymap.mixin;

import com.voxymap.Gpu3DMap;
import com.voxymap.Map3D;
import com.voxymap.MapData;
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
 * <p>With {@code debugEnabled} on, the first frames after the world render resumes are traced: the
 * 3D view leaves a custom render pass and custom GL state behind, and this trace is the cheapest way
 * to see whether a driver-level failure happens on the resume path.</p>
 *
 * <p>1.21.1 note: the boolean parameter of {@code render} is {@code renderLevel} (26.2 renamed the
 * semantics to {@code advanceGameTime}), and the current screen lives in {@code Minecraft.screen}
 * (26.2 moved it to {@code Minecraft.gui.screen()}).</p>
 *
 * <p><b>3D pass call site.</b> The GPU terrain is drawn here, right after the engine's world-frame
 * clear and before the GUI phase builds its immediate-mode batches (26.2 does the same from
 * {@code GuiRenderer.render} HEAD). Rendering it from {@code MapScreen.render} instead would splice
 * per-tile VAO/texture/program switches into the middle of vanilla's GUI batching, which makes
 * third-party GUI batching mods (immediate mode caches, VAO caches) render garbage text afterwards.
 * {@link Gpu3DMap#beginFrame()} resets the per-frame dedupe flag so the legacy
 * {@code MapScreen.render} call site is a no-op.</p>
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
     * <p>Pausing the world renderer for a long time and resuming made Voxy/BLAZE3D ask the NVIDIA
     * driver for an impossible amount of GPU memory on the Fabric 26.1.2 line (Voxy 0.2.18); the
     * driver then fail-fasts (nvoglv64, exit 0xc0000409) with no Java crash report. The game used
     * only ~3.4 GB of an 8 GB card, so this is a bad resume-time allocation, not capacity
     * exhaustion. Voxy 0.2.18 and 0.2.19 have byte-identical geometry allocation code, so the
     * same edge case can hit any version/loader; one hidden frame per second keeps the render
     * ring and upload queues warm.</p>
     */
    private static final boolean voxymap$keepalive = true;

    /** Runs after the first {@code RenderSystem.clear} of the frame (the world-frame clear) and
     *  before the GUI ortho projection is set up, i.e. before any GUI batch is built.
     *  {@code require = 0}: if another mod ever changes this call site, the port must degrade to the
     *  {@code MapScreen.render} call site (dedupe makes that safe) instead of crashing the game. */
    @Inject(method = "render", require = 0,
            at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.AFTER,
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
    private void voxymap$renderGpu3dBeforeGui(DeltaTracker deltaTracker, boolean renderLevel,
                                              CallbackInfo ci) {
        Gpu3DMap.beginFrame();
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MapScreen screen && screen.isGpu3dView()) {
            Map3D.Camera cam = screen.gpu3dCamera();
            if (cam != null) {
                // Build/upload first (26.2 also ticks from the extract phase, before the draw), then
                // draw. Both happen before any of this frame's GUI batches exist.
                Gpu3DMap.tick(MapData.INSTANCE, cam);
                Gpu3DMap.render();
            }
        }
    }

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean voxymap$skipWorldBehindMap(boolean renderLevel) {
        boolean wantSkip = renderLevel
                && VoxyMapConfig.SKIP_WORLD_RENDER.get()
                && Minecraft.getInstance().screen instanceof MapScreen;
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
        return skip ? false : renderLevel;
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void voxymap$frameBegin(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        if (voxymap$watchFrames > 0) {
            if (voxymap$watchFrames == 12) System.out.println("[voxymap] resume: " + voxymap$glState());
            System.out.println("[voxymap] frame begin");
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void voxymap$frameEnd(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        Gpu3DMap.endFrame();
        if (voxymap$watchFrames > 0) {
            System.out.println("[voxymap] frame end");
            voxymap$watchFrames--;
        }
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
