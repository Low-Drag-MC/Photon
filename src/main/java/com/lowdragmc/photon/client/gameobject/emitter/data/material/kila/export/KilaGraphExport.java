package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.export;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.GradientGlsl;
import com.lowdragmc.kilagraph.rendertype.nodes.artistic.gradient.SampleGradientNode;
import com.lowdragmc.kilagraph.rendertype.nodes.channel.SplitNode;
import com.lowdragmc.kilagraph.rendertype.nodes.constant.TimeNode;
import com.lowdragmc.kilagraph.rendertype.nodes.fog.ApplyFogNode;
import com.lowdragmc.kilagraph.rendertype.nodes.fragment.FragmentAlphaBlock;
import com.lowdragmc.kilagraph.rendertype.nodes.fragment.FragmentBaseColorBlock;
import com.lowdragmc.kilagraph.rendertype.nodes.input.NormalNode;
import com.lowdragmc.kilagraph.rendertype.nodes.input.PositionNode;
import com.lowdragmc.kilagraph.rendertype.nodes.input.UVNode;
import com.lowdragmc.kilagraph.rendertype.nodes.input.VertexColorNode;
import com.lowdragmc.kilagraph.rendertype.nodes.input.fragment.FrontFacingNode;
import com.lowdragmc.kilagraph.rendertype.nodes.procedural.noise.GradientNoiseNode;
import com.lowdragmc.kilagraph.rendertype.nodes.procedural.noise.SimpleNoiseNode;
import com.lowdragmc.kilagraph.rendertype.nodes.procedural.noise.VoronoiNode;
import com.lowdragmc.kilagraph.rendertype.nodes.scene.SceneColorNode;
import com.lowdragmc.kilagraph.rendertype.nodes.scene.SceneDepthNode;
import com.lowdragmc.kilagraph.rendertype.nodes.texture.SamplerTexture2DNode;
import com.lowdragmc.kilagraph.rendertype.nodes.transform.DynamicTransformsUboNode;
import com.lowdragmc.kilagraph.rendertype.nodes.uv.PolarCoordinatesNode;
import com.lowdragmc.kilagraph.rendertype.nodes.uv.RotateNode;
import com.lowdragmc.kilagraph.rendertype.nodes.uv.TilingAndOffsetNode;
import com.lowdragmc.kilagraph.rendertype.nodes.uv.TwirlNode;
import com.lowdragmc.kilagraph.rendertype.nodes.vertex.VertexModelPositionBlock;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandles;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.PortModel;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.ShaderGraphMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaDissolve;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaDistortion;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaDriver;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaFresnel;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaLayer;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMainTexture;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMask;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaModule;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaScreenDistortion;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaShading;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTexture;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaUvEffects;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaVertexOffset;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;
import com.lowdragmc.photon.client.shadergraph.nodes.AdditionalDataNode;
import com.lowdragmc.photon.client.shadergraph.nodes.CustomDataNode;
import com.lowdragmc.photon.client.shadergraph.nodes.DepthFadeNode;
import com.lowdragmc.photon.client.shadergraph.nodes.DynamicLightNode;
import com.lowdragmc.photon.client.shadergraph.nodes.ParticleDataNode;
import com.lowdragmc.photon.client.shadergraph.nodes.ViewportNode;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector2f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static com.lowdragmc.kilagraph.rendertype.compiler.GlslType.FLOAT;
import static com.lowdragmc.kilagraph.rendertype.compiler.GlslType.SAMPLER2D;
import static com.lowdragmc.kilagraph.rendertype.compiler.GlslType.VEC2;
import static com.lowdragmc.kilagraph.rendertype.compiler.GlslType.VEC3;
import static com.lowdragmc.kilagraph.rendertype.compiler.GlslType.VEC4;
import static com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.export.GraphWriter.Port.in;
import static com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.export.GraphWriter.out;

/**
 * A KilaMaterial as a shader graph that draws the same: colours, textures and strengths become exposed variables,
 * uv chains, samplers, noises and scene reads native nodes, and Kila's own formulas expression nodes, in
 * {@code kila.fsh}'s order. What a graph cannot do the way Kila does is left out and listed by lang key.
 */
@OnlyIn(Dist.CLIENT)
public final class KilaGraphExport {
    /** {@code renderState} null when the material leaves blend, cull and depth to its slot. */
    public record Result(ShaderGraph graph, @Nullable ShaderGraphMaterial.RenderState renderState, List<String> skipped) {
    }

    private static final String LUMA = "vec3(0.2126, 0.7152, 0.0722)";

    private final KilaMaterial m;
    private final ShaderGraph graph;
    private final GraphWriter w;
    private final Set<KilaModule> dropped;
    private final Set<String> skipped = new LinkedHashSet<>();
    private final boolean flowMap;
    @Nullable
    private PortModel rawTime, time, frameRate, rawUv, meshUv, screenUv, world, vertexColor, viewPos, viewNormal,
            modelView, normal, flow, flowPhase;

    public static Result export(KilaMaterial material) {
        var export = new KilaGraphExport(material);
        export.build();
        export.w.layout();
        return new Result(export.graph, export.renderState(), List.copyOf(export.skipped));
    }

    private KilaGraphExport(KilaMaterial material) {
        this.m = material;
        this.graph = new ShaderGraph() {
            @Override
            protected void initializeDefaultEntityShader() {
            }
        };
        this.w = new GraphWriter(graph);
        this.dropped = Set.copyOf(material.layout().dropped);
        for (var module : dropped) skipped.add(module.langKey());
        this.flowMap = on(material.distortion) && material.distortion.mode == KilaDistortion.Mode.FLOW_MAP;
    }

    private boolean on(KilaModule module) {
        return module.isEnable() && !dropped.contains(module);
    }

    @Nullable
    private ShaderGraphMaterial.RenderState renderState() {
        if (m.slotRenderState) return null;
        return new ShaderGraphMaterial.RenderState(m.blend, m.doubleSided, m.backFacesFirst, m.depthTest, m.depthWrite);
    }

    // ---- building blocks ---------------------------------------------------------------------------

    private PortModel number(String name, float value) {
        return w.variable(name, TypeHandles.FLOAT, value);
    }

    private PortModel vector(String name, Vector2f value) {
        return w.variable(name, RenderTypeGraphTypes.VEC2, new Vector2f(value));
    }

    private PortModel vector(String name, Vector3f value) {
        return w.variable(name, RenderTypeGraphTypes.VEC3, new Vector3f(value));
    }

    private PortModel color(String name, HDRColor value) {
        return w.variable(name, TypeHandles.HDR_COLOR, value.copy());
    }

    private PortModel gradient(String name, GradientColor value) {
        if (value.getRgbP().size() > GradientGlsl.MAX_KEYS || value.getAP().size() > GradientGlsl.MAX_KEYS) {
            skipped.add("kila.export.skipped.gradient_keys");
        }
        return w.variable(name, RenderTypeGraphTypes.GRADIENT,
                new RenderTypeGraphTypes.GradientValue(value.copy(), RenderTypeGraphTypes.BlendMode.BLEND));
    }

    private PortModel expr(String output, GlslType type, String body, GraphWriter.Port... inputs) {
        return expr(output, type, body, List.of(inputs));
    }

    private PortModel expr(String output, GlslType type, String body, List<GraphWriter.Port> inputs) {
        return out(w.expression(inputs, List.of(GraphWriter.Port.out(output, type)), body), output);
    }

    private PortModel split(PortModel vector, String channel) {
        var node = w.node(SplitNode.class);
        w.wire(node, "in", vector);
        return out(node, channel);
    }

    private static String vec4(float[] v) {
        return "vec4(" + v[0] + ", " + v[1] + ", " + v[2] + ", " + v[3] + ")";
    }

    // ---- inputs ------------------------------------------------------------------------------------

    private PortModel rawTime() {
        if (rawTime == null) rawTime = out(w.node(TimeNode.class), "time");
        return rawTime;
    }

