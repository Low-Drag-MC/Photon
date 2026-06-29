package com.lowdragmc.photon.gui.editor.view.timeline;

import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import com.lowdragmc.photon.client.fx.timeline.AnimatedProperty;
import com.lowdragmc.photon.client.fx.timeline.AnimationTrack;
import com.lowdragmc.photon.client.fx.timeline.SpeedTrack;
import com.lowdragmc.photon.client.fx.timeline.Track;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector2f;

import javax.annotation.Nullable;
import java.util.ArrayList;

/**
 * Editor for {@code speed} tracks. Reuses the whole {@link AnimationTrackEditor} curve UI, but the track
 * carries a single locked {@code speed} property (no add/remove), allows binding root (global slow-mo),
 * and has no record toggle. The curve drives the target's playback speed; see {@code SpeedTrack}.
 */
@OnlyIn(Dist.CLIENT)
public class SpeedTrackEditor extends AnimationTrackEditor {

    @Override
    public ColorPattern chipColor() {
        return ColorPattern.LIGHT_BLUE;
    }

    @Override
    protected boolean allowRootTarget() {
        return true; // a speed track on root scales the whole effect
    }

    @Override
    protected boolean canEditProperties() {
        return false; // the single speed property is auto-created and fixed
    }

    @Override
    @Nullable
    public UIElement buildHeaderControls(TimelineContext ctx, Track track, TrackUIState state) {
        return null; // no record toggle for speed
    }

    /** Draw a preview of the speed curve (+ keyframe dots) in the lane row. */
    @Override
    protected void drawLaneContent(TimelineContext ctx, GuiGraphics graphics, AnimationTrack track, float x, float y, float width, float height) {
        if (!(track instanceof SpeedTrack speedTrack)) {
            super.drawLaneContent(ctx, graphics, track, x, y, width, height);
            return;
        }
        var property = speedTrack.speedProperty();
        if (property == null) return;
        var min = property.rangeMin();
        var max = property.rangeMax();
        if (max <= min) max = min + 1;
        var pad = 2f;
        var inner = Math.max(1f, height - 2 * pad);
        var channel = property.channel(0);
        // curve polyline (sampled across the visible width)
        var points = new ArrayList<Vector2f>();
        for (float px = 0; px <= width; px += 2) {
            var tick = ctx.scrollTicks() + px / ctx.scale();
            var v = AnimatedProperty.sampleChannel(channel, tick);
            var ny = Mth.clamp(y + pad + inner * (1 - (v - min) / (max - min)), y, y + height);
            points.add(new Vector2f(x + px, ny));
        }
        if (points.size() > 1) {
            DrawerHelper.drawLines(graphics, points, ColorPattern.LIGHT_BLUE.color, ColorPattern.LIGHT_BLUE.color, 0.5f);
        }
        // keyframe dots
        for (int k = 0; k < property.keyCount(0); k++) {
            var key = property.key(0, k);
            var dx = x + (float) ((key.x - ctx.scrollTicks()) * ctx.scale());
            if (dx < x || dx > x + width) continue;
            var ny = Mth.clamp(y + pad + inner * (1 - (key.y - min) / (max - min)), y, y + height);
            DrawerHelper.drawSolidRect(graphics, dx - 1.5f, ny - 1.5f, 3, 3, ColorPattern.WHITE.color);
        }
    }
}

