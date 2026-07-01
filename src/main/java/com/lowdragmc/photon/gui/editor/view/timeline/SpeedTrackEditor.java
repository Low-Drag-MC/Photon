package com.lowdragmc.photon.gui.editor.view.timeline;

import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.OreSprites;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import com.lowdragmc.photon.client.fx.timeline.AnimationTrack;
import com.lowdragmc.photon.client.fx.timeline.SpeedTrack;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
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

    /** No property list / selection UI — just a centered "speed" label; the curve panel (expanded right)
     *  edits the single speed property directly. */
    @Override
    public UIElement buildExpandedLeft(TimelineContext ctx, Track track, TrackUIState state) {
        var st = (AnimationTrackUIState) state;
        autoSelectFirstProperty(st, (AnimationTrack) track); // so the curve panel always edits the speed curve
        var container = new UIElement().setId("timeline.speedLabel").layout(layout ->
                layout.widthPercent(100).paddingAll(4)); // height from the host wrapper's flex(1)
        container.getStyle().background(OreSprites.RECT2);
        var label = new Label().setText(Component.translatable("photon.gui.editor.timeline.property.speed").getString());
        label.getTextStyle().textAlignHorizontal(Horizontal.CENTER);
        TimelineContext.styleLabel(label); // vertically centered
        label.layout(l -> l.flex(1).heightPercent(100));
        container.addChild(label);
        return container;
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
        // curve polyline: uniform samples + each keyframe's exact tick (so vertical jumps stay vertical)
        var scroll = ctx.scrollTicks();
        var endTick = scroll + width / ctx.scale();
        var points = new ArrayList<Vector2f>();
        for (var t : curvePolylineTicks(property, 0, scroll, endTick, 2 / ctx.scale())) {
            var v = property.sampleChannelValue(0, t);
            var ny = Mth.clamp(y + pad + inner * (1 - (v - min) / (max - min)), y, y + height);
            points.add(new Vector2f(x + (t - scroll) * ctx.scale(), ny));
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

