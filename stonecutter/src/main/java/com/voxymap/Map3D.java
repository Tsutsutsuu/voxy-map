package com.voxymap;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Platform-independent 3D map helpers: height-field mesh built from the existing map snapshot and a
 * simple orbit camera. Rendering lives in the screen (each loader has its own draw path).
 */
public final class Map3D {

    private Map3D() {
    }

    /** A height-field mesh: interleaved position (x,y,z) + colour (r,g,b,a) per vertex. */
    public static final class Mesh {
        /** sampling step (map pixels per vertex) of the finest layer, shown in the debug panel */
        public final int step;
        public final float[] vertices;
        public final int[] indices;

        Mesh(int step, float[] vertices, int[] indices) {
            this.step = step;
            this.vertices = vertices;
            this.indices = indices;
        }
    }

    /**
     * Builds a distance-based LOD mesh: several concentric spherical layers around the camera eye,
     * each with its own sampling step. Layers are assigned per triangle, so no cracks have to be
     * welded, and every layer is clipped to the real map bounds.
     */
    public static Mesh buildLod(MapData data, double eyeX, double eyeY, double eyeZ, double verticalScale,
                                int[] stepPx, double[] radiiBlocks, boolean debugColors) {
        // per-layer tints for the LOD debug view: near = red, mid = green, far = blue
        final int[] tint = {0xE02020, 0x20E020, 0x2020E0};
        int mapW = data.getRenderWidthPx();
        int mapH = data.getRenderHeightPx();
        if (mapW <= 0 || mapH <= 0) return null;
        int pb = Math.max(1, data.getPixelBlocks());
        long minWX = data.getRenderMinWX();
        long minWZ = data.getRenderMinWZ();

        java.util.ArrayList<float[]> verts = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> idxList = new java.util.ArrayList<>();
        for (int layer = 0; layer < stepPx.length; layer++) {
            double outerRadius = radiiBlocks[Math.min(layer, radiiBlocks.length - 1)];
            double innerRadius = layer == 0 ? 0.0 : radiiBlocks[Math.min(layer - 1, radiiBlocks.length - 1)];
            // Real stitching: judge the boundary at the coarser grid's cell centres so both layers
            // keep exactly the same coarse cells (identical staircase, no gap, no extension).
            int coarseStep = stepPx[stepPx.length - 1];
            int px0 = (int) Math.floor((eyeX - outerRadius - minWX) / pb);
            int px1 = (int) Math.ceil((eyeX + outerRadius - minWX) / pb);
            int pz0 = (int) Math.floor((eyeZ - outerRadius - minWZ) / pb);
            int pz1 = (int) Math.ceil((eyeZ + outerRadius - minWZ) / pb);
            // every layer snaps to the same (coarsest) origin, so coarse cells line up across layers
            int alignAll = stepPx[stepPx.length - 1];
            px0 = Math.floorDiv(px0, alignAll) * alignAll;
            pz0 = Math.floorDiv(pz0, alignAll) * alignAll;
            px0 = Math.max(0, Math.min(mapW - 1, px0));
            px1 = Math.max(0, Math.min(mapW - 1, px1));
            pz0 = Math.max(0, Math.min(mapH - 1, pz0));
            pz1 = Math.max(0, Math.min(mapH - 1, pz1));
            if (px1 <= px0 || pz1 <= pz0) continue;
            int step = stepPx[Math.min(layer, stepPx.length - 1)];
            int gw = (px1 - px0) / step + 1;
            int gh = (pz1 - pz0) / step + 1;
            if (gw < 2 || gh < 2) continue;
            int baseVertex = verts.size();
            boolean[] empty = new boolean[gw * gh];
            for (int gz = 0; gz < gh; gz++) {
                for (int gx = 0; gx < gw; gx++) {
                    int px = Math.min(mapW - 1, px0 + gx * step);
                    int pz = Math.min(mapH - 1, pz0 + gz * step);
                    int worldX = (int) (minWX + (long) px * pb);
                    int worldZ = (int) (minWZ + (long) pz * pb);
                    boolean e = data.isEmptyAt(worldX, worldZ);
                    int rgb = data.getRgbAtWorld(worldX, worldZ);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    empty[gz * gw + gx] = e;
                    if (debugColors) {
                        int c = tint[Math.min(layer, 2)];
                        r = (c >> 16) & 0xFF;
                        g = (c >> 8) & 0xFF;
                        b = c & 0xFF;
                    }
                    float y;
                    if (e) {
                        y = 0f;
                    } else if (data.isWaterAt(worldX, worldZ)) {
                        y = (float) (data.seaLevel() * verticalScale);
                    } else {
                        y = (float) (data.heightAt(worldX, worldZ) * verticalScale);
                    }
                    // NOTE: layer membership is decided PER TRIANGLE below. Testing it per vertex
                    // dropped every triangle that crossed a voxel border and left a ring-shaped gap.
                    verts.add(new float[]{
                            (float) (minWX + (long) px * pb),
                            y,
                            (float) (minWZ + (long) pz * pb),
                            r / 255f, g / 255f, b / 255f,
                            e ? 0f : 1f
                    });
                }
            }
            long voxelSize = Math.max(1L, (long) coarseStep * pb);
            for (int gz = 0; gz < gh - 1; gz++) {
                for (int gx = 0; gx < gw - 1; gx++) {
                    int a = baseVertex + gz * gw + gx;
                    int b = a + 1;
                    int c = a + gw;
                    int d = c + 1;
                    int[] tri1 = {a, c, b};
                    int[] tri2 = {b, c, d};
                    for (int[] tri : new int[][]{tri1, tri2}) {
                        if (empty[tri[0] - baseVertex] || empty[tri[1] - baseVertex] || empty[tri[2] - baseVertex]) {
                            continue;
                        }
                        // triangle centre -> voxel sphere -> layer membership (exactly one layer)
                        double tx = 0.0;
                        double ty = 0.0;
                        double tz = 0.0;
                        for (int k = 0; k < 3; k++) {
                            float[] vv = verts.get(tri[k]);
                            tx += vv[0];
                            ty += vv[1];
                            tz += vv[2];
                        }
                        tx /= 3.0;
                        ty /= 3.0;
                        tz /= 3.0;
                        long voxelX = Math.floorDiv((long) tx, voxelSize) * voxelSize + voxelSize / 2;
                        long voxelZ = Math.floorDiv((long) tz, voxelSize) * voxelSize + voxelSize / 2;
                        double cddx = voxelX - eyeX;
                        double cddy = ty - eyeY;
                        double cddz = voxelZ - eyeZ;
                        double dist3 = Math.sqrt(cddx * cddx + cddy * cddy + cddz * cddz);
                        double outerLimit = outerRadius;
                        if (layer + 1 < stepPx.length) {
                            // overlap a bit more than half a voxel: the coarse layer then always
                            // covers the fine layer's T-junction edge (0.75 voxel still avoids a
                            // visible double-drawn band)
                            double overlap = Math.max(voxelSize * 0.75, stepPx[layer] * (double) pb * 3.0);
                            outerLimit = outerRadius + overlap;
                        }
                        if (dist3 < innerRadius || dist3 >= outerLimit) {
                            continue;
                        }
                        idxList.add(tri[0]);
                        idxList.add(tri[1]);
                        idxList.add(tri[2]);
                    }
                }
            }
        }
        if (verts.isEmpty() || idxList.isEmpty()) return null;
        int n = verts.size();
        float[] vertexData = new float[n * 7];
        for (int i = 0; i < n; i++) {
            System.arraycopy(verts.get(i), 0, vertexData, i * 7, 7);
        }
        int[] indices = new int[idxList.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = idxList.get(i);
        return new Mesh(stepPx[0], vertexData, indices);
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
