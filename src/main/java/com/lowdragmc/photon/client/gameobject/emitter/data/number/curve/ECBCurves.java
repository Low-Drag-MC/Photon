package com.lowdragmc.photon.client.gameobject.emitter.data.number.curve;

import com.lowdragmc.lowdraglib2.math.curve.ExplicitCubicBezierCurve2;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;
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
public class ECBCurves implements INBTSerializable<ListTag> {
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
        var value = segments.getFirst().p0.y;
        var found = x < segments.getFirst().p0.x;
        if (!found) {
            for (var curve : segments) {
                if (x >= curve.p0.x && x <= curve.p1.x) {
                    value = curve.getPoint((x - curve.p0.x) / (curve.p1.x - curve.p0.x)).y;
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            value = segments.getLast().p1.y;
        }
        return value;
    }

    @Override
    public ListTag serializeNBT(@Nonnull HolderLookup.Provider provider) {
        var list = new ListTag();
        for (var curve : segments) {
            list.add(curve.serializeNBT(provider));
        }
        return list;
    }

    @Override
    public void deserializeNBT(@Nonnull HolderLookup.Provider provider, ListTag list) {
        segments.clear();
        for (Tag tag : list) {
            if (tag instanceof ListTag curve) {
                segments.add(new ExplicitCubicBezierCurve2(curve));
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
