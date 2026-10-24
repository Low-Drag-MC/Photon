package com.lowdragmc.photon.client.gameobject.collider;

import com.lowdragmc.photon.client.render.PhotonGizmoLines;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import org.joml.Vector3f;
import oshi.util.tuples.Pair;

import java.util.ArrayList;
import java.util.List;

/** Wireframes for colliders and collision debugging, drawn over the scene through the editor's after-world hook. */
public final class CollisionGizmos {
    private static final int SEGMENTS = 24;

    private CollisionGizmos() {
    }

    /** The collider's outline in its own local space. */
    public static List<Pair<Vector3f, Vector3f>> colliderLines(ColliderConfig.Shape shape, Vector3f size, float radius) {
        var lines = new ArrayList<Pair<Vector3f, Vector3f>>();
        switch (shape) {
            case Plane -> {
                // a 2x2 patch with a grid, and the facing direction
                for (int i = -2; i <= 2; i++) {
                    float offset = i * 0.5f;
                    lines.add(new Pair<>(new Vector3f(-1, 0, offset), new Vector3f(1, 0, offset)));
                    lines.add(new Pair<>(new Vector3f(offset, 0, -1), new Vector3f(offset, 0, 1)));
                }
                lines.add(new Pair<>(new Vector3f(), new Vector3f(0, 0.75f, 0)));
                lines.add(new Pair<>(new Vector3f(0, 0.75f, 0), new Vector3f(0.12f, 0.6f, 0)));
                lines.add(new Pair<>(new Vector3f(0, 0.75f, 0), new Vector3f(-0.12f, 0.6f, 0)));
            }
            case Box -> box(lines, size.x / 2, size.y / 2, size.z / 2);
            case Sphere -> {
                circle(lines, radius, 0);
                circle(lines, radius, 1);
                circle(lines, radius, 2);
            }
        }
        return lines;
    }

    public static void box(List<Pair<Vector3f, Vector3f>> lines, float x, float y, float z) {
        Vector3f[] c = {
                new Vector3f(-x, -y, -z), new Vector3f(x, -y, -z), new Vector3f(x, -y, z), new Vector3f(-x, -y, z),
                new Vector3f(-x, y, -z), new Vector3f(x, y, -z), new Vector3f(x, y, z), new Vector3f(-x, y, z)};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (var edge : edges) lines.add(new Pair<>(new Vector3f(c[edge[0]]), new Vector3f(c[edge[1]])));
    }

    private static void circle(List<Pair<Vector3f, Vector3f>> lines, float radius, int axis) {
        for (int i = 0; i < SEGMENTS; i++) {
            lines.add(new Pair<>(point(radius, axis, Mth.TWO_PI * i / SEGMENTS),
                    point(radius, axis, Mth.TWO_PI * (i + 1) / SEGMENTS)));
        }
    }

    private static Vector3f point(float radius, int axis, float angle) {
        float c = radius * Mth.cos(angle), s = radius * Mth.sin(angle);
        return switch (axis) {
            case 0 -> new Vector3f(0, s, c);
            case 1 -> new Vector3f(c, 0, s);
            default -> new Vector3f(c, s, 0);
        };
    }

    public static void draw(PoseStack poseStack, MultiBufferSource bufferSource, List<Pair<Vector3f, Vector3f>> lines,
                            int color, float width) {
        PhotonGizmoLines.draw(poseStack, bufferSource, lines, color, width);
    }
}