    private PortModel frameRate() {
        if (frameRate == null) frameRate = number("Frame Rate", m.frameRate);
        return frameRate;
    }

    /** Seconds, stepped to the frame rate when the material draws on twos. */
    private PortModel time() {
        if (time == null) {
            time = m.frameRate > 0 ? expr("stepped", FLOAT, "stepped = floor(t * fps) / fps;",
                    in("t", FLOAT, rawTime()), in("fps", FLOAT, frameRate())) : rawTime();
        }
        return time;
    }

    private PortModel vertexColor() {
        if (vertexColor == null) {
            var node = w.node(VertexColorNode.class);
            GraphWriter.option(node, "mode", VertexColorNode.MODE_COLOR);
            vertexColor = out(node, "out");
        }
        return vertexColor;
    }

    private PortModel viewPos() {
        if (viewPos == null) {
            var node = w.node(PositionNode.class);
            GraphWriter.option(node, "space", "view");
            viewPos = out(node, "out");
        }
        return viewPos;
    }

    private PortModel viewNormal() {
        if (viewNormal == null) {
            var node = w.node(NormalNode.class);
            GraphWriter.option(node, "space", "view");
            viewNormal = out(node, "out");
        }
        return viewNormal;
    }

    private PortModel modelView() {
        if (modelView == null) modelView = out(w.node(DynamicTransformsUboNode.class), "ModelViewMat");
        return modelView;
    }

    private PortModel screenUv() {
        if (screenUv == null) screenUv = out(w.node(ViewportNode.class), "screenUv");
        return screenUv;
    }

    private PortModel world() {
        if (world == null) {
            var node = w.node(PositionNode.class);
            GraphWriter.option(node, "space", "world");
            world = out(node, "out");
        }
        return world;
    }

    private PortModel rawUv() {
        if (rawUv == null) rawUv = out(w.node(UVNode.class), "out");
        return rawUv;
    }

    /** The mesh uv every mesh-space slot starts from, through the boil, the uv effects and the pixelation. */
    private PortModel meshUv() {
        if (meshUv == null) meshUv = w.aside("UV", this::buildMeshUv);
        return meshUv;
    }

    private PortModel buildMeshUv() {
        var uv = rawUv();
        if (m.frameRate > 0 && m.boil > 0) {
            uv = expr("boiled", VEC2, """
                            float tick = floor(t * fps);
                            boiled = uv + (fract(sin(vec2(tick * 12.9898, tick * 78.233)) * 43758.5453) - 0.5) * boil;""",
                    in("uv", VEC2, uv), in("t", FLOAT, rawTime()), in("fps", FLOAT, frameRate()),
                    in("boil", FLOAT, number("Boil", m.boil)));
        }
        if (on(m.uvEffects)) uv = uvEffects(m.uvEffects, uv);
        if (on(m.pixelate)) {
            uv = expr("snapped", VEC2, "snapped = (floor(uv * n) + 0.5) / n;", in("uv", VEC2, uv),
                    in("n", FLOAT, number("Pixels", Math.max(m.pixelate.pixels, 1))));
        }
        return uv;
    }

    private PortModel uvEffects(KilaUvEffects fx, PortModel uv) {
        var center = vector("Twirl Center", fx.twirlCenter);
        if (fx.twirl.isDriven() || fx.twirl.value != 0) {
            var twirl = w.node(TwirlNode.class);
            w.wire(twirl, "uv", uv);
            w.wire(twirl, "center", center);
            w.wire(twirl, "strength", driver("Twirl", fx.twirl));
            uv = out(twirl, "out");
        }
        if (fx.shear.x != 0 || fx.shear.y != 0) {
            uv = expr("sheared", VEC2, "vec2 d = uv - center;\nsheared = uv + vec2(d.y, -d.x) * dot(d, d) * shear;",
                    in("uv", VEC2, uv), in("center", VEC2, center), in("shear", VEC2, vector("Shear", fx.shear)));
        }
        float tau = (float) (Math.PI * 2);
        if (fx.waveAmplitude.isDriven() || fx.waveAmplitude.value != 0) {
            boolean alongU = fx.waveAxis == KilaUvEffects.WaveAxis.U;
            uv = expr("waved", VEC2, "float wave = sin(uv." + (alongU ? "x" : "y") + " * frequency + t * speed) * amplitude;\n"
                            + "waved = " + (alongU ? "vec2(uv.x, uv.y + wave)" : "vec2(uv.x + wave, uv.y)") + ";",
                    in("uv", VEC2, uv), in("t", FLOAT, time()), in("amplitude", FLOAT, driver("Wave", fx.waveAmplitude)),
                    in("frequency", FLOAT, number("Wave Frequency", fx.waveFrequency * tau)),
                    in("speed", FLOAT, number("Wave Speed", fx.waveSpeed * tau)));
        }
        if (fx.rippleAmplitude != 0) {
            uv = expr("rippled", VEC2, """
                            vec2 d = uv - center;
                            float r = length(d);
                            rippled = uv + d / max(r, 1e-4) * sin(r * frequency - t * speed) * amplitude;""",
                    in("uv", VEC2, uv), in("center", VEC2, center), in("t", FLOAT, time()),
                    in("amplitude", FLOAT, number("Ripple", fx.rippleAmplitude)),
                    in("frequency", FLOAT, number("Ripple Frequency", fx.rippleFrequency * tau)),
                    in("speed", FLOAT, number("Ripple Speed", fx.rippleSpeed * tau)));
        }
        return uv;
    }

    // ---- drivers -----------------------------------------------------------------------------------

    /** value + scale x the particle data the driver follows, or just the value. */
    private PortModel driver(String name, KilaDriver driver) {
        var value = number(name, driver.value);
        if (!driver.isDriven()) return value;
        return expr("driven", FLOAT, "driven = base + scale * source;", in("base", FLOAT, value),
                in("scale", FLOAT, number(name + " Scale", driver.scale)), in("source", FLOAT, source(driver)));
    }

    private PortModel source(KilaDriver driver) {
        return switch (driver.source) {
            case VERTEX_R -> split(vertexColor(), "r");
            case VERTEX_G -> split(vertexColor(), "g");
            case VERTEX_B -> split(vertexColor(), "b");
            case VERTEX_A -> split(vertexColor(), "a");
            case LIFE -> particleData("addition_gpu_data.t");
            case RANDOM -> particleData("addition_gpu_data.random");
            case POINT_LIFE -> particleData("addition_gpu_data.point_life");
            case POINT_T -> particleData("addition_gpu_data.point_t");
            case CUSTOM -> {
                var node = w.node(CustomDataNode.class);
                GraphWriter.option(node, "index", Math.clamp(driver.stream, 0, 3));
                yield split(out(node, "out"), String.valueOf("rgba".charAt(Math.clamp(driver.channel, 0, 3))));
            }
            case CONSTANT -> throw new IllegalArgumentException("a constant driver has no source");
        };
    }

    private PortModel particleData(String channel) {
        var node = w.node(AdditionalDataNode.class);
        GraphWriter.option(node, "channel", channel);
        return out(node, "out");
    }

    /** A driven uv offset, or null while it stays at 0. */
    @Nullable
    private PortModel offset(String name, KilaDriver u, KilaDriver v) {
        if (!u.isDriven() && !v.isDriven() && u.value == 0 && v.value == 0) return null;
        return expr("offset", VEC2, "offset = vec2(u, v);", in("u", FLOAT, driver(name + " U", u)),
                in("v", FLOAT, driver(name + " V", v)));
    }

    // ---- texture slots -----------------------------------------------------------------------------

    /** Kila's uv chain: base, polar, rotation, tiling and offset, scroll, then the driven offset. */
    private PortModel slotUv(String name, KilaTexture t, @Nullable PortModel offset) {
        return slotUv(name, t, offset, switch (t.uvSource) {
            case MESH -> meshUv();
            case SCREEN -> screenUv();
            case WORLD_XZ, WORLD_XY, WORLD_ZY -> plane(t.uvSource, world());
        });
    }

