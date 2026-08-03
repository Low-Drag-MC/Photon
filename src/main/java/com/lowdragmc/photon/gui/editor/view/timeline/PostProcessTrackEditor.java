package com.lowdragmc.photon.gui.editor.view.timeline;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.lowdraglib2.configurator.IToggleConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.BooleanConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.StringConfigurator;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Tooltips;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.IFieldValueConfigurable;
import com.lowdragmc.photon.client.fx.timeline.Clip;
import com.lowdragmc.photon.client.fx.timeline.PostProcessClip;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.configurator.NumberFunctionConfigurator;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.postfx.graph.gui.PassOptionConfigurators;
import com.lowdragmc.photon.client.postfx.runtime.CompiledEffect;
import com.lowdragmc.photon.client.postfx.runtime.PostEffectStack;
import com.lowdragmc.photon.gui.editor.resource.RenderGraphResource;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Timeline editor for {@link com.lowdragmc.photon.client.fx.timeline.PostProcessTrack}: clips are
 * "apply effect X" windows. The clip inspector picks the effect from the render-graph library
 * (effects only — bare fullscreen graphs are an API convenience, not clip content), shapes the
 * request weight as a {@link NumberFunction} sampled over the clip, and shows one row per schema
 * parameter in the {@code Property#createConfigurator} style: the row always edits a sampling
 * function (seeded from the schema default), the first edit promotes it to an override (orange
 * label), and the REPLAY square at the row end clears it back to the schema default.
 */
public class PostProcessTrackEditor extends ClipTrackEditor {

    @Override
    public ColorPattern chipColor() {
        return ColorPattern.PURPLE;
    }

    @Override
    public ColorPattern clipFillColor() {
        return ColorPattern.T_PURPLE;
    }

    @Override
    public UIElement buildHeaderContent(TimelineContext ctx, Track track, TrackUIState state) {
        return new UIElement(); // target-less track: nothing to bind
    }

    @Override
    protected void onLaneDoubleClick(TimelineContext ctx, Track track, float x, float y) {
        ctx.addClip(track, new PostProcessClip(Math.max(0, Math.round(ctx.xToTick(x))),
                ctx.defaultClipDuration(track.targetId()), 1.0f));
    }

    @Nullable
    @Override
    protected String clipLabel(TimelineContext ctx, Track track, Clip clip) {
        if (clip instanceof PostProcessClip post && !post.effect().isEmpty()) {
            return PassOptionConfigurators.graphDisplayName(post.effect());
        }
        return null;
    }

    @Override
    protected void buildClipConfigurator(ConfiguratorGroup group, TimelineContext ctx, Track track, Clip clip) {
        if (!(clip instanceof PostProcessClip post)) return;
        var paramsGroup = new ConfiguratorGroup("photon.gui.editor.timeline.post_process.params", false);
        paramsGroup.setTips("photon.gui.editor.timeline.post_process.params.tip");
        group.addConfigurator(effectGraphPicker(post, paramsGroup, ctx)
                .setTips("photon.gui.editor.timeline.post_process.effect_graph.tip"));
        var weightRow = new NumberFunctionConfigurator(
                "photon.gui.editor.timeline.post_process.weight",
                post::weight,
                fn -> {
                    post.weight(fn);
                    ctx.refreshPreview();
                }, true, WEIGHT_CONFIG);
        weightRow.setTips("photon.gui.editor.timeline.post_process.weight.tip");
        group.addConfigurator(weightRow);
        group.addConfigurator(maskFilterGroup(post, ctx));
        group.addConfigurator(new BooleanConfigurator(
                "photon.gui.editor.timeline.post_process.independent",
                post::independent,
                value -> {
                    post.independent(value);
                    ctx.refreshPreview();
                },
                false, true)
                .setTips("photon.gui.editor.timeline.post_process.independent.tip"));
        rebuildParamRows(paramsGroup, post, ctx);
        group.addConfigurator(paramsGroup);
    }

    /** A picker row over the render-graph library: the button shows the current effect, clicking
     *  opens the resource selector dialog. */
    private Configurator effectGraphPicker(PostProcessClip post, ConfiguratorGroup paramsGroup,
                                           TimelineContext ctx) {
        var row = new Configurator("photon.gui.editor.timeline.post_process.effect_graph");
        var button = new Button();
        button.setText(PassOptionConfigurators.graphDisplayName(post.effect()));
        button.setOnClick(event -> {
            var mui = event.currentElement.getModularUI();
            if (mui == null) return;
            RenderGraphResource.INSTANCE.setPathSelectListener(path -> {
                if (!path.getPathWithType().equals(post.effect())) {
                    post.effect(path.getPathWithType());
                    post.params().clear(); // the old schema's overrides are meaningless here
                }
                rebuildParamRows(paramsGroup, post, ctx);
                button.setText(PassOptionConfigurators.graphDisplayName(post.effect()));
                ctx.refreshPreview();
                ctx.requestRebuild(); // clip label shows the effect name
            });
            var dialog = RenderGraphResource.INSTANCE.getResourceInstance()
                    .createSelectorDialog(event.x, event.y, tag -> { }, () -> { });
            dialog.setOnClose(() -> RenderGraphResource.INSTANCE.setPathSelectListener(null));
            dialog.show(mui);
        });
        row.addInlineChild(button);
        return row;
    }

    /** CustomMask culling through the STOCK {@link IToggleConfigurable}
     *  rendering (same header toggle + collapse behavior as the renderer's Custom Mask group),
     *  adapted onto the clip's maskCulling/maskGroup fields. */
    private Configurator maskFilterGroup(PostProcessClip post, TimelineContext ctx) {
        var maskGroup = new ConfiguratorGroup("photon.gui.editor.timeline.post_process.mask_filter", false);
        maskGroup.setTips("photon.gui.editor.timeline.post_process.mask_filter.tip");
        var adapter = new IToggleConfigurable() {
            @Override
            public boolean isEnable() {
                return post.maskCulling();
            }

            @Override
            public void setEnable(boolean enable) {
                if (post.maskCulling() == enable) return;
                post.maskCulling(enable);
                ctx.refreshPreview();
            }

            @Override
            public void buildConfigurator(ConfiguratorGroup father) {
                IToggleConfigurable.super.buildConfigurator(father);
                father.addConfigurator(new StringConfigurator(
                        "photon.gui.editor.timeline.post_process.mask_filter.value",
                        post::maskGroup,
                        value -> {
                            post.maskGroup(value);
                            ctx.refreshPreview();
                        },
                        "", true)
                        .setTips("photon.gui.editor.timeline.post_process.mask_filter.value.tip"));
            }
        };
        adapter.buildConfigurator(maskGroup);
        return maskGroup;
    }

    // ---- parameter overrides -----------------------------------------------------------------

    /**
     * One function row per schema parameter channel. Not-overridden rows display functions seeded
     * from the schema default; ANY edit (value, curve, function-type switch) fires the setter —
     * every builtin function editor routes through {@code updateValue} — which promotes the seeded
     * set into a real override. Vectors get {@code .x/.y/.z/.w} rows sharing one override.
     */
    private void rebuildParamRows(ConfiguratorGroup paramsGroup, PostProcessClip post, TimelineContext ctx) {
        paramsGroup.removeAllConfigurators();
        var path = post.effectPath();
        var effect = path == null ? null : PostEffectStack.resolveEffect(path);
        if (effect == null) return;
        for (var spec : effect.schema()) {
            var kind = kindOf(spec);
            if (kind == null) continue;
            var name = spec.name();
            // reserved: the framework fills this from the clip's 遮罩过滤 selection (group id)
            if (CompiledEffect.MASK_FILTER_PARAM.equals(name)) continue;
            if (kind == PostProcessClip.ParamKind.SAMPLER) {
                buildSamplerRow(paramsGroup, post, ctx, name, spec);
                continue;
            }
            var seeded = new PostProcessClip.ParamOverride(kind, defaultChannels(kind, spec.defaultValue()));
            int count = kind.channelCount();
            for (int c = 0; c < count; c++) {
                final int ci = c;
                var label = count > 1 ? name + "." + "xyzw".charAt(ci) : name;
                var configurator = new NumberFunctionConfigurator(label,
                        () -> {
                            var current = post.params().getOrDefault(name, seeded);
                            return ci < current.channels().size() ? current.channels().get(ci)
                                    : NumberFunction.constant(0);
                        },
                        fn -> {
                            // first edit promotes the seeded defaults into a real override
                            var current = post.params().computeIfAbsent(name, key -> seeded);
                            if (ci < current.channels().size()) current.channels().set(ci, fn);
                            ctx.refreshPreview();
                        }, true, kind == PostProcessClip.ParamKind.COLOR ? COLOR_CONFIG : SCALAR_CONFIG);
                if (ci == 0) attachResetButton(configurator, paramsGroup, post, ctx, name);
                markWhenOverridden(configurator, post, name);
                paramsGroup.addConfigurator(configurator);
            }
        }
    }

    /** Property#createConfigurator-style reset: a small REPLAY square at the row end. It lives in
     *  the label line (NOT the inline content, which function-type switches rebuild), and only
     *  shows while the parameter is overridden. */
    private void attachResetButton(NumberFunctionConfigurator configurator, ConfiguratorGroup paramsGroup,
                                   PostProcessClip post, TimelineContext ctx, String name) {
        var reset = new Button().noText().setOnClick(event -> {
            post.params().remove(name);
            rebuildParamRows(paramsGroup, post, ctx);
            ctx.refreshPreview();
        });
        reset.layout(layout -> {
            layout.height(14);
            layout.width(14);
        }).addChild(new UIElement()
                .layout(layout -> {
                    layout.height(10);
                    layout.width(10);
                })
                .style(style -> style.backgroundTexture(Icons.REPLAY)
                        .tooltips("photon.gui.editor.timeline.post_process.param_reset")));
        reset.setDisplay(post.params().containsKey(name));
        configurator.lineContainer.addChildAt(reset, configurator.tip.getSiblingIndex());
        configurator.addEventListener(UIEvents.TICK, event ->
                reset.setDisplay(post.params().containsKey(name)));
    }

    /** Orange label while the parameter is overridden (the Property inline-value convention). */
    private static void markWhenOverridden(NumberFunctionConfigurator configurator, PostProcessClip post,
                                           String name) {
        var mark = new AtomicBoolean(false);
        Runnable sync = () -> {
            boolean overridden = post.params().containsKey(name);
            if (overridden == mark.get()) return;
            mark.set(overridden);
            configurator.label.setText(configurator.label.getText().copy().withStyle(style ->
                    style.withColor(overridden ? ColorPattern.ORANGE.color : -1)));
        };
        mark.set(!post.params().containsKey(name)); // force the initial apply
        sync.run();
        configurator.addEventListener(UIEvents.TICK, event -> sync.run());
    }

    /** The override kind a schema param maps to; null = a default we can't map (unset/unknown). */
    @Nullable
    private static PostProcessClip.ParamKind kindOf(CompiledEffect.ParamSpec spec) {
        return switch (spec.defaultValue()) {
            case Float ignored -> PostProcessClip.ParamKind.FLOAT;
            case Boolean ignored -> PostProcessClip.ParamKind.BOOL;
            // an Integer default is a COLOR (ARGB, lerpable) or an INT (not lerpable) parameter
            case Integer ignored -> spec.lerpable() ? PostProcessClip.ParamKind.COLOR
                    : PostProcessClip.ParamKind.INT;
            case Vector2f ignored -> PostProcessClip.ParamKind.VEC2;
            case Vector3f ignored -> PostProcessClip.ParamKind.VEC3;
            case Vector4f ignored -> PostProcessClip.ParamKind.VEC4;
            case RenderTypeGraphTypes.Sampler2DValue ignored -> PostProcessClip.ParamKind.SAMPLER;
            case null, default -> null;
        };
    }

    /**
     * A sampler-parameter row: the SAMPLER2D picker (the same editor the shader-graph blackboard uses),
     * seeded from the schema default. Picking a texture promotes it to an override; the REPLAY square on
     * the group header clears it back to the default. Samplers can't interpolate, so there is one static
     * value — no sampling function.
     */
    private void buildSamplerRow(ConfiguratorGroup paramsGroup, PostProcessClip post, TimelineContext ctx,
                                 String name, CompiledEffect.ParamSpec spec) {
        var defaultValue = spec.defaultValue() instanceof RenderTypeGraphTypes.Sampler2DValue s
                ? s : RenderTypeGraphTypes.Sampler2DValue.defaultValue();
        var adapter = new IFieldValueConfigurable() {
            @Override
            public void setValue(Object value) {
                if (!(value instanceof RenderTypeGraphTypes.Sampler2DValue s)) return;
                post.params().put(name, PostProcessClip.ParamOverride.sampler(s));
                ctx.refreshPreview();
            }

            @SuppressWarnings("unchecked")
            @Override
            public <T> T getValue() {
                var current = post.params().get(name);
                return (T) (current != null && current.sampler() != null ? current.sampler() : defaultValue);
            }

            @SuppressWarnings("unchecked")
            @Override
            public <T> T getDefaultValue() {
                return (T) defaultValue;
            }

            @Override
            public Tooltips getTooltips() {
                return Tooltips.of(new String[0]);
            }
        };
        var sub = new ConfiguratorGroup(name);
        sub.setCollapse(false);
        var resolved = RenderTypeGraphTypes.SAMPLER2D.resolveConfigurable();
        if (resolved != null) {
            var configurable = resolved.createConfigurable(adapter, RenderTypeGraphTypes.SAMPLER2D);
            if (configurable != null) configurable.buildConfigurator(sub);
        }
        attachSamplerReset(sub, paramsGroup, post, ctx, name);
        paramsGroup.addConfigurators(sub);
    }

    /** REPLAY reset on the sampler group header (mirrors the shader-graph variable reset): shows while
     *  overridden (orange title); clicking drops the override and rebuilds the rows. */
    private void attachSamplerReset(ConfiguratorGroup sub, ConfiguratorGroup paramsGroup, PostProcessClip post,
                                    TimelineContext ctx, String name) {
        var reset = new Button().noText().setOnClick(event -> {
            if (post.params().remove(name) == null) return;
            rebuildParamRows(paramsGroup, post, ctx);
            ctx.refreshPreview();
        });
        reset.layout(layout -> {
            layout.height(14);
            layout.width(14);
        }).addChild(new UIElement()
                .layout(layout -> {
                    layout.height(10);
                    layout.width(10);
                })
                .style(style -> style.backgroundTexture(Icons.REPLAY)
                        .tooltips("photon.gui.editor.timeline.post_process.param_reset")));
        sub.lineContainer.addChildAt(reset, sub.tip.getSiblingIndex());
        var mark = new AtomicBoolean(false);
        Runnable sync = () -> {
            boolean overridden = post.params().containsKey(name);
            if (overridden == mark.get()) return;
            mark.set(overridden);
            reset.setDisplay(overridden);
            sub.label.setText(sub.label.getText().copy().withStyle(style ->
                    style.withColor(overridden ? ColorPattern.ORANGE.color : -1)));
        };
        mark.set(!post.params().containsKey(name)); // force the initial apply
        sync.run();
        sub.addEventListener(UIEvents.TICK, event -> sync.run());
    }

    /** Fresh override functions seeded from the schema default (one per channel for vectors).
     *  MUTABLE list — the channel editors write back in place. */
    private static List<NumberFunction> defaultChannels(PostProcessClip.ParamKind kind,
                                                                  @Nullable Object defaultValue) {
        var channels = new ArrayList<NumberFunction>(kind.channelCount());
        switch (kind) {
            case COLOR -> channels.add(NumberFunction.color(defaultValue instanceof Integer argb ? argb : -1));
            case BOOL -> channels.add(NumberFunction.constant(defaultValue == Boolean.TRUE ? 1 : 0));
            case VEC2 -> {
                var v = defaultValue instanceof Vector2f vec ? vec : new Vector2f();
                channels.add(NumberFunction.constant(v.x));
                channels.add(NumberFunction.constant(v.y));
            }
            case VEC3 -> {
                var v = defaultValue instanceof Vector3f vec ? vec : new Vector3f();
                channels.add(NumberFunction.constant(v.x));
                channels.add(NumberFunction.constant(v.y));
                channels.add(NumberFunction.constant(v.z));
            }
            case VEC4 -> {
                var v = defaultValue instanceof Vector4f vec ? vec : new Vector4f();
                channels.add(NumberFunction.constant(v.x));
                channels.add(NumberFunction.constant(v.y));
                channels.add(NumberFunction.constant(v.z));
                channels.add(NumberFunction.constant(v.w));
            }
            default -> channels.add(NumberFunction.constant(defaultValue instanceof Number number ? number : 0));
        }
        return channels;
    }

    // real @NumberFunctionConfig instances for the editors (same reflection trick as
    // AdditionalGPUDataSetting): weight is 0..1, scalars are free, colors offer gradients
    @SuppressWarnings("unused")
    private static final class ConfigHolders {
        @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class},
                defaultValue = 1, curveConfig = @CurveConfig(bound = {0, 1}))
        private float weight;
        @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class})
        private float scalar;
        @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class},
                defaultValue = -1)
        private int color;
    }

    private static final NumberFunctionConfig WEIGHT_CONFIG = readConfig("weight");
    private static final NumberFunctionConfig SCALAR_CONFIG = readConfig("scalar");
    private static final NumberFunctionConfig COLOR_CONFIG = readConfig("color");

    private static NumberFunctionConfig readConfig(String field) {
        try {
            return ConfigHolders.class.getDeclaredField(field).getAnnotation(NumberFunctionConfig.class);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }
}
