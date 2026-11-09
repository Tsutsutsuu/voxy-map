package com.voxymap.compat.gpu;

/**
 * 1.21.1 stand-in for the 26.2 {@code com.mojang.blaze3d.textures.FilterMode}.
 *
 * <p>26.2 passes a filter mode to a {@code GpuSampler}; in 1.21.1 the filter is per-texture GL state
 * that is fixed when the texture is created (see {@link GpuTexture}), so a sampler only remembers
 * the mode for source parity. The GPU 3D atlas is always NEAREST + clamp-to-edge, exactly like the
 * 26.2 pass.</p>
 */
public enum FilterMode {
    NEAREST,
    LINEAR
}
