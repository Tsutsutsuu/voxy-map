package com.voxymap.compat.gpu;

import java.nio.ByteBuffer;

/**
 * 1.21.1 stand-in for 26.2's {@code CommandEncoder}.
 *
 * <p>There is no deferred command buffer on 1.21.1, so every write executes immediately on the
 * render thread and {@link #submit()} is a no-op. The type still exists so the ported call sites
 * keep the 26.2 shape ("collect one encoder per batch, submit once").</p>
 */
public final class CommandEncoder {
    private CommandEncoder() {
    }

    public static CommandEncoder create() {
        return new CommandEncoder();
    }

    /** Uploads the whole slice immediately ({@code offset}/{@code size} are informational). */
    public void writeToBuffer(GpuBufferSlice slice, ByteBuffer data) {
        slice.buffer().upload(slice.offset(), data);
    }

    /**
     * Sub-uploads an RGBA8 image (tightly packed, {@code width} x {@code height}) into a texture at
     * {@code (x, y)}. This replaces 26.2's {@code writeToTexture(...)} atlas cell update.
     */
    public void writeToTexture(GpuTexture texture, ByteBuffer data, int x, int y, int width, int height) {
        texture.uploadRgba(data, x, y, width, height);
    }

    /** No-op on 1.21.1: GL commands are already executed. */
    public void submit() {
    }
}
