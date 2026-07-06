package com.lowdragmc.photon.client.gameobject.forcefield;

import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import oshi.util.tuples.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * Wireframe guide-line geometry (field-local space) for the force-field influence shapes.
 */
@OnlyIn(Dist.CLIENT)
public final class ForceFieldGizmos {
    private static final int SEGMENTS = 24;

    private ForceFieldGizmos() {
    }

    public static List<Pair<Vector3f, Vector3f>> getGuideLines(ForceFieldConfig.Shape shape, float range) {
        if (range <= 0) {
            return List.of();
        }
        var lines = new ArrayList<Pair<Vector3f, Vector3f>>();
        switch (shape) {
            case Sphere -> {
                circle(lines, range, Axis.X);
                circle(lines, range, Axis.Y);
                circle(lines, range, Axis.Z);
            }
            case Hemisphere -> {
                circle(lines, range, Axis.Y); // equator
                halfCircle(lines, range, Axis.X);
                halfCircle(lines, range, Axis.Z);
            }
            case Cylinder -> {
                // shell of max(len(xz), |y|) == range: radius `range`, height ±range
                circleAt(lines, range, range, Axis.Y);
                circleAt(lines, range, -range, Axis.Y);
                for (int i = 0; i < 4; i++) {
                    float angle = i * Mth.HALF_PI;
                    float x = range * Mth.cos(angle);
                    float z = range * Mth.sin(angle);
                    lines.add(new Pair<>(new Vector3f(x, -range, z), new Vector3f(x, range, z)));
                }
            }
            case Box -> cubeFrame(lines, range);
        }
        return lines;
    }

    private enum Axis {X, Y, Z}

    /** Full circle of {@code radius} around the given axis, centered at the origin. */
    private static void circle(List<Pair<Vector3f, Vector3f>> lines, float radius, Axis axis) {
        circleAt(lines, radius, 0, axis);
    }

    /** Full circle of {@code radius} around the given axis, offset by {@code offset} along it. */
    private static void circleAt(List<Pair<Vector3f, Vector3f>> lines, float radius, float offset, Axis axis) {
        arc(lines, radius, offset, axis, 0, Mth.TWO_PI, SEGMENTS);
    }

    /** Upper half circle (y >= 0) around a horizontal axis. */
    private static void halfCircle(List<Pair<Vector3f, Vector3f>> lines, float radius, Axis axis) {
        arc(lines, radius, 0, axis, 0, Mth.PI, SEGMENTS / 2);
    }

    private static void arc(List<Pair<Vector3f, Vector3f>> lines, float radius, float offset, Axis axis,
                            float from, float to, int segments) {
        for (int i = 0; i < segments; i++) {
            float a1 = from + (to - from) * i / segments;
            float a2 = from + (to - from) * (i + 1) / segments;
            lines.add(new Pair<>(arcPoint(radius, offset, axis, a1), arcPoint(radius, offset, axis, a2)));
        }
    }

    private static Vector3f arcPoint(float radius, float offset, Axis axis, float angle) {
        float c = radius * Mth.cos(angle);
        float s = radius * Mth.sin(angle);
        return switch (axis) {
            // for the X/Z normals, y = sin(angle) so [0, PI] spans the upper half (y >= 0)
            case X -> new Vector3f(offset, s, c);
            case Y -> new Vector3f(c, offset, s);
            case Z -> new Vector3f(c, s, offset);
        };
    }

    private static void cubeFrame(List<Pair<Vector3f, Vector3f>> lines, float halfExtent) {
        float r = halfExtent;
        Vector3f[] corners = {
                new Vector3f(-r, -r, -r), new Vector3f(r, -r, -r), new Vector3f(r, -r, r), new Vector3f(-r, -r, r),
                new Vector3f(-r, r, -r), new Vector3f(r, r, -r), new Vector3f(r, r, r), new Vector3f(-r, r, r)
        };
        int[][] edges = {
                {0, 1}, {1, 2}, {2, 3}, {3, 0}, // bottom
                {4, 5}, {5, 6}, {6, 7}, {7, 4}, // top
                {0, 4}, {1, 5}, {2, 6}, {3, 7}  // verticals
        };
        for (var edge : edges) {
            lines.add(new Pair<>(new Vector3f(corners[edge[0]]), new Vector3f(corners[edge[1]])));
        }
    }
}
