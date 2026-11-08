package com.voxymap;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Platform-independent 3D map helpers: height-field mesh built from the existing map snapshot and a
 * simple orbit camera. Rendering lives in the screen (each loader has its own draw path).
 */
public final class Map3D {
    public static final int DEFAULT_GRID = 1024;

    private Map3D() {
    }

    /** A height-field mesh: interleaved position (x,y,z) + colour (r,g,b,a) per vertex. */
    public static final class Mesh {
        public final int gridW;
        public final int gridH;
        public final int step;
        public final float[] vertices;
        public final int[] indices;
        public final float minY;
        public final float maxY;

        Mesh(int gridW, int gridH, int step, float[] vertices, int[] indices, float minY, float maxY) {
            this.gridW = gridW;
            this.gridH = gridH;
            this.step = step;
            this.vertices = vertices;
            this.indices = indices;
            this.minY = minY;
            this.maxY = maxY;
        }

        public int vertexCount() {
            return vertices.length / 7;
        }

        public int triangleCount() {
            return indices.length / 3;
        }
    }

    /**
     * Builds the 3D mesh by sampling the existing map snapshot: height from {@link MapData#heightAt},
     * colour from the rendered RGB buffer. Unexplored pixels produce no triangles at all.
     */
    public static Mesh build(MapData data, int gridSize, double verticalScale) {
        int mapW = data.getMapWidth();
        int mapH = data.getMapHeight();
        if (mapW <= 0 || mapH <= 0) return null;
        int grid = Math.max(8, gridSize);
        int step = Math.max(1, (Math.max(mapW, mapH) + grid - 1) / grid);
        int gw = (mapW + step - 1) / step;
        int gh = (mapH + step - 1) / step;
        int n = gw * gh;
        float[] verts = new float[n * 7];
        boolean[] empty = new boolean[n];
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        int pixelBlocks = data.getPixelBlocks();
        long minWX = data.getMinWX();
        long minWZ = data.getMinWZ();
        for (int gz = 0; gz < gh; gz++) {
            for (int gx = 0; gx < gw; gx++) {
                int px = Math.min(mapW - 1, gx * step);
                int pz = Math.min(mapH - 1, gz * step);
                int worldX = (int) (minWX + (long) px * pixelBlocks);
                int worldZ = (int) (minWZ + (long) pz * pixelBlocks);
                boolean e = data.isEmptyAt(worldX, worldZ);
                int i = gz * gw + gx;
                empty[i] = e;
                int rgb = data.getRgbAt(px, pz);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                float y;
                if (e) {
                    y = 0f;
                } else if (data.isWaterAt(worldX, worldZ)) {
                    // water is a flat plane at the world sea level, not the column top
                    y = (float) (data.seaLevel() * verticalScale);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                } else {
                    y = (float) (data.heightAt(worldX, worldZ) * verticalScale);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
                int o = i * 7;
                verts[o] = (float) (minWX + (long) px * pixelBlocks);
                verts[o + 1] = y;
                verts[o + 2] = (float) (minWZ + (long) pz * pixelBlocks);
                verts[o + 3] = r / 255f;
                verts[o + 4] = g / 255f;
                verts[o + 5] = b / 255f;
                verts[o + 6] = e ? 0f : 1f;
            }
        }
        if (minY > maxY) {
            minY = 0f;
            maxY = 0f;
        }
        // triangles: (x,z)-(x,z+1)-(x+1,z) and (x+1,z)-(x,z+1)-(x+1,z+1), skipping holes
        int[] idx = new int[Math.max(6, gw * gh * 6)];
        int count = 0;
        for (int gz = 0; gz < gh - 1; gz++) {
            for (int gx = 0; gx < gw - 1; gx++) {
                int a = gz * gw + gx;
                int b = a + 1;
                int c = a + gw;
                int d = c + 1;
                if (!empty[a] && !empty[b] && !empty[c]) {
                    idx[count++] = a;
                    idx[count++] = c;
                    idx[count++] = b;
                }
                if (!empty[b] && !empty[d] && !empty[c]) {
                    idx[count++] = b;
                    idx[count++] = c;
                    idx[count++] = d;
                }
            }
        }
        if (count == 0) return null;
        int[] indices = new int[count];
        System.arraycopy(idx, 0, indices, 0, count);
        return new Mesh(gw, gh, step, verts, indices, minY, maxY);
    }

    /** Orbit camera around a target point. Pitch is negative when looking down. */
    public static final class Camera {
        public double targetX;
        public double targetY;
        public double targetZ;
        public float yaw = (float) Math.toRadians(45);
        public float pitch = (float) Math.toRadians(-50);
        public float distance = 1500f;

        public void setTarget(double x, double y, double z) {
            targetX = x;
            targetY = y;
            targetZ = z;
        }

        public double eyeX() {
            return targetX + distance * Math.cos(pitch) * Math.sin(yaw);
        }

        public double eyeY() {
            return targetY - distance * Math.sin(pitch);
        }

        public double eyeZ() {
            return targetZ + distance * Math.cos(pitch) * Math.cos(yaw);
        }

        public Matrix4f viewMatrix() {
            return new Matrix4f().lookAt(
                    (float) eyeX(), (float) eyeY(), (float) eyeZ(),
                    (float) targetX, (float) targetY, (float) targetZ,
                    0f, 1f, 0f);
        }

        public Matrix4f projectionMatrix(float aspect, float fovDegrees) {
            float fov = (float) Math.toRadians(fovDegrees);
            return new Matrix4f().perspective(fov, Math.max(0.01f, aspect), 1f, 60000f);
        }

        public Matrix4f viewProjection(float aspect, float fovDegrees) {
            return projectionMatrix(aspect, fovDegrees).mul(viewMatrix());
        }

        /** Projects a world point to screen coordinates; returns null when behind the camera. */
        public Vector3f worldToScreen(double x, double y, double z, Matrix4f viewProj, int screenW, int screenH) {
            Vector4f v = new Vector4f((float) x, (float) y, (float) z, 1f).mul(viewProj);
            if (v.w <= 1e-4f) return null;
            float sx = (v.x / v.w * 0.5f + 0.5f) * screenW;
            float sy = (1f - (v.y / v.w * 0.5f + 0.5f)) * screenH;
            return new Vector3f(sx, sy, v.z / v.w);
        }
    }
}
