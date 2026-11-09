package com.voxymap.compat.gpu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;

/**
 * A GL buffer object (VBO or IBO), standing in for 26.2's {@code GpuBuffer}.
 *
 * <p>26.2 hands out immutable per-tile {@code GpuBuffer}s and pairs them with an explicit index
 * buffer in a {@code RenderPass}. On 1.21.1 the index count cannot be supplied separately from a
 * vanilla {@code VertexBuffer} mesh, so the port keeps its own triangle index list and owns the
 * vertex array object: a vertex buffer lazily creates a VAO that remembers its attribute layout
 * ({@link #createVertex}) plus the element buffer paired with it in
 * {@code RenderPass#setIndexBuffer}.</p>
 *
 * <p>Uploads happen on the render thread (the meshing workers only produce direct {@link ByteBuffer}
 * payloads), and {@link #close()} is safe from any thread.</p>
 */
public final class GpuBuffer implements AutoCloseable {
    /** 26.2 usage flag names, kept so the ported call sites read the same. */
    public static final int USAGE_VERTEX = 1;
    public static final int USAGE_INDEX = 2;
    public static final int USAGE_COPY_DST = 4;
    public static final int USAGE_COPY_SRC = 8;
    public static final int USAGE_UNIFORM = 16;

    private final String label;
    private final int id;
    private final int target;
    private final int size;
    private final int usage;
    /** Vertex layout for vertex buffers; {@code null} for index buffers. */
    private final VertexFormat format;
    private int vao;
    private boolean vaoConfigured;
    private boolean closed;

    private GpuBuffer(String label, int usage, int size, VertexFormat format) {
        RenderSystem.assertOnRenderThreadOrInit();
        this.label = label;
        this.usage = usage;
        this.size = size;
        this.format = format;
        this.target = (usage & USAGE_VERTEX) != 0 ? GL15.GL_ARRAY_BUFFER : GL15.GL_ELEMENT_ARRAY_BUFFER;
        this.id = GlStateManager._glGenBuffers();
        GlStateManager._glBindVertexArray(0);
        GlStateManager._glBindBuffer(this.target, this.id);
        GlStateManager._glBufferData(this.target, size, GL15.GL_DYNAMIC_DRAW);
    }

    /** 26.2-compatible factory: {@code GpuBuffer.create(label, usage, size)}. */
    public static GpuBuffer create(String label, int usage, int size) {
        return new GpuBuffer(label, usage, size, null);
    }

    public static GpuBuffer createVertex(String label, int size, VertexFormat format) {
        return new GpuBuffer(label, USAGE_VERTEX | USAGE_COPY_DST, size, format);
    }

    public static GpuBuffer createIndex(String label, int size) {
        return new GpuBuffer(label, USAGE_INDEX | USAGE_COPY_DST, size, null);
    }

    public int id() {
        return id;
    }

    public int size() {
        return size;
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

    /**
     * Uploads {@code data} at {@code offset}. Offset 0 with a full-size payload orphanes the whole
     * store (the only pattern the 3D map uses); partial writes fall back to {@code glBufferSubData}.
     */
    void upload(long offset, ByteBuffer data) {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        GlStateManager._glBindBuffer(target, id);
        if (offset == 0L) {
            GlStateManager._glBufferData(target, data, GL15.GL_DYNAMIC_DRAW);
        } else {
            GL15.glBufferSubData(target, offset, data);
        }
    }

    /**
     * Binds this vertex buffer's VAO and makes sure its attribute pointers + element buffer are set
     * up. The attribute state is recorded once, when the VBO/IBO pair is first drawn.
     */
    void bindForDraw(GpuBuffer indexBuffer) {
        if (vao == 0) {
            vao = GlStateManager._glGenVertexArrays();
        }
        GlStateManager._glBindVertexArray(vao);
        if (!vaoConfigured) {
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, id);
            format.setupBufferState();
            GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer.id);
            vaoConfigured = true;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (RenderSystem.isOnRenderThread()) {
            deleteGl();
        } else {
            RenderSystem.recordRenderCall(this::deleteGl);
        }
    }

    private void deleteGl() {
        int v = vao;
        vao = 0;
        if (v != 0) {
            GlStateManager._glDeleteVertexArrays(v);
        }
        GlStateManager._glDeleteBuffers(id);
    }

    @Override
    public String toString() {
        return "GpuBuffer[" + label + " id=" + id + " bytes=" + size + "]";
    }
}
