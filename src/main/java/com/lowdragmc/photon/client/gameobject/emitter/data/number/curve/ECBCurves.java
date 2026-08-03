package com.lowdragmc.photon.client.gameobject.emitter.data.number.curve;

import com.lowdragmc.lowdraglib2.math.curve.ExplicitCubicBezierCurve2;
import com.mojang.serialization.Codec;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.joml.Vector2f;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * @author KilaBash
 * @date 2023/5/29
 * @implNote ECBCurves
 */
@EqualsAndHashCode
public class ECBCurves {
    /** Same layout as the legacy ListTag: one 8-float list per segment (see serialization note below). */
    public static final Codec<ECBCurves> CODEC =
            Codec.FLOAT.listOf().listOf().xmap(
                    lists -> {
                        var curves = new ECBCurves();
                        curves.segments.clear();
                        for (var points : lists) {
                            if (points.size() >= 8) {
                                curves.segments.add(new ExplicitCubicBezierCurve2(
                                        new Vector2f(points.get(0), points.get(1)),
                                        new Vector2f(points.get(2), points.get(3)),
                                        new Vector2f(points.get(4), points.get(5)),
                                        new Vector2f(points.get(6), points.get(7))));
                            }
                        }
                        return curves;
                    },
                    curves -> curves.segments.stream()
                            .map(curve -> List.of(
                                    curve.p0.x(), curve.p0.y(), curve.c0.x(), curve.c0.y(),
                                    curve.c1.x(), curve.c1.y(), curve.p1.x(), curve.p1.y()))
                            .toList());

    @Getter
    private final List<ExplicitCubicBezierCurve2> segments = new ArrayList<>();

    public ECBCurves() {
        segments.add(new ExplicitCubicBezierCurve2(new Vector2f(0, 0.5f), new Vector2f(0.1f, 0.5f), new Vector2f(0.9f, 0.5f), new Vector2f(1, 0.5f)));
    }

    public ECBCurves(float... data) {
        for (int i = 0; i < data.length; i+=8) {
            segments.add(new ExplicitCubicBezierCurve2(new Vector2f(data[i], data[i + 1]), new Vector2f(data[i + 2], data[i + 3]), new Vector2f(data[i + 4], data[i + 5]), new Vector2f(data[i + 6], data[i + 7])));
        }
    }

    public float getCurveY(float x) {
        var value = segments.getFirst().p0.y();
        var found = x < segments.getFirst().p0.x();
        if (!found) {
            for (var curve : segments) {
                if (x >= curve.p0.x() && x <= curve.p1.x()) {
                    var dx = curve.p1.x() - curve.p0.x();
                    // zero-width segment (vertical jump): step to the later point instead of dividing by 0
                    value = dx <= 0 ? curve.p1.y() : curve.getPoint((x - curve.p0.x()) / dx).y;
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            value = segments.getLast().p1.y();
        }
        return value;
    }

    /*
     * Serialization note (26.1): the LDLib2 curve moved to ValueIOSerializable with its own layout,
     * so the legacy 8-float ListTag layout [p0.x, p0.y, c0.x, c0.y, c1.x, c1.y, p1.x, p1.y] is
     * written/read here directly to keep .fx files byte-identical.
     */
    public ListTag serializeNBT(@Nonnull HolderLookup.Provider provider) {
        var list = new ListTag();
        for (var curve : segments) {
            var points = new ListTag();
            addPoint(points, curve.p0.x(), curve.p0.y());
            addPoint(points, curve.c0.x(), curve.c0.y());
            addPoint(points, curve.c1.x(), curve.c1.y());
            addPoint(points, curve.p1.x(), curve.p1.y());
            list.add(points);
        }
        return list;
    }

    private static void addPoint(ListTag points, float x, float y) {
        points.add(FloatTag.valueOf(x));
        points.add(FloatTag.valueOf(y));
    }

    public void deserializeNBT(@Nonnull HolderLookup.Provider provider, ListTag list) {
        segments.clear();
        for (Tag tag : list) {
            if (tag instanceof ListTag curve && curve.size() >= 8) {
                segments.add(new ExplicitCubicBezierCurve2(
                        new Vector2f(curve.getFloatOr(0, 0.0F), curve.getFloatOr(1, 0.0F)),
                        new Vector2f(curve.getFloatOr(2, 0.0F), curve.getFloatOr(3, 0.0F)),
                        new Vector2f(curve.getFloatOr(4, 0.0F), curve.getFloatOr(5, 0.0F)),
                        new Vector2f(curve.getFloatOr(6, 0.0F), curve.getFloatOr(7, 0.0F))));
            }
        }
    }

    public ECBCurves copy() {
        var curves = new ECBCurves();
        curves.segments.clear();
        for (var segment : this.segments) {
            curves.segments.add(segment.copy());
        }
        return curves;
    }
}
