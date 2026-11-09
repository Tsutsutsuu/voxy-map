package com.voxymap.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;

/**
 * 1.21.1 stand-in for 26.2's {@code Matrix3x2fStack} (returned by the extractor's {@code pose()}).
 *
 * <p>26.2 accumulates GUI transforms in a 2D matrix and bakes that matrix into every extracted
 * element; 1.21.1 draws immediately, so this wrapper pushes the same operations straight onto the
 * vanilla {@link PoseStack}. Only the operations the map screens use are provided.</p>
 */
public final class Pose2D {
    private final PoseStack stack;

    public Pose2D(PoseStack stack) {
        this.stack = stack;
    }

    public void pushMatrix() {
        stack.pushPose();
    }

    public void popMatrix() {
        stack.popPose();
    }

    public void translate(float x, float y) {
        stack.translate(x, y, 0f);
    }

    public void scale(float x, float y) {
        stack.scale(x, y, 1f);
    }

    /** Rotation around the screen Z axis, positive = clockwise on screen (same as 26.2). */
    public void rotate(float radians) {
        stack.mulPose(new Quaternionf(new AxisAngle4f(radians, 0f, 0f, 1f)));
    }
}
