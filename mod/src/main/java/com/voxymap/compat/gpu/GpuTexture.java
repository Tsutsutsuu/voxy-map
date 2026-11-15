package com.voxymap.compat.gpu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * A plain GL texture, standing in for 26.2's {@code GpuTexture(GpuTextureView)} pair.
 *
 * <p>The GPU 3D atlas is an ordinary mutable RGBA8 2D texture (one sheet per 1024x1024 page). 1.21.1
 * has no immutable storage / sampler objects, so the filter (NEAREST) and wrap mode (clamp-to-edge)
 * are set here once and every sub-upload keeps them. There are no mipmaps, same as the 26.2 pass.</p>
 *
 * <p>Creation and upload must happen on the render thread. {@link #close()} may be reached from a
 * meshing worker (atlas slot release on a failed build), so deletion is bounced onto the render
 * thread through {@code RenderSystem.recordRenderCall}.</p>
 */
public final class GpuTexture implements AutoCloseable {
    /** 26.2 usage flag; kept so call sites read the same. */
    public static final int USAGE_TEXTURE_BINDING = 1;
    /** 26.2 usage flag; kept so call sites read the same. */
    public static final int USAGE_COPY_DST = 2;
    /** 26.2 usage flag; kept so call sites read the same. */
    public static final int USAGE_COPY_SRC = 4;

    private final String label;
    private final int id;
    private final int width;
    private final int height;
    private final int usage;
    /** False for textures owned by someone else (the arrow PNG lives in vanilla's TextureManager). */
    private final boolean owned;
    private boolean closed;

    private GpuTexture(String label, int id, int width, int height, int usage, boolean owned) {
        this.label = label;
        this.id = id;
        this.width = width;
        this.height = height;
        this.usage = usage;
        this.owned = owned;
    }

    /**
     * Creates + allocates an RGBA8 texture with NEAREST filtering and clamp-to-edge wrapping (the
     * exact sampling the 26.2 GPU pass used for the atlas pages).
     */
    public static GpuTexture createColor(String label, int usage, int width, int height) {
        RenderSystem.assertOnRenderThread();
        int id = GlStateManager._genTexture();
        GlStateManager._bindTexture(id);
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (IntBuffer) null);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        // Defensive: with NEAREST minification the mip chain must never be sampled, even if some
        // other code passes this texture through a mipmapped render type.
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GlStateManager._bindTexture(0);
        return new GpuTexture(label, id, width, height, usage, true);
    }

    /**
     * Wraps a GL texture owned by someone else so the pass can bind it with the 26.2 call shape.
     * {@link #close()} never deletes the id - 1.21.1 keeps the arrow artwork in
     * {@code TextureManager} and deleting it here would blank every later 2D arrow blit.
     */
    public static GpuTexture wrapExisting(String label, int glId, int width, int height) {
        return new GpuTexture(label, glId, width, height, USAGE_TEXTURE_BINDING, false);
    }

    public GpuTextureView createView() {
        return new GpuTextureView(this);
    }

    public int id() {
        return id;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int usage() {
        return usage;
    }

    public String label() {
        return label;
    }

    public boolean isClosed() {
        return closed;
    }

    /** RGBA8 sub-upload of a tightly packed {@code width * height} region. Render thread only. */
    void uploadRgba(ByteBuffer data, int x, int y, int w, int h) {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        // GlStateManager.upload() (used by the 2D tile textures) leaves UNPACK_ROW_LENGTH/SKIP_* and
        // the alignment set for its own image, so pin them to "tightly packed RGBA rows" first.
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GlStateManager._bindTexture(id);
        // 1.21.1's GlStateManager only exposes the pointer variant; our staging buffer is a direct
        // buffer whose position is already 0.
        GlStateManager._texSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, w, h,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(data));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (!owned) return;      // never delete a texture we do not own
        if (RenderSystem.isOnRenderThread()) {
            GlStateManager._deleteTexture(id);
        } else {
            RenderSystem.recordRenderCall(() -> GlStateManager._deleteTexture(id));
        }
    }

    @Override
    public String toString() {
        return "GpuTexture[" + label + " " + width + "x" + height + " id=" + id + "]";
    }
}
