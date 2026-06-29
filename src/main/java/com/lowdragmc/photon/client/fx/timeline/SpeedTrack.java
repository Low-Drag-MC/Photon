package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.photon.client.fx.timeline.property.SpeedPropertyType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Speed track. Like an {@link AnimationTrack} it binds one target and keyframes a curve, but the single
 * (auto-created, non-removable) {@link SpeedPropertyType} channel drives the target's playback speed
 * ({@code selfTimeScale}) rather than its transform. The {@link TimelinePlayer} samples it each tick and
 * applies it to the bound subtree (and excludes it from the transform animation pass). Reusing
 * {@code AnimationTrack} gives the curve serialization + editor for free.
 */
@OnlyIn(Dist.CLIENT)
public class SpeedTrack extends AnimationTrack {

    public SpeedTrack() {
        var base = new float[]{1f};
        properties().add(new AnimatedProperty(SpeedPropertyType.INSTANCE, base,
                AnimatedProperty.seedChannels(base), 0f, 2f));
    }

    @Override
    protected void copyExtra(Track target) {
        // the ctor already seeded one property on `target`; clear it so the copy isn't duplicated
        if (target instanceof SpeedTrack speedTrack) {
            speedTrack.properties().clear();
        }
        super.copyExtra(target);
    }

    /** The single speed property (auto-created), or {@code null} if somehow absent. */
    public AnimatedProperty speedProperty() {
        return properties().isEmpty() ? null : properties().getFirst();
    }

    /** Sample the playback speed at {@code time} (ticks), clamped {@code >= 0}; 1 when no curve. */
    public float sampleSpeed(double time) {
        var property = speedProperty();
        if (property == null) return 1f;
        return Math.max(0, property.sample(time)[0]);
    }
}