    private PortModel plane(KilaTexture.UvSource source, PortModel world) {
        var axes = switch (source) {
            case WORLD_XY -> "xy";
            case WORLD_ZY -> "zy";
            default -> "xz";
        };
        return expr("plane", VEC2, "plane = p." + axes + ";", in("p", VEC3, world));
    }

    private PortModel slotUv(String name, KilaTexture t, @Nullable PortModel offset, PortModel uv) {
        if (t.polar) {
            var polar = w.node(PolarCoordinatesNode.class);
            w.wire(polar, "uv", uv);
            w.wire(polar, "center", vector(name + " Polar Center", t.polarCenter));
            w.wire(polar, "radialScale", number(name + " Polar Radial", t.polarRadial));
            w.wire(polar, "lengthScale", number(name + " Polar Angular", t.polarAngular));
            uv = out(polar, "out");
        }
        if (t.rotation != 0 || t.rotationSpeed != 0) {
            var rotate = w.node(RotateNode.class);
            w.wire(rotate, "uv", uv);
            var angle = number(name + " Rotation", (float) Math.toRadians(t.rotation));
            if (t.rotationSpeed != 0) {
                angle = expr("angle", FLOAT, "angle = start + speed * t;", in("start", FLOAT, angle),
                        in("speed", FLOAT, number(name + " Rotation Speed", (float) Math.toRadians(t.rotationSpeed))),
                        in("t", FLOAT, time()));
            }
            w.wire(rotate, "rotation", angle);
            uv = out(rotate, "out");
        }
        var tiling = w.node(TilingAndOffsetNode.class);
        w.wire(tiling, "uv", uv);
        w.wire(tiling, "tiling", vector(name + " Tiling", t.tiling));
        w.wire(tiling, "offset", vector(name + " Offset", t.offset));
        uv = out(tiling, "out");
        if (t.scroll.x != 0 || t.scroll.y != 0) {
            uv = expr("scrolled", VEC2, "scrolled = uv + scroll * t;", in("uv", VEC2, uv),
                    in("scroll", VEC2, vector(name + " Scroll", t.scroll)), in("t", FLOAT, time()));
        }
        if (offset != null) uv = add(uv, offset);
        return uv;
    }

    private PortModel add(PortModel uv, PortModel offset) {
        return expr("moved", VEC2, "moved = uv + offset;", in("uv", VEC2, uv), in("offset", VEC2, offset));
    }

    private static boolean clamped(KilaTexture.Wrap wrap) {
        return wrap == KilaTexture.Wrap.CLAMP || wrap == KilaTexture.Wrap.CLIP;
    }

    /** A value read at a uv, and the uv a clip tests: the moved one with an offset flow, the slot's own with a flow map. */
    private record Read(PortModel value, PortModel uv) {
    }

    /**
     * {@code read} at {@code uv} through the distortion: an offset flow moves the uv, a flow map reads twice half a
     * cycle apart and crossfades, so the uv never runs away.
     */
    private Read throughFlow(PortModel uv, @Nullable PortModel flow, GlslType type, Function<PortModel, PortModel> read) {
        if (flow == null) return new Read(read.apply(uv), uv);
        if (!flowMap) {
            var moved = add(uv, flow);
            return new Read(read.apply(moved), moved);
        }
        var a = read.apply(expr("at", VEC2, "at = uv - flow * phase.x;", in("uv", VEC2, uv), in("flow", VEC2, flow),
                in("phase", VEC3, flowPhase)));
        var b = read.apply(expr("at", VEC2, "at = uv - flow * phase.y;", in("uv", VEC2, uv), in("flow", VEC2, flow),
                in("phase", VEC3, flowPhase)));
        return new Read(expr("crossfaded", type, "crossfaded = mix(a, b, phase.z);", in("a", type, a), in("b", type, b),
                in("phase", VEC3, flowPhase)), uv);
    }

    /** {@code value} with its alpha (or itself) 0 where a clipped axis leaves 0..1. */
    private PortModel clip(KilaTexture t, PortModel uv, PortModel value, GlslType type) {
        boolean u = t.wrapU == KilaTexture.Wrap.CLIP;
        boolean v = t.wrapV == KilaTexture.Wrap.CLIP;
        if (!u && !v) return value;
        var outside = (u ? "uv.x < 0.0 || uv.x > 1.0" : "") + (u && v ? " || " : "") + (v ? "uv.y < 0.0 || uv.y > 1.0" : "");
        String body = type == VEC4 ? "kept = value;\nif (" + outside + ") kept.a = 0.0;"
                : "kept = (" + outside + ") ? 0.0 : value;";
        return expr("kept", type, body, in("uv", VEC2, uv), in("value", type, value));
    }

    private PortModel colorMode(KilaTexture t, PortModel texel) {
        return switch (t.colorMode) {
            case RGBA -> texel;
            case RED_AS_ALPHA -> expr("mode", VEC4, "mode = vec4(c.rgb, c.r);", in("c", VEC4, texel));
            case ALPHA_ONLY -> expr("mode", VEC4, "mode = vec4(1.0, 1.0, 1.0, c.a);", in("c", VEC4, texel));
            case LUMINANCE_AS_ALPHA -> expr("mode", VEC4, "mode = vec4(c.rgb, dot(c.rgb, " + LUMA + "));", in("c", VEC4, texel));
        };
    }

    /** One texture or noise, its sampler or noise parameters made once however many times it is read. */
    private final class Source {
        final String name;
        final KilaTexture t;
        @Nullable
        PortModel sampler, scale, motion;

        Source(String name, KilaTexture t) {
            this.name = name;
            this.t = t;
            if (t.isProcedural()) {
                scale = number(name + " Noise Scale", t.noiseScale);
                if (t.noise == KilaTexture.Noise.VORONOI) motion = number(name + " Noise Motion", t.noiseMotion);
            } else {
                var address = clamped(t.wrapU) && clamped(t.wrapV) ? RenderTypeGraphTypes.SamplerAddress.CLAMP
                        : RenderTypeGraphTypes.SamplerAddress.REPEAT;
                var value = new RenderTypeGraphTypes.Sampler2DValue(t.getTexture().toString(), RenderTypeGraphTypes.SamplerMode.CUSTOM,
                        t.nearest ? RenderTypeGraphTypes.SamplerFilter.NEAREST : RenderTypeGraphTypes.SamplerFilter.LINEAR,
                        address, false);
                sampler = w.variable(name + " Texture", RenderTypeGraphTypes.SAMPLER2D, value);
            }
        }

        PortModel texel(PortModel uv) {
            var node = w.node(SamplerTexture2DNode.class);
            w.wire(node, "sampler", sampler);
            w.wire(node, "uv", wrapUv(uv));
            return out(node, "color");
        }

        /**
         * A sampler has one wrap for both axes: what it can't do for an axis happens to the uv, kept half a texel
         * in so repeat filtering doesn't bleed the far edge in.
         */
        private PortModel wrapUv(PortModel uv) {
            if (clamped(t.wrapU) && clamped(t.wrapV)) return uv;
            if (t.wrapU == KilaTexture.Wrap.REPEAT && t.wrapV == KilaTexture.Wrap.REPEAT) return uv;
            return expr("wrapped", VEC2, "vec2 h = 0.5 / vec2(textureSize(tex, 0));\nwrapped = vec2("
                            + axis(t.wrapU, "uv.x", "h.x") + ", " + axis(t.wrapV, "uv.y", "h.y") + ");",
                    in("uv", VEC2, uv), in("tex", SAMPLER2D, sampler));
        }

        PortModel color(PortModel uv) {
            return colorMode(t, texel(uv));
        }

        PortModel value(PortModel uv) {
            return t.isProcedural() ? noise(uv) : channel(texel(uv), t.channel);
        }

