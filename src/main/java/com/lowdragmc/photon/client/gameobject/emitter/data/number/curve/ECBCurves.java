package com.lowdragmc.photon.client.gameobject.emitter.data.number.curve;

import com.lowdragmc.lowdraglib2.math.curve.ExplicitCubicBezierCurve2;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;
import org.joml.Vector2f;

import javax.annotation.Nonnull;
import java.util.ArrayList;

/**
 * @author KilaBash
 * @date 2023/5/29
 * @implNote ECBCurves
 */
public class ECBCurves extends ArrayList<ExplicitCubicBezierCurve2> implements INBTSerializable<ListTag> {

    public ECBCurves() {
        add(new ExplicitCubicBezierCurve2(new Vector2f(0, 0.5f), new Vector2f(0.1f, 0.5f), new Vector2f(0.9f, 0.5f), new Vector2f(1, 0.5f)));
    }

    public ECBCurves(float... data) {
        for (int i = 0; i < data.length; i+=8) {
            add(new ExplicitCubicBezierCurve2(new Vector2f(data[i], data[i + 1]), new Vector2f(data[i + 2], data[i + 3]), new Vector2f(data[i + 4], data[i + 5]), new Vector2f(data[i + 6], data[i + 7])));
        }
    }

    public float getCurveY(float x) {
        var value = get(0).p0.y;
        var found = x < get(0).p0.x;
        if (!found) {
            for (var curve : this) {
                if (x >= curve.p0.x && x <= curve.p1.x) {
                    value = curve.getPoint((x - curve.p0.x) / (curve.p1.x - curve.p0.x)).y;
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            value = get(size() - 1).p1.y;
        }
        return value;
    }

    @Override
    public ListTag serializeNBT(@Nonnull HolderLookup.Provider provider) {
        var list = new ListTag();
        for (var curve : this) {
            list.add(curve.serializeNBT(provider));
        }
        return list;
    }

    @Override
    public void deserializeNBT(@Nonnull HolderLookup.Provider provider, ListTag list) {
        clear();
        for (Tag tag : list) {
            if (tag instanceof ListTag curve) {
                add(new ExplicitCubicBezierCurve2(curve));
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof ECBCurves curves) {
            if (size() != curves.size()) return false;
            for (int i = 0; i < size(); i++) {
                if (!get(i).serializeNBT(null).equals(curves.get(i).serializeNBT(null))) return false;
            }
            return true;
        }
        return false;
    }

    public ECBCurves copy() {
        var curves = new ECBCurves();
        curves.clear();
        for (var curve : this) {
            curves.add(curve.copy());
        }
        return curves;
    }
}
