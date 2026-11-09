package com.voxymap.compat.gpu;

/**
 * 1.21.1 stand-in for 26.2's {@code GpuBufferSlice}: a buffer plus a byte range. The 3D map uses it
 * to hand whole buffers to a {@link CommandEncoder}. Record so the source stays a one-liner.
 */
public record GpuBufferSlice(GpuBuffer buffer, long offset, long size) {
}
