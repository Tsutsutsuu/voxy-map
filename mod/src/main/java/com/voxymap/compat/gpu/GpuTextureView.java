package com.voxymap.compat.gpu;

/**
 * 1.21.1 stand-in for 26.2's {@code GpuTextureView}. GL has no texture-view objects, so this is a
 * thin handle on the owning {@link GpuTexture}; closing the view does not delete the texture (the
 * texture's own {@link GpuTexture#close()} owns the GL name).
 */
public final class GpuTextureView implements AutoCloseable {
    private final GpuTexture texture;
    private boolean closed;

    GpuTextureView(GpuTexture texture) {
        this.texture = texture;
    }

    public GpuTexture texture() {
        return texture;
    }

    public int width() {
        return texture.width();
    }

    public int height() {
        return texture.height();
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }
}