        PortModel noise(PortModel uv) {
            return switch (t.noise) {
                case SIMPLE, TEXTURE -> {
                    var node = w.node(SimpleNoiseNode.class);
                    w.wire(node, "uv", uv);
                    w.wire(node, "scale", scale);
                    yield out(node, "out");
                }
                case GRADIENT -> {
                    var node = w.node(GradientNoiseNode.class);
                    w.wire(node, "uv", uv);
                    w.wire(node, "scale", scale);
                    yield out(node, "out");
                }
                case VORONOI -> {
                    var node = w.node(VoronoiNode.class);
                    w.wire(node, "uv", uv);
                    w.wire(node, "angleOffset", expr("angle", FLOAT, "angle = 2.0 + t * motion;", in("t", FLOAT, time()),
                            in("motion", FLOAT, motion)));
                    w.wire(node, "cellDensity", scale);
                    yield out(node, "out");
                }
            };
        }

        /** Two decorrelated reads: the texture's red and green, or the noise and the noise a little way off. */
        PortModel pair(PortModel uv) {
            if (!t.isProcedural()) return expr("rg", VEC2, "rg = c.rg;", in("c", VEC4, texel(uv)));
            var shifted = expr("shifted", VEC2, "shifted = uv + vec2(17.31, 5.27);", in("uv", VEC2, uv));
            return expr("rg", VEC2, "rg = vec2(a, b);", in("a", FLOAT, noise(uv)), in("b", FLOAT, noise(shifted)));
        }
    }

    private static String axis(KilaTexture.Wrap wrap, String x, String h) {
        return switch (wrap) {
            case MIRROR -> "clamp(1.0 - abs(mod(" + x + ", 2.0) - 1.0), " + h + ", 1.0 - " + h + ")";
            case CLAMP, CLIP -> "clamp(" + x + ", " + h + ", 1.0 - " + h + ")";
            case REPEAT -> x;
        };
    }

    private PortModel channel(PortModel texel, KilaTexture.Channel channel) {
        return switch (channel) {
            case R -> split(texel, "r");
            case G -> split(texel, "g");
            case B -> split(texel, "b");
            case A -> split(texel, "a");
            case LUMINANCE -> expr("luma", FLOAT, "luma = dot(c.rgb, " + LUMA + ");", in("c", VEC4, texel));
        };
    }

    private PortModel colorSlot(String name, KilaTexture t, @Nullable PortModel offset, @Nullable PortModel flow) {
        var source = new Source(name, t);
        var read = throughFlow(slotUv(name, t, offset), flow, VEC4, source::color);
        return clip(t, read.uv(), read.value(), VEC4);
    }

    private PortModel valueSlot(String name, KilaTexture t, @Nullable PortModel offset, @Nullable PortModel flow) {
        var source = new Source(name, t);
        var read = throughFlow(slotUv(name, t, offset), flow, FLOAT, source::value);
        return clip(t, read.uv(), read.value(), FLOAT);
    }

    // ---- the main texture --------------------------------------------------------------------------

    private record Sheet(PortModel grid, PortModel fps, PortModel start, @Nullable PortModel frame, @Nullable PortModel seed,
                         @Nullable PortModel motion, @Nullable PortModel motionStrength) {
    }

    private PortModel mainTexture() {
        var main = m.main;
        var source = new Source("Main", main.texture);
        Sheet sheet = null;
        if (main.flipbook) {
            boolean driven = main.frameSource == KilaMainTexture.FrameSource.DRIVER;
            boolean motion = main.frameBlend && main.motionVectors;
            sheet = new Sheet(vector("Flipbook Grid", new Vector2f(Math.max(main.columns, 1), Math.max(main.rows, 1))),
                    number("Flipbook FPS", main.fps), number("Flipbook Start Frame", main.startFrame),
                    driven ? driver("Flipbook Frame", main.frame) : null,
                    main.randomRow || main.randomStart ? particleData("addition_gpu_data.random") : null,
                    motion ? new Source("Motion Vectors", main.motionTexture).sampler : null,
                    motion ? number("Motion Strength", main.motionStrength) : null);
        }
        var split = main.dispersion ? expr("split", VEC2, "split = " + (main.dispersionRadial ? "(uv - 0.5)" : "direction")
                        + " * amount;", main.dispersionRadial ? List.of(in("uv", VEC2, meshUv()),
                        in("amount", FLOAT, driver("Dispersion", main.dispersionAmount)))
                        : List.of(in("direction", VEC2, vector("Dispersion Direction", main.dispersionDirection)),
                        in("amount", FLOAT, driver("Dispersion", main.dispersionAmount))))
                : null;
        var finalSheet = sheet;
        Function<PortModel, PortModel> sample = uv -> colorMode(main.texture,
                finalSheet == null ? source.texel(uv) : flipbook(source, finalSheet, uv));
        Function<PortModel, PortModel> fetch = split == null ? sample : uv -> {
            var r = sample.apply(expr("at", VEC2, "at = uv + split;", in("uv", VEC2, uv), in("split", VEC2, split)));
            var b = sample.apply(expr("at", VEC2, "at = uv - split;", in("uv", VEC2, uv), in("split", VEC2, split)));
            var g = sample.apply(uv);
            // split premultiplied, so a white shape still fringes
            return expr("dispersed", VEC4, """
                            float a = max(max(r.a, g.a), b.a);
                            dispersed = vec4(vec3(r.r * r.a, g.g * g.a, b.b * b.a) / max(a, 1e-4), a);""",
                    in("r", VEC4, r), in("g", VEC4, g), in("b", VEC4, b));
        };
        var read = throughFlow(slotUv("Main", main.texture, offset("Main Offset", main.offsetU, main.offsetV)),
                flowFor(m.distortion.main), VEC4, fetch);
        return clip(main.texture, read.uv(), read.value(), VEC4);
    }

    /** The sheet at {@code uv}: the frame, wrapped inside its cell, blended into the next along its motion. */
    private PortModel flipbook(Source source, Sheet sheet, PortModel uv) {
        var main = m.main;
        var inputs = new ArrayList<>(List.of(in("uv", VEC2, uv), in("tex", SAMPLER2D, source.sampler),
                in("grid", VEC2, sheet.grid()), in("fps", FLOAT, sheet.fps()), in("start", FLOAT, sheet.start()),
                in("t", FLOAT, time())));
        var body = new StringBuilder("""
                vec2 g = max(floor(grid), vec2(1.0));
                float span = g.x * g.y;
                float first = 0.0;
                """);
        if (sheet.seed() != null) inputs.add(in("seed", FLOAT, sheet.seed()));
        if (main.randomRow) body.append("span = g.x;\nfirst = floor(fract(seed) * g.y) * g.x;\n");
        boolean once = sheet.frame() != null;
        if (once) {
            inputs.add(in("driven", FLOAT, sheet.frame()));
            body.append("bool once = true;\nfloat frame = clamp(driven, 0.0, 1.0) * span;\n");
        } else {
            body.append("bool once = false;\nfloat frame = t * fps + start;\n");
        }
        if (main.randomStart) body.append("frame += floor(fract(seed * 13.7) * span);\nonce = false;\n");
        body.append("""
                // a driven sheet plays once: it holds the last frame rather than wrapping to the first
                float now = once ? min(floor(frame), span - 1.0) : floor(frame);
                float next = once ? min(now + 1.0, span - 1.0) : now + 1.0;
                """);
        body.append("vec2 cell = vec2(").append(cellWrap(main.texture.wrapU, "uv.x")).append(", ")
                .append(cellWrap(main.texture.wrapV, "uv.y")).append(");\n");
        body.append("""
                float fa = mod(first + mod(now, span), g.x * g.y);
                vec2 a = (cell + vec2(mod(fa, g.x), floor(fa / g.x))) / g;
                """);
        if (main.frameBlend) {
            body.append("""
                    float fb = mod(first + mod(next, span), g.x * g.y);
                    vec2 b = (cell + vec2(mod(fb, g.x), floor(fb / g.x))) / g;
                    float f = fract(frame);
                    """);
            if (sheet.motion() != null) {
                inputs.add(in("motion", SAMPLER2D, sheet.motion()));
                inputs.add(in("strength", FLOAT, sheet.motionStrength()));
                // both frames pushed along their motion so they meet at f instead of crossfading
                body.append("""
                        a -= (texture(motion, a).rg * 2.0 - 1.0) / g * strength * f;
                        b += (texture(motion, b).rg * 2.0 - 1.0) / g * strength * (1.0 - f);
                        """);
            }
            body.append("texel = mix(texture(tex, a), texture(tex, b), f);");
        } else {
            body.append("texel = texture(tex, a);");
        }
        return expr("texel", VEC4, body.toString(), inputs);
    }

