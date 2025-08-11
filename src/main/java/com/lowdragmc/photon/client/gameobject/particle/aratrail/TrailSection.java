package com.lowdragmc.photon.client.gameobject.particle.aratrail;

import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.List;

public class TrailSection {
    public List<Vector2f> vertices = new ArrayList<>();
    public int snapX = 0;
    public int snapY = 0;

    public int getSegments() {
        return vertices != null ? vertices.size() - 1 : 0;
    }

    public void circlePreset(int segments) {
        vertices.clear();

        for (int j = 0; j <= segments; ++j) {
            float angle = 2 * (float) Math.PI / segments * j;
            Vector2f right = new Vector2f(1, 0);
            Vector2f up = new Vector2f(0, 1);
            Vector2f point = new Vector2f(right).mul((float) Math.cos(angle))
                                              .add(new Vector2f(up).mul((float) Math.sin(angle)));
            vertices.add(point);
        }
    }

    /**
     * Snaps a float value to the nearest multiple of snapInterval.
     */
    public static int snapTo(float val, int snapInterval, int threshold) {
        int intVal = (int) val;
        if (snapInterval <= 0)
            return intVal;
        int under = (int) Math.floor(val / snapInterval) * snapInterval;
        int over = under + snapInterval;
        if (intVal - under < threshold) return under;
        if (over - intVal < threshold) return over;
        return intVal;
    }
}