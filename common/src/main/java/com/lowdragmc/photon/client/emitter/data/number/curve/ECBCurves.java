package com.lowdragmc.photon.client.emitter.data.number.curve;

import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.lowdragmc.lowdraglib.utils.curve.ExplicitCubicBezierCurve2;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec2;

import java.util.ArrayList;

/**
 * @author KilaBash
 * @date 2023/5/29
 * @implNote ECBCurves
 */
public class ECBCurves extends ArrayList<ExplicitCubicBezierCurve2> implements ITagSerializable<ListTag> {

    public ECBCurves() {
        add(new ExplicitCubicBezierCurve2(new Vec2(0, 0.5f), new Vec2(0.1f, 0.5f), new Vec2(0.9f, 0.5f), new Vec2(1, 0.5f)));
    }

    public ECBCurves(float... data) {
        for (int i = 0; i < data.length; i+=8) {
            add(new ExplicitCubicBezierCurve2(new Vec2(data[i], data[i + 1]), new Vec2(data[i + 2], data[i + 3]), new Vec2(data[i + 4], data[i + 5]), new Vec2(data[i + 6], data[i + 7])));
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
    public ListTag serializeNBT() {
        var list = new ListTag();
        for (var curve : this) {
            list.add(curve.serializeNBT());
        }
        return list;
    }

    @Override
    public void deserializeNBT(ListTag list) {
        clear();
        for (Tag tag : list) {
            if (tag instanceof ListTag curve) {
                add(new ExplicitCubicBezierCurve2(curve));
            }
        }
    }
}