    private static String cellWrap(KilaTexture.Wrap wrap, String x) {
        return switch (wrap) {
            case REPEAT -> "fract(" + x + ")";
            case MIRROR -> "1.0 - abs(mod(" + x + ", 2.0) - 1.0)";
            case CLAMP, CLIP -> "clamp(" + x + ", 0.0, 1.0)";
        };
    }

    // ---- the material ------------------------------------------------------------------------------

    private void build() {
        skipUnsupported();
        if (on(m.distortion)) distortion();
        if (on(m.vertexOffset)) vertexOffset();

        w.row("Main Texture");
        PortModel c = on(m.main) ? mainTexture() : expr("white", VEC4, "white = vec4(1.0);");
        if (on(m.layer)) {
            c = layer("Second Layer", m.layer, c, offset("Second Layer Offset", m.layer.offsetU, m.layer.offsetV),
                    flowFor(m.distortion.layer));
        }
        if (on(m.layer2)) c = layer("Third Layer", m.layer2, c, null, null);
        if (on(m.ramp)) c = ramp(c);

        w.row("Surface");
        c = expr("tinted", VEC4, "tinted = c * tint * modulator;", in("c", VEC4, c), in("tint", VEC4, color("Color", m.color)),
                in("modulator", VEC4, out(w.node(DynamicTransformsUboNode.class), "ColorModulator")));
        if (!m.backColor.equals(HDRColor.white())) {
            c = expr("faced", VEC4, "faced = front ? c : vec4(c.rgb * back.rgb, c.a);", in("c", VEC4, c),
                    in("front", GlslType.BOOL, out(w.node(FrontFacingNode.class), "out")),
                    in("back", VEC4, color("Back Color", m.backColor)));
        }
        c = switch (m.vertexColor) {
            case MULTIPLY -> expr("colored", VEC4, "colored = c * vc;", in("c", VEC4, c), in("vc", VEC4, vertexColor()));
            case ALPHA_ONLY -> expr("colored", VEC4, "colored = vec4(c.rgb, c.a * vc.a);", in("c", VEC4, c), in("vc", VEC4, vertexColor()));
            case RGB_ONLY -> expr("colored", VEC4, "colored = vec4(c.rgb * vc.rgb, c.a);", in("c", VEC4, c), in("vc", VEC4, vertexColor()));
            case IGNORE -> c;
        };
        // the scene behind is lit and fogged already
        boolean sceneShown = on(m.screenDistortion);
        c = sceneShown ? screen(c) : light(c);
        w.row("Intensity");
        c = expr("glowing", VEC4, "glowing = vec4(c.rgb * intensity + emission.rgb, c.a);", in("c", VEC4, c),
                in("intensity", FLOAT, driver("Intensity", m.intensity)), in("emission", VEC4, color("Emission", m.emission)));
        if (on(m.matcap)) c = matcap(c);

        if (on(m.mask1)) c = mask("Mask 1", m.mask1, c, flowFor(m.distortion.mask1));
        if (on(m.mask2)) c = mask("Mask 2", m.mask2, c, flowFor(m.distortion.mask2));
        if (on(m.dissolve)) c = dissolve(c);
        if (on(m.fresnel)) c = fresnel(c);
        if (on(m.colorAdjust)) c = colorAdjust(c);

        w.row("Output");
        var alpha = expr("shaped", FLOAT, "shaped = clamp(pow(clamp(a, 0.0, 1.0), max(power, 1e-3)) * alpha, 0.0, 1.0);",
                in("a", FLOAT, split(c, "a")), in("power", FLOAT, number("Alpha Power", m.alphaSharpen)),
                in("alpha", FLOAT, driver("Alpha", m.alpha)));
        c = expr("clipped", VEC4, "if (a < cutoff) discard;\nclipped = vec4(c.rgb, a);", in("c", VEC4, c),
                in("a", FLOAT, alpha), in("cutoff", FLOAT, number("Alpha Clip", m.alphaClip)));
        // fades act on alpha after the fog: fogging an already faded fragment would bring the fog colour back
        if (m.fog && !sceneShown) {
            var fog = w.node(ApplyFogNode.class);
            w.wire(fog, "inColor", c);
            c = out(fog, "out");
        }
        if (on(m.depth) && m.depth.soft) {
            var fade = w.node(DepthFadeNode.class);
            w.wire(fade, "distance", number("Soft Distance", m.depth.distance));
            c = expr("softened", VEC4, "softened = vec4(c.rgb, c.a * pow(fade, max(power, 1e-5)));", in("c", VEC4, c),
                    in("fade", FLOAT, out(fade, "fade")), in("power", FLOAT, number("Soft Power", m.depth.power)));
        }
        if (on(m.cameraFade)) {
            c = expr("faded", VEC4, "faded = vec4(c.rgb, c.a * clamp((length(p) - near) / max(range, 1e-4), 0.0, 1.0));",
                    in("c", VEC4, c), in("p", VEC3, viewPos()), in("near", FLOAT, number("Camera Fade Start", m.cameraFade.near)),
                    in("range", FLOAT, number("Camera Fade Range", m.cameraFade.range)));
        }
        if (!m.slotRenderState) c = blendOutput(c);

        var stage = graph.getFragmentStageModel();
        w.attach(stage);
        w.wire(w.block(stage, FragmentBaseColorBlock.class), "color",
                expr("rgb", VEC3, "rgb = c.rgb;", in("c", VEC4, c)));
        w.wire(w.block(stage, FragmentAlphaBlock.class), "alpha", split(c, "a"));
    }

    @Nullable
    private PortModel flowFor(boolean target) {
        return target ? flow : null;
    }

    private void skipUnsupported() {
        if (on(m.projection)) skipped.add(m.projection.langKey());
        if (on(m.volume)) skipped.add(m.volume.langKey());
        if (on(m.shading) && m.shading.model == KilaShading.Model.SIX_WAY) skipped.add("kila.export.skipped.six_way");
        if (on(m.depth) && (m.depth.contactWidth.isDriven() || m.depth.contactWidth.value > 0)) {
            skipped.add("kila.export.skipped.contact_glow");
        }
        if (m.cameraOffset != 0) skipped.add("kila.export.skipped.camera_offset");
        if (on(m.screenDistortion) && m.screenDistortion.stack) skipped.add("kila.export.skipped.stacked");
    }

    private PortModel layer(String name, KilaLayer layer, PortModel c, @Nullable PortModel offset, @Nullable PortModel flow) {
        w.row(name);
        var texel = colorSlot(name, layer.texture, offset, flow);
        String blended = switch (layer.blend) {
            case MULTIPLY -> "c * layer";
            // add and screen keep alpha: an opaque layer must not fill the quad
            case ADD -> "vec4(c.rgb + layer.rgb * layer.a, c.a)";
            case LERP -> "vec4(mix(c.rgb, layer.rgb, layer.a), max(c.a, layer.a))";
            case SCREEN -> "vec4(1.0 - (1.0 - c.rgb) * (1.0 - layer.rgb * layer.a), c.a)";
        };
        return expr("blended", VEC4, "vec4 b = " + blended + ";\nfloat k = clamp(strength, 0.0, 1.0);\nblended = vec4("
                        + (layer.affectsColor ? "mix(c.rgb, b.rgb, k)" : "c.rgb") + ", "
                        + (layer.affectsAlpha ? "mix(c.a, b.a, k)" : "c.a") + ");",
                in("c", VEC4, c), in("layer", VEC4, texel), in("strength", FLOAT, driver(name + " Strength", layer.strength)));
    }

