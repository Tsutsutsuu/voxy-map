package com.voxymap.compat.gpu;

/**
 * 1.21.1 stand-in for the 26.2 {@code GpuSampler}.
 *
 * <p>1.21.1 has no sampler objects: min/mag filter and wrap mode live on the texture. The atlas
 * textures are created with NEAREST + clamp-to-edge (see {@link GpuTexture#createColor}), so these
 * instances only carry the intended mode.</p>
 */
public final class GpuSampler {
    public static final GpuSampler CLAMP_NEAREST = new GpuSampler(FilterMode.NEAREST);
    public static final GpuSampler CLAMP_LINEAR = new GpuSampler(FilterMode.LINEAR);

    public final FilterMode filterMode;

    private GpuSampler(FilterMode filterMode) {
        this.filterMode = filterMode;
    }

    @Override
    public String toString() {
        return "GpuSampler[" + filterMode + ", clamp]";
    }
}
