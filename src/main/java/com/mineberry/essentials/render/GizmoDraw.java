package com.mineberry.essentials.render;

import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Tiny wrapper around vanilla Gizmos (Minecraft 26.x). Every shape is drawn twice: once normally and once,
 * much fainter, on top of everything so it stays readable through walls (same look as WorldEditCUI).
 * Only call these from inside LevelRenderEvents.BEFORE_GIZMOS.
 */
public final class GizmoDraw {

    private static final int HIDDEN_ALPHA = 0x55;

    private GizmoDraw() {}

    private static int dim(int argb) { return (HIDDEN_ALPHA << 24) | (argb & 0xFFFFFF); }

    public static void box(AABB box, int argb, float width) {
        Gizmos.cuboid(box, GizmoStyle.stroke(argb, width));
        Gizmos.cuboid(box, GizmoStyle.stroke(dim(argb), width)).setAlwaysOnTop();
    }

    public static void line(Vec3 a, Vec3 b, int argb, float width) {
        Gizmos.line(a, b, argb, width);
        Gizmos.line(a, b, dim(argb), width).setAlwaysOnTop();
    }

    /** A closed ring (ellipse) in one of the three axis planes: axis 0 = X, 1 = Y, 2 = Z is the ring's normal. */
    public static void ring(double cx, double cy, double cz, double ra, double rb, int normalAxis, int segments, int argb, float width) {
        Vec3 prev = null;
        for (int i = 0; i <= segments; i++) {
            double t = (Math.PI * 2.0 * i) / segments;
            double a = Math.cos(t) * ra, b = Math.sin(t) * rb;
            Vec3 p = switch (normalAxis) {
                case 0 -> new Vec3(cx, cy + a, cz + b);   // ring in the YZ plane
                case 1 -> new Vec3(cx + a, cy, cz + b);   // ring in the XZ plane (horizontal)
                default -> new Vec3(cx + a, cy + b, cz);  // ring in the XY plane
            };
            if (prev != null) line(prev, p, argb, width);
            prev = p;
        }
    }
}