    private PortModel ramp(PortModel c) {
        var ramp = m.ramp;
        w.row("Color Ramp");
        String input = switch (ramp.input) {
            case LUMINANCE -> "dot(c.rgb, " + LUMA + ")";
            case RED -> "c.r";
            case ALPHA -> "c.a";
            case U -> "uv.x";
            case V -> "uv.y";
        };
        var x = expr("x", FLOAT, "x = clamp(" + input + " + offset, 0.0, 1.0);", in("c", VEC4, c),
                in("uv", VEC2, meshUv()), in("offset", FLOAT, driver("Ramp Offset", ramp.offset)));
        var sample = w.node(SampleGradientNode.class);
        w.wire(sample, "gradient", gradient("Ramp", ramp.gradient));
        w.wire(sample, "time", x);
        String mode = switch (ramp.mode) {
            case REPLACE -> "vec4(r.rgb, c.a)";
            case MULTIPLY -> "vec4(c.rgb * r.rgb, c.a)";
            case REPLACE_ALPHA -> "vec4(r.rgb, c.a * r.a)";
        };
        return expr("ramped", VEC4, "vec4 r = vec4(ramp.rgb * intensity, ramp.a);\nramped = " + mode + ";",
                in("c", VEC4, c), in("ramp", VEC4, out(sample, "color")),
                in("intensity", FLOAT, number("Ramp Intensity", ramp.intensity)));
    }

    // ---- light -------------------------------------------------------------------------------------

    /** The view-space normal the surface lights, rims and reflects by: the shaded one, else the mesh's. */
    private PortModel normal() {
        if (normal == null) normal = on(m.shading) ? w.aside("Shading Normal", this::shadingNormal) : viewNormal();
        return normal;
    }

    /** The quad's frame from derivatives, the sphere it bulges into, then the bump. */
    private PortModel shadingNormal() {
        var s = m.shading;
        var inputs = new ArrayList<>(List.of(in("p", VEC3, viewPos()), in("meshNormal", VEC3, viewNormal()),
                in("uv", VEC2, rawUv()), in("front", GlslType.BOOL, out(w.node(FrontFacingNode.class), "out"))));
        var body = new StringBuilder("""
                vec3 dpx = dFdx(p);
                vec3 dpy = dFdy(p);
                vec2 dux = dFdx(uv);
                vec2 duy = dFdy(uv);
                float det = dux.x * duy.y - duy.x * dux.y;
                vec3 t = vec3(1.0, 0.0, 0.0);
                vec3 b = vec3(0.0, -1.0, 0.0);
                bool framed = false;
                if (abs(det) >= 1e-12) {
                    t = (dpx * duy.y - dpy * dux.y) / det;
                    b = (dpy * dux.x - dpx * duy.x) / det;
                    framed = length(cross(t, b)) > 1e-8;
                }
                vec3 mesh = normalize(meshNormal);
                if (!front) mesh = -mesh;
                vec3 tangent = normalize(t);
                vec3 bitangent = normalize(b);
                vec3 face = framed ? normalize(cross(t, b)) : mesh;
                if (dot(face, -p) < 0.0) face = -face;
                vec3 n = mesh;
                """);
        if (s.normal == KilaShading.Normal.SPHERE) {
            inputs.add(in("bulge", FLOAT, number("Sphere Bulge", s.bulge)));
            body.append("""
                    vec2 q = uv * 2.0 - 1.0;
                    vec3 sphere = normalize(tangent * q.x + bitangent * q.y + face * sqrt(max(1.0 - dot(q, q), 0.0)));
                    n = normalize(mix(face, sphere, clamp(bulge, 0.0, 1.0)));
                    """);
        }
        if (s.bump == KilaShading.Bump.HEIGHT) {
            var height = new Source("Bump", s.height);
            inputs.add(in("h", FLOAT, height.value(slotUv("Bump", s.height, null))));
            inputs.add(in("strength", FLOAT, number("Bump Strength", s.bumpStrength)));
            // Mikkelsen's derivative bump mapping: no tangents needed
            body.append("""
                    float hh = h * strength * 0.1;
                    vec3 r1 = cross(dpy, n);
                    vec3 r2 = cross(n, dpx);
                    float d = dot(dpx, r1);
                    n = normalize(abs(d) * n - sign(d) * (dFdx(hh) * r1 + dFdy(hh) * r2));
                    """);
        } else if (s.bump == KilaShading.Bump.NORMAL_MAP) {
            var map = new Source("Normal Map", s.normalMap);
            inputs.add(in("m", VEC4, map.texel(slotUv("Normal Map", s.normalMap, null))));
            inputs.add(in("strength", FLOAT, number("Bump Strength", s.bumpStrength)));
            body.append("vec3 mm = m.rgb * 2.0 - 1.0;\n");
            if (s.flipGreen) body.append("mm.y = -mm.y;\n");
            // green points up the texture, against v
            body.append("mm.xy *= strength;\nn = normalize(tangent * mm.x - bitangent * mm.y + n * max(mm.z, 1e-3));\n");
        }
        body.append("shaded = n;");
        return expr("shaded", VEC3, body.toString(), inputs);
    }

    /** The world light on the surface colour, the key light and the dynamic lights; what is added later glows in the dark. */
    private PortModel light(PortModel c) {
        boolean shaded = on(m.shading);
        if (!m.worldLight && !shaded && !on(m.lit)) return c;
        w.row("Light");
        var surface = c;
        if (m.worldLight) {
            c = expr("lightmapped", VEC4, "lightmapped = vec4(c.rgb * light.rgb, c.a);", in("c", VEC4, c),
                    in("light", VEC4, out(w.node(ParticleDataNode.class), "light")));
        }
        if (shaded) {
            var s = m.shading;
            c = expr("keyed", VEC4, """
                            vec3 l = mat3(view) * direction;
                            if (dot(l, l) > 1e-8) l = normalize(l);
                            float weight = clamp((dot(n, l) + wrap) / (1.0 + wrap), 0.0, 1.0);
                            keyed = vec4(c.rgb * key.rgb * mix(ambient, 1.0, weight), c.a);""",
                    in("c", VEC4, c), in("n", VEC3, normal()), in("view", GlslType.MAT4, modelView()),
                    in("direction", VEC3, vector("Key Light Direction", s.lightDirection)),
                    in("key", VEC4, color("Key Light Color", s.lightColor)), in("wrap", FLOAT, number("Light Wrap", s.wrap)),
                    in("ambient", FLOAT, number("Ambient", s.ambient)));
        }
        if (on(m.lit)) {
            var lights = w.node(DynamicLightNode.class);
            if (shaded) {
                // the node takes a world normal
                w.wire(lights, "normal", expr("worldNormal", VEC3, "worldNormal = transpose(mat3(view)) * n;",
                        in("n", VEC3, normal()), in("view", GlslType.MAT4, modelView())));
                GraphWriter.constant(lights, "wrap", m.shading.wrap);
            }
            c = expr("lit", VEC4, "lit = vec4(c.rgb + surface.rgb * light * intensity, c.a);", in("c", VEC4, c),
                    in("surface", VEC4, surface), in("light", VEC3, out(lights, "light")),
                    in("intensity", FLOAT, number("Light Intensity", m.lit.intensity)));
        }
        return c;
    }

    private PortModel matcap(PortModel c) {
        var cap = m.matcap;
        w.row("Matcap");
        var source = new Source("Matcap", cap.texture);
        // v runs down the texture, so the normal's y is flipped
        var texel = source.texel(expr("capUv", VEC2, "capUv = vec2(n.x, -n.y) * 0.5 + 0.5;", in("n", VEC3, normal())));
        String blend = switch (cap.blend) {
            case ADD -> "c.rgb + cap.rgb * strength";
            case MULTIPLY -> "c.rgb * mix(vec3(1.0), cap.rgb, strength)";
            case REPLACE -> "mix(c.rgb, cap.rgb, clamp(strength, 0.0, 1.0))";
        };
        return expr("capped", VEC4, "capped = vec4(" + blend + ", c.a);", in("c", VEC4, c), in("cap", VEC4, texel),
                in("strength", FLOAT, number("Matcap Strength", cap.strength)));
    }

