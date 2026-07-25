package com.lowdragmc.photon.gui.editor.view.timeline;

import com.lowdragmc.lowdraglib2.configurator.ui.BooleanConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.RegistrySearchComponent;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.utils.UIElementProvider;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.photon.client.fx.timeline.AudioClip;
import com.lowdragmc.photon.client.fx.timeline.Clip;
import com.lowdragmc.photon.client.fx.timeline.SoundLengthCache;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.configurator.NumberFunctionConfigurator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Editor for {@code audio} tracks: an optional bound-target header (position source for 3D playback) +
 * a lane of {@link AudioClip}s. Each clip picks a {@code SoundEvent} and plays it for the clip's span;
 * the inspector exposes sound / volume / pitch / category / attenuation / loop-length, and the clip
 * draws loop sub-divisions so the user sees how many times the sound repeats.
 */
@OnlyIn(Dist.CLIENT)
public class AudioTrackEditor extends ClipTrackEditor {

    @Override
    public ColorPattern chipColor() {
        return ColorPattern.ORANGE;
    }

    @Override
    public ColorPattern clipFillColor() {
        return ColorPattern.T_ORANGE;
    }

    @Override
    public UIElement buildHeaderContent(TimelineContext ctx, Track track, TrackUIState state) {
        return buildTargetSlot(ctx, track, true);
    }

    @Override
    protected void onLaneDoubleClick(TimelineContext ctx, Track track, float x, float y) {
        ctx.addClip(track, new AudioClip(Math.max(0, Math.round(ctx.xToTick(x))),
                ctx.defaultClipDuration(track.targetId()), 1.0f));
    }

    @Override
    protected void onLaneRightClick(TimelineContext ctx, Track track, float x, float y) {
        var startTick = Math.max(0, Math.round(ctx.xToTick(x)));
        var menu = TreeBuilder.Menu.start();
        menu.leaf("photon.gui.editor.timeline.add_clip",
                () -> ctx.addClip(track, new AudioClip(startTick, ctx.defaultClipDuration(track.targetId()), 1.0f)));
        ctx.openMenu(x, y, menu);
    }

    @Nullable
    @Override
    protected String clipLabel(TimelineContext ctx, Track track, Clip clip) {
        return clip instanceof AudioClip audio ? audio.sound().getPath() : null;
    }

    @Override
    protected void drawClipDecoration(GuiGraphics graphics, TimelineContext ctx, Track track, Clip clip,
                                      float x, float y, float w, float h, float pt) {
        if (!(clip instanceof AudioClip audio)) {
            return;
        }
        // one sub-division per loop, spaced by the auto-detected sound length (the clip length = rounds)
        var loop = SoundLengthCache.getTicks(audio.sound());
        if (loop <= 0) return;
        var step = (float) (loop * ctx.scale());
        if (step < 2) return; // avoid drawing a solid block when the loop is tiny at this zoom
        for (float lx = x + step; lx < x + w - 0.5f; lx += step) {
            DrawerHelper.drawSolidRect(graphics, lx - 0.5f, y + 1, 1, h - 2, ColorPattern.T_WHITE.color);
        }
    }

    @Override
    protected void buildClipConfigurator(ConfiguratorGroup group, TimelineContext ctx, Track track, Clip clip) {
        if (!(clip instanceof AudioClip audio)) return;
        var defaultSound = resolveSound(audio.sound());
        group.addConfigurator(new RegistrySearchComponent<>("photon.gui.editor.timeline.audio.sound",
                () -> resolveSound(audio.sound()),
                v -> {
                    var key = BuiltInRegistries.SOUND_EVENT.getKey(v);
                    if (key != null) audio.sound(key);
                    // the clip label + loop sub-divisions are live-bound, so no rebuild is needed here
                    ctx.refreshPreview();
                }, defaultSound, true, BuiltInRegistries.SOUND_EVENT,
                UIElementProvider.text(sound -> {
                    var key = BuiltInRegistries.SOUND_EVENT.getKey(sound);
                    return Component.literal(key == null ? "unknown" : key.toString());
                })));
        // a preview button: play the sound once (non-positional) so the user can hear it
        group.addConfigurator(new Configurator("photon.gui.editor.timeline.audio.preview").addInlineChild(
                new Button().setText("photon.gui.editor.timeline.audio.play", true)
                        .setOnClick(e -> previewSound(audio))
                        .layout(layout -> layout.flex(1).height(14))));
        group.addConfigurator(new NumberFunctionConfigurator("photon.gui.editor.timeline.audio.volume",
                audio::volume, fn -> { audio.volume(fn); ctx.refreshPreview(); }, true, VOLUME_CONFIG));
        group.addConfigurator(new NumberFunctionConfigurator("photon.gui.editor.timeline.audio.pitch",
                audio::pitch, fn -> { audio.pitch(fn); ctx.refreshPreview(); }, true, PITCH_CONFIG));
        group.addConfigurator(new SelectorConfigurator<>("photon.gui.editor.timeline.audio.category",
                audio::category, v -> { audio.category(v); ctx.refreshPreview(); }, audio.category(), true,
                List.of(SoundSource.values()), SoundSource::getName));
        group.addConfigurator(new BooleanConfigurator("photon.gui.editor.timeline.audio.attenuation",
                audio::attenuation, v -> { audio.attenuation(v); ctx.refreshPreview(); }, audio.attenuation(), true));
    }

    /** Play the clip's sound once, non-positional, honoring its category/volume/pitch, for previewing.
     *  The envelopes are sampled at the clip's start — a one-shot preview cannot follow a curve. */
    private static void previewSound(AudioClip audio) {
        if (BuiltInRegistries.SOUND_EVENT.get(audio.sound()) == null) return;
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(
                audio.sound(), audio.category(), Math.max(0.0001f, audio.volumeAt(0)), audio.pitchAt(0),
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
    }

    // real @NumberFunctionConfig instances for the editor rows (same reflection trick as
    // PostProcessTrackEditor): volume is 0..1, pitch is the range the sound engine accepts
    @SuppressWarnings("unused")
    private static final class ConfigHolders {
        @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class},
                defaultValue = 1, curveConfig = @CurveConfig(bound = {0, 1}))
        private float volume;
        @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class},
                defaultValue = 1, curveConfig = @CurveConfig(bound = {0.5f, 2}))
        private float pitch;
    }

    private static final NumberFunctionConfig VOLUME_CONFIG = readConfig("volume");
    private static final NumberFunctionConfig PITCH_CONFIG = readConfig("pitch");

    private static NumberFunctionConfig readConfig(String field) {
        try {
            return ConfigHolders.class.getDeclaredField(field).getAnnotation(NumberFunctionConfig.class);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    /** Resolve a stored sound id to its SoundEvent, falling back to a always-present vanilla sound. */
    private static SoundEvent resolveSound(net.minecraft.resources.ResourceLocation id) {
        var sound = BuiltInRegistries.SOUND_EVENT.get(id);
        return sound != null ? sound : SoundEvents.UI_BUTTON_CLICK.value();
    }
}
