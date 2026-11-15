package com.voxymap;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Platform-independent 3D map helpers: the orbit camera used by the GPU 3D view.
 * Rendering lives in the screen / Gpu3DMap (each loader has its own draw path).
 */
public final class Map3D {

    private Map3D() {
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