    // ---- distortions -------------------------------------------------------------------------------

    /** The scene behind, pushed by the distortion: what a screen distortion shows instead of a lit surface. */
    private PortModel screen(PortModel c) {
        var screen = m.screenDistortion;
        w.row("Screen Distortion");
        var strength = driver("Screen Strength", screen.strength);
        PortModel push;
        if (screen.source == KilaScreenDistortion.Source.NORMAL) {
            push = expr("push", VEC2, "push = n.xy * strength;", in("n", VEC3, normal()), in("strength", FLOAT, strength));
        } else {
            var source = new Source("Screen", screen.texture);
            push = expr("push", VEC2, "push = (n * 2.0 - 1.0) * strength;",
                    in("n", VEC2, source.pair(slotUv("Screen", screen.texture, null))), in("strength", FLOAT, strength));
        }
        var here = screenUv();
        if (screen.keepForeground) {
            var depth = w.node(SceneDepthNode.class);
            GraphWriter.option(depth, "sampling", "eye");
            w.wire(depth, "uv", at(here, push, null));
            // don't pull in what stands in front of the particle
            push = expr("kept", VEC2, "kept = push * clamp((there + p.z) / 0.25, 0.0, 1.0);", in("push", VEC2, push),
                    in("there", FLOAT, out(depth, "out")), in("p", VEC3, viewPos()));
        }
        PortModel scene;
        if (screen.chromatic != 0) {
            var fringe = number("Color Fringe", screen.chromatic);
            scene = expr("scene", VEC3, "scene = vec3(r.r, g.g, b.b);",
                    in("r", VEC3, sceneAt(at(here, push, "(1.0 + fringe)", in("fringe", FLOAT, fringe)))),
                    in("g", VEC3, sceneAt(at(here, push, null))),
                    in("b", VEC3, sceneAt(at(here, push, "(1.0 - fringe)", in("fringe", FLOAT, fringe)))));
        } else {
            scene = sceneAt(at(here, push, null));
        }
        return expr("shown", VEC4, "shown = vec4(c.rgb * scene, c.a);", in("c", VEC4, c), in("scene", VEC3, scene));
    }

    private PortModel at(PortModel here, PortModel push, @Nullable String scale, GraphWriter.Port... more) {
        var inputs = new ArrayList<>(List.of(in("here", VEC2, here), in("push", VEC2, push)));
        inputs.addAll(List.of(more));
        return expr("at", VEC2, "at = here + push" + (scale == null ? "" : " * " + scale) + ";", inputs);
    }

    private PortModel sceneAt(PortModel uv) {
        var node = w.node(SceneColorNode.class);
        w.wire(node, "uv", uv);
        return out(node, "out");
    }

    /** The uv offset, or with a flow map how far one cycle carries the uv. */
    private void distortion() {
        var d = m.distortion;
        w.row("UV Distortion");
        var n = new Source("Distortion", d.texture).pair(slotUv("Distortion", d.texture, null));
        String mask = switch (d.mask) {
            case NONE -> "1.0";
            case U -> "uv.x";
            case V -> "uv.y";
            case RADIAL -> "(1.0 - clamp(length(uv - 0.5) * 2.0, 0.0, 1.0))";
        };
        var inputs = new ArrayList<>(List.of(in("n", VEC2, n), in("amount", VEC2, vector("Distortion Amount", d.amount)),
                in("strength", FLOAT, driver("Distortion Strength", d.strength))));
        if (d.mask != KilaDistortion.Mask.NONE) inputs.add(in("uv", VEC2, meshUv()));
        flow = expr("flow", VEC2, (d.bidirectional ? "vec2 m = n * 2.0 - 1.0;" : "vec2 m = n;")
                + "\nflow = m * amount * strength * " + mask + ";", inputs);
        if (flowMap) {
            flowPhase = expr("phase", VEC3, "float p = fract(t * speed);\nphase = vec3(p, fract(p + 0.5), abs(1.0 - 2.0 * p));",
                    in("t", FLOAT, time()), in("speed", FLOAT, number("Flow Speed", d.flowSpeed)));
        }
    }

    private PortModel mask(String name, KilaMask mask, PortModel c, @Nullable PortModel flow) {
        w.row(name);
        var value = valueSlot(name, mask.texture, offset(name + " Offset", mask.offsetU, mask.offsetV), flow);
        return expr("masked", VEC4, "float f = pow(clamp(v, 0.0, 1.0), max(power, 1e-3));\n"
                        + (mask.invert ? "f = 1.0 - f;\n" : "")
                        + "f = mix(1.0, f, clamp(strength, 0.0, 1.0));\n"
                        + "masked = vec4(" + (mask.affectsColor ? "c.rgb * f" : "c.rgb") + ", c.a * f);",
                in("c", VEC4, c), in("v", FLOAT, value), in("strength", FLOAT, number(name + " Strength", mask.strength)),
                in("power", FLOAT, number(name + " Power", mask.power)));
    }

    private PortModel dissolve(PortModel c) {
        var d = m.dissolve;
        w.row("Dissolve");
        var n = valueSlot("Dissolve", d.texture, offset("Dissolve Offset", d.offsetU, d.offsetV), flowFor(m.distortion.dissolve));
        var softness = number("Dissolve Softness", d.softness);
        var edgeWidth = driver("Dissolve Edge Width", d.edgeWidth);
        var inputs = new ArrayList<>(List.of(in("n", FLOAT, n), in("sharpen", FLOAT, number("Dissolve Sharpen", d.sharpen)),
                in("progress", FLOAT, driver("Dissolve Progress", d.progress)), in("softness", FLOAT, softness),
                in("edgeWidth", FLOAT, edgeWidth)));
        var body = new StringBuilder("value = pow(clamp(n, 0.0, 1.0), max(sharpen, 1e-3));\n");
        if (d.direction != KilaDissolve.Direction.NONE) {
            String g = switch (d.direction) {
                case U -> "uv.x";
                case V -> "uv.y";
                case RADIAL -> "clamp(length(uv - 0.5) * 2.0, 0.0, 1.0)";
                case TEXTURE, NONE -> "sweep";
            };
            if (d.direction == KilaDissolve.Direction.TEXTURE) {
                var tiling = w.node(TilingAndOffsetNode.class);
                w.wire(tiling, "uv", meshUv());
                w.wire(tiling, "tiling", vector("Dissolve Direction Tiling", d.directionTexture.tiling));
                w.wire(tiling, "offset", vector("Dissolve Direction Offset", d.directionTexture.offset));
                var source = new Source("Dissolve Direction", d.directionTexture);
                inputs.add(in("sweep", FLOAT, channel(source.texel(out(tiling, "out")), d.directionTexture.channel)));
            } else {
                inputs.add(in("uv", VEC2, meshUv()));
            }
            inputs.add(in("weight", FLOAT, number("Dissolve Direction Weight", d.directionWeight)));
            body.append("float g = ").append(g).append(";\n");
            if (d.directionInvert) body.append("g = 1.0 - g;\n");
            body.append("value = mix(value, clamp(g, 0.0, 1.0), clamp(weight, 0.0, 1.0));\n");
        }
        // the cut sweeps from -(soft + edge) to 1: progress 0 is whole and 1 is gone at any width
        body.append("""
                float soft = max(softness, 1e-4);
                float edge = max(edgeWidth, 0.0);
                cut = mix(-(soft + edge), 1.0, clamp(progress, 0.0, 1.0));
                band = clamp((value - cut - soft) / max(edge, 1e-6), 0.0, 1.0);""");
        var cut = w.expression(inputs, List.of(GraphWriter.Port.out("value", FLOAT), GraphWriter.Port.out("cut", FLOAT),
                GraphWriter.Port.out("band", FLOAT)), body.toString());

        var finish = new ArrayList<>(List.of(in("c", VEC4, c), in("v", FLOAT, out(cut, "value")), in("cut", FLOAT, out(cut, "cut")),
                in("softness", FLOAT, softness), in("edgeWidth", FLOAT, edgeWidth),
                in("edgeColor", VEC4, color("Dissolve Edge Color", d.edgeColor))));
        var edge = new StringBuilder("""
                float soft = max(softness, 1e-4);
                float width = max(edgeWidth, 0.0);
                if (width > 0.0) {
                    float edge = 1.0 - smoothstep(cut + soft, cut + soft + width, v);
                    vec3 tint = edgeColor.rgb;
                """);
        if (d.edgeGradient) {
            var sample = w.node(SampleGradientNode.class);
            w.wire(sample, "gradient", gradient("Dissolve Edge Gradient", d.edgeGradientColors));
            w.wire(sample, "time", out(cut, "band"));
            finish.add(in("ramp", VEC4, out(sample, "color")));
            // across the band: left at the burnt side, right at the intact side; its alpha fades the band
            edge.append("    tint *= ramp.rgb;\n    edge = (v < cut + soft + width ? 1.0 : 0.0) * ramp.a;\n");
        }
        if (d.edgeTint) {
            finish.add(in("vc", VEC4, vertexColor()));
            edge.append("    tint *= vc.rgb;\n");
        }
        edge.append(d.edge == KilaDissolve.Edge.REPLACE ? "    c.rgb = mix(c.rgb, tint, edge);\n" : "    c.rgb += tint * edge;\n");
        edge.append("}\nc.a *= smoothstep(cut, cut + soft, v);\ndissolved = c;");
        return expr("dissolved", VEC4, edge.toString(), finish);
    }

