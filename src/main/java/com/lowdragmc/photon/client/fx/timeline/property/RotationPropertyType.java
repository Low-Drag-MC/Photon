package com.lowdragmc.photon.client.fx.timeline.property;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.fx.timeline.AnimatedProperty;
import com.lowdragmc.photon.client.fx.timeline.property.RotationAnimatedProperty.InterpMode;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

@OnlyIn(Dist.CLIENT)
public class RotationPropertyType extends TransformPropertyType {
    @LDLRegisterClient(name = "rotation", registry = "photon:animated_property")
    public static final RotationPropertyType INSTANCE = new RotationPropertyType();

    private static final float RAD = (float) Math.toRadians(1);
    private static final float DEG = 57.29577951308232f;

    @Override
    public float[] capture(FXObject target) {
        var e = target.transform().localRotation().getEulerAnglesXYZ(new Vector3f()).mul(DEG);
        return new float[]{e.x, e.y, e.z};
    }

    @Override
    public void apply(FXObject target, float[] values) {
        target.transform().localRotation(new Quaternionf().rotationXYZ(
                values[0] * RAD, values[1] * RAD, values[2] * RAD));
    }

    @Override
    public boolean angular() {
        return true;
    }

    @Override
    public float[] defaultRange(float[] base) {
        return new float[]{-180, 180};
    }

    @Override
    public AnimatedProperty create(FXObject target) {
        var base = capture(target);
        var channels = AnimatedProperty.seedChannels(base);
        var range = defaultRange(base);
        return new RotationAnimatedProperty(this, base.clone(), channels, range[0], range[1]);
    }

    @Override
    public CompoundTag serialize(HolderLookup.Provider provider, AnimatedProperty property) {
        var tag = super.serialize(provider, property);
        if (property instanceof RotationAnimatedProperty rotation) {
            tag.putInt("interp", rotation.interpMode().ordinal());
        }
        return tag;
    }

    @Override
    public AnimatedProperty deserialize(HolderLookup.Provider provider, CompoundTag tag) {
        var base = AnimatedProperty.readFloatsFromTag(tag.getList("base", net.minecraft.nbt.Tag.TAG_FLOAT));
        var channels = AnimatedProperty.readChannels(provider, tag, channelCount());
        var fixedBase = new float[channelCount()];
        System.arraycopy(base, 0, fixedBase, 0, Math.min(base.length, fixedBase.length));
        var rotation = new RotationAnimatedProperty(this, fixedBase, channels,
                tag.getFloat("rangeMin"), tag.getFloat("rangeMax"));
        rotation.interpMode(InterpMode.byOrdinal(tag.getInt("interp")));
        return rotation;
    }

    @Override
    public IConfigurable inspect(AnimatedProperty property, Runnable onChanged) {
        if (!(property instanceof RotationAnimatedProperty rotation)) return null;
        return IConfigurable.create(group -> group.addConfigurator(new SelectorConfigurator<>(
                "photon.gui.editor.timeline.interp_mode",
                rotation::interpMode,
                mode -> { rotation.interpMode(mode); onChanged.run(); },
                rotation.interpMode(), true,
                List.of(InterpMode.values()),
                mode -> Component.translatable(mode.langKey()).getString())));
    }

    @Override
    public float[] sample(AnimatedProperty property, float time) {
        var shortest = property instanceof RotationAnimatedProperty rotation
                && rotation.interpMode() == InterpMode.SHORTEST;
        var values = new float[channelCount()];
        for (int i = 0; i < values.length; i++) {
            var channel = shortest ? unwrap(property.channel(i)) : property.channel(i);
            values[i] = AnimatedProperty.sampleChannel(channel, time);
        }
        return values;
    }

    /** Copy a channel with consecutive keyframe values shifted by ±360 so adjacent keys differ by
     *  ≤180° (handles shift with their endpoints). Interpolating the unwrapped curve takes the
     *  shortest angular path between keyframes. */
    private static ECBCurves unwrap(ECBCurves curve) {
        var copy = curve.copy();
        var segs = copy.getSegments();
        if (segs.size() <= 1) return copy;
        float offset = 0;
        for (var seg : segs) {
            // seg.p0 carries the running offset from the previous keyframe; shift it (and its out-handle)
            seg.p0.y += offset;
            seg.c0.y += offset;
            // choose this segment's end offset so its delta is the shortest wrap of the original delta
            var rawDelta = seg.p1.y - (seg.p0.y - offset);
            var wrapped = rawDelta - 360f * Math.round(rawDelta / 360f);
            var endValue = (seg.p0.y) + wrapped; // p0.y already offset
            var endOffset = endValue - seg.p1.y;
            seg.p1.y += endOffset;
            seg.c1.y += endOffset;
            offset = endOffset;
        }
        return copy;
    }
}