    private PortModel fresnel(PortModel c) {
        var f = m.fresnel;
        w.row("Fresnel");
        String apply = switch (f.mode) {
            case RIM -> "c.rgb += rim.rgb * f * k;";
            case FADE_EDGES -> "c.a *= mix(1.0, 1.0 - f, k);";
            case FADE_CENTER -> "c.a *= mix(1.0, f, k);";
        };
        var inputs = new ArrayList<>(List.of(in("c", VEC4, c), in("n", VEC3, normal()), in("p", VEC3, viewPos()),
                in("lean", VEC3, vector("Fresnel Offset", f.offset)), in("power", FLOAT, number("Fresnel Power", f.power)),
                in("scale", FLOAT, number("Fresnel Scale", f.scale)), in("strength", FLOAT, driver("Fresnel Strength", f.strength))));
        if (f.mode == KilaFresnel.Mode.RIM) inputs.add(in("rim", VEC4, color("Fresnel Color", f.color)));
        return expr("rimmed", VEC4, """
                vec3 v = normalize(normalize(-p) + lean);
                float f = clamp(scale * pow(1.0 - abs(dot(n, v)), max(power, 1e-3)), 0.0, 1.0);
                float k = clamp(strength, 0.0, 1.0);
                """ + apply + "\nrimmed = c;", inputs);
    }

    private PortModel colorAdjust(PortModel c) {
        var a = m.colorAdjust;
        w.row("Color Adjust");
        return expr("adjusted", VEC4, """
                        vec3 rgb = c.rgb;
                        // hue: a rotation about the grey axis
                        const vec3 grey = vec3(0.57735027);
                        float cosine = cos(hue);
                        rgb = rgb * cosine + cross(grey, rgb) * sin(hue) + grey * dot(grey, rgb) * (1.0 - cosine);
                        rgb = mix(vec3(dot(rgb, vec3(0.2126, 0.7152, 0.0722))), rgb, saturation);
                        rgb = (rgb - 0.5) * contrast + 0.5;
                        rgb *= brightness;
                        if (levels >= 2.0) rgb = floor(rgb * (levels - 1.0) + 0.5) / (levels - 1.0);
                        adjusted = vec4(max(rgb, vec3(0.0)), c.a);""",
                in("c", VEC4, c), in("hue", FLOAT, number("Hue", (float) Math.toRadians(a.hue))),
                in("saturation", FLOAT, number("Saturation", a.saturation)), in("contrast", FLOAT, number("Contrast", a.contrast)),
                in("brightness", FLOAT, number("Brightness", a.brightness)), in("levels", FLOAT, number("Posterize", a.posterize)));
    }

    /** What the blend preset asks of the colour: premultiplied, pulled towards white, opaque. */
    private PortModel blendOutput(PortModel c) {
        return switch (m.blend) {
            case PREMULTIPLIED -> expr("blended", VEC4, "blended = vec4(c.rgb * c.a, c.a * (1.0 - clamp(additive, 0.0, 1.0)));",
                    in("c", VEC4, c), in("additive", FLOAT, number("Additive", m.additive)));
            case MULTIPLY -> expr("blended", VEC4, "blended = vec4(mix(vec3(1.0), c.rgb, c.a), c.a);", in("c", VEC4, c));
            case OPAQUE -> expr("blended", VEC4, "blended = vec4(c.rgb, 1.0);", in("c", VEC4, c));
            case ALPHA, ADDITIVE -> c;
        };
    }

    // ---- the vertex stage --------------------------------------------------------------------------

    /**
     * The vertices pushed along the normal or an axis by a texture or noise, read at the mesh uv as it arrives: the
     * vertex stage has no screen, and no boil or uv effects. Built from nodes of its own, none shared with the
     * fragment stage.
     */
    private void vertexOffset() {
        var v = m.vertexOffset;
        w.row("Vertex Offset");
        var kept = new PortModel[]{rawTime, time, rawUv, world, vertexColor};
        rawTime = time = rawUv = world = vertexColor = null;
        try {
            var data = w.node(ParticleDataNode.class);
            var t = v.texture;
            var uv = slotUv("Vertex Offset", t, null, switch (t.uvSource) {
                case MESH, SCREEN -> rawUv();
                case WORLD_XZ, WORLD_XY, WORLD_ZY -> plane(t.uvSource, world());
            });
            var inputs = new ArrayList<>(List.of(in("p", VEC3, out(data, "position")), in("uv", VEC2, uv),
                    in("meshUv", VEC2, rawUv()), in("amount", FLOAT, driver("Vertex Offset", v.strength))));
            var body = new StringBuilder();
            if (t.isProcedural()) {
                inputs.add(in("value", FLOAT, new Source("Vertex Offset", t).noise(uv)));
                body.append("float push = value;\n");
            } else {
                var weights = new float[4];
                t.channel.weights(weights);
                inputs.add(in("tex", SAMPLER2D, new Source("Vertex Offset", t).sampler));
                body.append("float push = dot(textureLod(tex, uv, 0.0), ").append(vec4(weights)).append(");\n");
            }
            if (v.bidirectional) body.append("push = push * 2.0 - 1.0;\n");
            if (v.edgeFade > 0) {
                inputs.add(in("fade", FLOAT, number("Vertex Offset Edge Fade", v.edgeFade)));
                body.append("push *= smoothstep(0.0, fade, meshUv.y) * smoothstep(0.0, fade, 1.0 - meshUv.y);\n");
            }
            if (v.direction == KilaVertexOffset.Direction.AXIS) {
                inputs.add(in("direction", VEC3, vector("Vertex Offset Axis", v.axis)));
            } else {
                inputs.add(in("direction", VEC3, out(data, "normal")));
            }
            body.append("displaced = p + direction * push * amount;");
            w.attach(graph.getVertexStageModel());
            var block = w.block(graph.getVertexStageModel(), VertexModelPositionBlock.class);
            w.wire(block, "position", expr("displaced", VEC3, body.toString(), inputs));
        } finally {
            rawTime = kept[0];
            time = kept[1];
            rawUv = kept[2];
            world = kept[3];
            vertexColor = kept[4];
        }
    }
}
