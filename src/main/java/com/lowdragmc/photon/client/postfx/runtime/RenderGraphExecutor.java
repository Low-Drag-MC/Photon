package com.lowdragmc.photon.client.postfx.runtime;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.runtime.RenderTypeGraphMaterial;
import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.postfx.shadergraph.PhotonFullscreenCompiler;
import com.lowdragmc.photon.client.postfx.shadergraph.runtime.FullscreenGraphRuntime;
import com.lowdragmc.photon.client.render.PhotonEngineUniforms;
import com.lowdragmc.photon.client.render.PhotonFullscreenPass;
import com.lowdragmc.photon.client.render.PhotonSceneCapture;
import com.lowdragmc.photon.client.render.PhotonTime;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL46;
import org.lwjgl.opengl.GL;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Executes one {@link CompiledEffect} invocation: resolves the transient resource sizes, then per pass —
 * acquire the output target from the pool, stage builtins + blended params + texture inputs (with their
 * {@code _TexelSize} companions), dispatch a fullscreen draw, and release inputs at their last use.
 * A broken pass skips the whole effect (chain passthrough), remembered per path so it logs once.
 *
 * <p>Two pass flavors share the loop: a hand-written core shader ({@link CustomShaderPass}, values in a
 * {@code PhotonPass} std140 block) and a compiled fullscreen graph (KilaGraph's own material UBO +
 * samplers). Both draw the shared {@code [0,1]²} quad through {@link PhotonFullscreenPass}.</p>
 *
 * <p>Caller contract: the returned target is pool-owned by the CALLER (release it or hand it on); null
 * means the chain must pass through unchanged. Render thread only, and every UBO upload / texture
 * resolve happens BETWEEN passes — both are illegal once a render pass is open.</p>
 */
public final class RenderGraphExecutor {

    /** Effect paths whose failure was already logged — cleared when the effect next succeeds. */
    private static final Set<String> LOGGED_FAILURES = new HashSet<>();

    /** The ONE Minecraft builtin block a graph pass dispatch binds (see {@link #dispatchGraph}). Anything
     *  else {@code PhotonPipelines.fullscreenGraph} declares would be a uniform nothing fills, which 26.1
     *  rejects at draw — {@code FullscreenGraphPassGameTest} holds that contract. */
    public static final String BOUND_BUILTIN_UNIFORM = "DynamicTransforms";

    // The neutral DynamicTransforms a fullscreen graph pass binds (see dispatchGraph). Shared instances
    // are safe: writeTransform copies every one of them into the buffer.
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Vector4f NO_MODULATION = new Vector4f(1, 1, 1, 1);
    private static final Vector3f NO_OFFSET = new Vector3f();

    private RenderGraphExecutor() {}

    /**
     * What the frame offers a pass beyond its own transient targets.
     *
     * @param sceneDepth the depth SCENE_DEPTH inputs read (null = unavailable, e.g. the standalone path)
     * @param maskColor  the CustomMask color texture (null = no mask this frame)
     * @param maskDepth  the mask target's own depth (null = no mask this frame)
     */
    public record FrameInputs(GpuTextureView sceneColor, int width, int height,
                              @Nullable GpuTextureView sceneDepth,
                              @Nullable GpuTextureView maskColor,
                              @Nullable GpuTextureView maskDepth) {

        public static FrameInputs of(GpuTextureView sceneColor, @Nullable GpuTextureView sceneDepth) {
            return new FrameInputs(sceneColor, sceneColor.texture().getWidth(0),
                    sceneColor.texture().getHeight(0), sceneDepth, null, null);
        }

        /** The same inputs with a different chain colour — each effect runs over the PREVIOUS one's output. */
        public FrameInputs withSceneColor(GpuTextureView color) {
            return new FrameInputs(color, width, height, sceneDepth, maskColor, maskDepth);
        }

        private FrameInputs withSceneDepth(@Nullable GpuTextureView depth) {
            return new FrameInputs(sceneColor, width, height, depth, maskColor, maskDepth);
        }

        public FrameInputs withMask(@Nullable GpuTextureView color, @Nullable GpuTextureView depth) {
            return new FrameInputs(sceneColor, width, height, sceneDepth, color, depth);
        }

        /**
         * The scene depth in a form a pass may actually sample. A render target's depth view is not
         * necessarily bindable as a texture — the editor's PIP depth is declared without
         * {@code USAGE_TEXTURE_BINDING}, and binding it throws inside the draw — so those are routed
         * through Photon's own capture instead. The world's main depth is bindable and passes straight
         * through, which is why this is not done unconditionally.
         */
        public FrameInputs withSampleableDepth() {
            if (sceneDepth == null
                    || (sceneDepth.texture().usage() & GpuTexture.USAGE_TEXTURE_BINDING) != 0) {
                return this;
            }
            return withSceneDepth(PhotonSceneCapture.captureDepth(sceneDepth));
        }
    }

    /** The effect's transient targets, sized once up front and aliased through the pool as passes retire. */
    private record Resources(PostFXTargetPool.Target[] targets, int[] widths, int[] heights) {}

    /**
     * Run {@code effect} over {@code inputs}. Returns the pooled output target, or null when any pass
     * failed to resolve/compile or a needed input was unavailable — the chain passes through unchanged.
     */
    @Nullable
    public static PostFXTargetPool.Target execute(CompiledEffect effect, float weight,
                                                  Map<String, Object> params, FrameInputs inputs) {
        if (effect.passes().isEmpty()) return null; // no-op effect: chain passthrough
        var resources = sizeResources(effect, inputs);

        boolean debugGroup = Platform.isDevEnv() && GL.getCapabilities().GL_KHR_debug;
        if (debugGroup) {
            GL46.glPushDebugGroup(GL46.GL_DEBUG_SOURCE_APPLICATION, 0, "photonfx:" + sourceName(effect));
        }
        var targets = resources.targets();
        boolean succeeded = false;
        try {
            for (int passIndex = 0; passIndex < effect.passes().size(); passIndex++) {
                var pass = effect.passes().get(passIndex);
                int out = pass.outputResource();
                if (targets[out] == null) {
                    targets[out] = PostFXTargetPool.acquire(resources.widths()[out], resources.heights()[out],
                            effect.resources().get(out).format());
                    if (targets[out] == null) {
                        logFailureOnce(effect, "no %dx%d %s target available".formatted(
                                resources.widths()[out], resources.heights()[out],
                                effect.resources().get(out).format()));
                        return null;
                    }
                }
                boolean dispatched = pass.customShader() != null
                        ? dispatchCustomShader(effect, pass, weight, params, resources, inputs)
                        : dispatchGraph(effect, pass, weight, params, resources, inputs);
                if (!dispatched) return null;

                // aliasing: anything last consumed by this pass goes straight back to the pool
                for (int resource = 0; resource < targets.length; resource++) {
                    if (resource != effect.outputResource() && targets[resource] != null
                            && effect.resources().get(resource).lastUsePass() == passIndex) {
                        PostFXTargetPool.release(targets[resource]);
                        targets[resource] = null;
                    }
                }
            }
            var result = targets[effect.outputResource()];
            targets[effect.outputResource()] = null;
            succeeded = true;
            return result;
        } finally {
            for (var target : targets) {
                PostFXTargetPool.release(target);
            }
            if (debugGroup) GL46.glPopDebugGroup();
            // sourceKey() allocates; the set is empty in the normal case, so don't pay for it per frame
            if (succeeded && !LOGGED_FAILURES.isEmpty()) LOGGED_FAILURES.remove(sourceKey(effect));
        }
    }

    private static Resources sizeResources(CompiledEffect effect, FrameInputs inputs) {
        int count = effect.resources().size();
        var widths = new int[count];
        var heights = new int[count];
        for (int i = 0; i < count; i++) {
            var size = effect.resources().get(i).size();
            switch (size.mode()) {
                case SCREEN_RELATIVE -> {
                    widths[i] = Math.max(1, Math.round(inputs.width() * size.scale()));
                    heights[i] = Math.max(1, Math.round(inputs.height() * size.scale()));
                }
                case INPUT_RELATIVE -> {
                    // the graph compiler guarantees the referenced resource resolves earlier in the list
                    widths[i] = Math.max(1, Math.round(widths[size.inputResource()] * size.scale()));
                    heights[i] = Math.max(1, Math.round(heights[size.inputResource()] * size.scale()));
                }
                case ABSOLUTE -> {
                    widths[i] = Math.max(1, size.width());
                    heights[i] = Math.max(1, size.height());
                }
            }
        }
        return new Resources(new PostFXTargetPool.Target[count], widths, heights);
    }

    // ---- pass flavors ---------------------------------------------------------------------------

    /** One texture input resolved to what a pass actually binds. {@code width}/{@code height} feed the
     *  sampler's {@code _TexelSize} companion; 0 means "unknown", leaving it at its default.
     *  {@code point} = sample it unfiltered (see {@link #samplerFor}). {@code sampler} overrides both,
     *  and is only set for external textures, whose {@code Sampler2DValue} carries its own filter/wrap. */
    private record BoundInput(GpuTextureView view, int width, int height, boolean point,
                              @Nullable GpuSampler sampler) {
        BoundInput(GpuTextureView view, int width, int height, boolean point) {
            this(view, width, height, point, null);
        }
    }

    /**
     * The CustomMask is the one input that must never be filtered: its red channel holds a group
     * <b>id</b>, not a quantity, and the consumers compare it against an integer
     * ({@code texture(...).r * 255} vs {@code MaskFilter}). Interpolating across the boundary between
     * group 3 and the empty background sweeps continuously through 1 and 2 — ids nobody ever wrote —
     * so a filter set for group 1 would match a halo around every group-3 object. Colour and depth are
     * quantities and interpolate meaningfully (a half-res pass genuinely wants the smooth read).
     */
    private static GpuSampler samplerFor(BoundInput input) {
        if (input.sampler() != null) {
            return input.sampler();
        }
        return input.point() ? PhotonSceneCapture.sampler() : linearClamp();
    }

    private static boolean dispatchCustomShader(CompiledEffect effect, CompiledEffect.CompiledPass pass,
                                                float weight, Map<String, Object> params,
                                                Resources resources, FrameInputs inputs) {
        var shaderPass = CustomShaderPass.get(pass.customShader());
        if (shaderPass == null) {
            logFailureOnce(effect, "custom shader '%s' failed to load (see log)".formatted(pass.customShader()));
            return false;
        }
        var target = resources.targets()[pass.outputResource()];
        // defaults first: the json's own values are the baseline every dispatch starts from, so an
        // absent binding can never inherit the previous dispatch's value
        var uniforms = shaderPass.uniforms();
        uniforms.resetToDefaults();
        for (var binding : pass.params().entrySet()) {
            var value = resolve(binding.getValue(), params, weight);
            if (value != null) setPassValue(uniforms, binding.getKey(), value);
        }
        uniforms.set("ScreenSize", target.width(), target.height());
        // Photon's clock, not the world's: in the editor it is the timeline's and freezes when paused
        uniforms.set("GameTime", PhotonTime.dayFraction());
        // the DRAWING view's planes (an editor scene's, not the world's) — a pass that reasons about
        // distance linearises the depth buffer with these
        uniforms.set("ZNear", PhotonEngineUniforms.zNear());
        uniforms.set("ZFar", PhotonEngineUniforms.zFar());

        var bound = new LinkedHashMap<String, BoundInput>();
        for (var binding : pass.textures().entrySet()) {
            var input = resolveInput(binding.getValue(), resources, inputs, params);
            if (input == null) return false;
            bound.put(binding.getKey(), input);
            var texel = texelSize(input);
            uniforms.set(binding.getKey() + PhotonFullscreenCompiler.TEXEL_SIZE_SUFFIX, texel);
        }
        uniforms.upload(); // outside the pass — writeToBuffer is illegal once one is open
        // resolving the placeholder can register+upload a texture, so do it here rather than in the bind
        var placeholder = missingView();

        var linear = linearClamp();
        PhotonFullscreenPass.draw("photonfx pass", shaderPass.pipeline(), target.view(), renderPass -> {
            // every pipeline-declared sampler must be bound at draw; a sampler the effect wired nothing
            // to (the compiler allows it) gets the missing texture rather than a stale unit
            for (var sampler : shaderPass.info().samplers()) {
                var input = bound.get(sampler);
                renderPass.bindTexture(sampler, input != null ? input.view() : placeholder,
                        input != null ? samplerFor(input) : linear);
            }
            uniforms.bindTo(renderPass);
        });
        return true;
    }

    private static boolean dispatchGraph(CompiledEffect effect, CompiledEffect.CompiledPass pass,
                                         float weight, Map<String, Object> params,
                                         Resources resources, FrameInputs inputs) {
        var entry = FullscreenGraphRuntime.get(pass.graphPath());
        if (entry == null || !entry.isValid()) {
            logFailureOnce(effect, entry == null ? "missing pass graph" : entry.getErrorMessage());
            return false;
        }
        var material = entry.material();
        var pipeline = entry.pipeline();
        var compiled = entry.getCompiled();
        if (material == null || pipeline == null || compiled == null) {
            logFailureOnce(effect, "pass shader failed to build (see log)");
            return false;
        }
        var target = resources.targets()[pass.outputResource()];

        // graph defaults first (the same baseline reset the custom-shader flavor does), then this pass's
        // own bindings — inline constants, blended effect params, or the request weight
        material.refreshDefaults(compiled);
        // every external view the material still holds points at a target that has since gone back to the
        // pool (entries are shared between passes and frames), so drop them before binding this dispatch's
        for (var sampler : material.managedSamplerNames()) {
            material.setTextureView(sampler, null, null);
        }
        for (var binding : pass.params().entrySet()) {
            var value = resolve(binding.getValue(), params, weight);
            if (value != null) stageValue(material, compiled, binding.getKey(), value);
        }

        for (var binding : pass.textures().entrySet()) {
            var input = resolveInput(binding.getValue(), resources, inputs, params);
            if (input == null) return false;
            material.setTextureView(binding.getKey(), input.view(), samplerFor(input));
            // the TexelSize node's companion field, by the same suffix convention it compiles under
            material.setUniformField(binding.getKey() + PhotonFullscreenCompiler.TEXEL_SIZE_SUFFIX,
                    texelSize(input));
        }
        material.prepareUniforms(); // uploads the UBO + resolves textures; must precede the pass

        // A fullscreen pass binds nothing of Minecraft's, but a graph CAN still pull DynamicTransforms in:
        // the unconnected-normal / unconnected-viewDir defaults (ShaderGraphCompiler.meshNormal /
        // meshViewDir) import dynamictransforms.glsl for ModelViewMat, and EXCLUDED_NODES only bans nodes,
        // not port defaults. 26.1 validates every DECLARED uniform at draw, so the pipeline would then
        // declare a block nothing fills ("Missing uniform DynamicTransforms"). Bound unconditionally: it
        // costs one ring-buffer append, and setUniform for a block the pipeline didn't declare is a no-op.
        // The values are neutral because the quad is a pass-through — no transform, no colour modulation
        // and no model offset to express. Written BEFORE the pass: the write appends to (and may grow) the
        // dynamic uniform buffer, which is illegal once one is open.
        var transforms = RenderSystem.getDynamicUniforms()
                .writeTransform(IDENTITY, NO_MODULATION, NO_OFFSET, IDENTITY);

        PhotonFullscreenPass.draw("photonfx graph pass", pipeline, target.view(), renderPass -> {
            renderPass.setUniform(BOUND_BUILTIN_UNIFORM, transforms);
            material.bindCustomUniforms(renderPass);
        });
        return true;
    }

    /** The {@code (w, h, 1/w, 1/h)} companion every bound sampler gets; 0 sizes leave it zeroed. */
    private static float[] texelSize(BoundInput input) {
        return new float[]{input.width(), input.height(),
                input.width() > 0 ? 1f / input.width() : 0f,
                input.height() > 0 ? 1f / input.height() : 0f};
    }

    // ---- input resolution -----------------------------------------------------------------------

    /** Null = this pass cannot run (a required input does not exist this frame). {@code params} is the
     *  request's, for PARAM-sourced texture inputs. */
    @Nullable
    private static BoundInput resolveInput(CompiledEffect.ResourceRef ref, Resources resources,
                                           FrameInputs inputs, Map<String, Object> params) {
        return switch (ref.source()) {
            case SCENE_COLOR -> new BoundInput(inputs.sceneColor(), inputs.width(), inputs.height(), false);
            // an absent scene depth / mask must not fall through to a placeholder: the pass would read
            // garbage and the whole frame would look broken. The stack already skips mask-reading
            // effects on maskless frames; this guards the remaining callers (editor preview).
            case SCENE_DEPTH -> inputs.sceneDepth() == null ? null
                    : new BoundInput(inputs.sceneDepth(), inputs.width(), inputs.height(), false);
            case CUSTOM_MASK -> inputs.maskColor() == null ? null
                    : new BoundInput(inputs.maskColor(), inputs.width(), inputs.height(), true);
            case CUSTOM_DEPTH -> inputs.maskDepth() == null ? null
                    : new BoundInput(inputs.maskDepth(), inputs.width(), inputs.height(), false);
            case RESOURCE -> {
                var target = resources.targets()[ref.resource()];
                // a resource read before anything wrote it: bind the missing texture rather than fail
                yield target == null ? new BoundInput(missingView(), 0, 0, false)
                        : new BoundInput(target.view(), resources.widths()[ref.resource()],
                                resources.heights()[ref.resource()], false);
            }
            // Texture Input node: a fixed image baked into the effect (ASSET), or an effect sampler
            // parameter supplied per request (PARAM). Both the stack's blend and the editor preview emit
            // the FULL schema, whose default for such a param is the node's own texture — so the lookup
            // only misses on a caller that skipped the schema, where the placeholder is the right answer.
            case ASSET -> externalTexture(ref.asset());
            case PARAM -> {
                var value = params.get(ref.param());
                yield externalTexture(value instanceof RenderTypeGraphTypes.Sampler2DValue s ? s : null);
            }
        };
    }

    /**
     * An external texture ({@link RenderTypeGraphTypes.Sampler2DValue}) resolved to a bound view plus the
     * sampler the author chose on the node — 26.1 binds samplers explicitly, so the picker's
     * filter/address/mipmap actually take effect here rather than being ignored as in 1.21.
     *
     * <p>A missing or malformed location falls back to the SAMPLER2D default rather than failing the pass:
     * an effect referencing a texture the pack removed should still render, just with the placeholder.
     * Dimensions stay 0 — the manager does not expose them here, so the {@code _TexelSize} companion
     * zeroes out (LUT/noise passes rarely need it).
     */
    private static BoundInput externalTexture(@Nullable RenderTypeGraphTypes.Sampler2DValue sampler) {
        var value = sampler;
        if (value == null || !LDLib2.isValidResourceLocation(value.location())) {
            value = RenderTypeGraphTypes.Sampler2DValue.defaultValue();
        }
        var view = Minecraft.getInstance().getTextureManager()
                .getTexture(Identifier.parse(value.location())).getTextureView();
        var address = value.address() == RenderTypeGraphTypes.SamplerAddress.REPEAT
                ? AddressMode.REPEAT : AddressMode.CLAMP_TO_EDGE;
        var filter = value.filter() == RenderTypeGraphTypes.SamplerFilter.LINEAR
                ? FilterMode.LINEAR : FilterMode.NEAREST;
        var gpuSampler = RenderSystem.getSamplerCache()
                .getSampler(address, address, filter, filter, value.mipmap());
        return new BoundInput(view, 0, 0, false, gpuSampler);
    }

    // ---- value marshalling ----------------------------------------------------------------------

    @Nullable
    private static Object resolve(CompiledEffect.ValueBinding binding, Map<String, Object> params, float weight) {
        return switch (binding) {
            case CompiledEffect.ValueBinding.Constant constant -> constant.value();
            case CompiledEffect.ValueBinding.ParamRef ref -> params.get(ref.schemaParam());
            case CompiledEffect.ValueBinding.EffectWeight ignored -> weight;
        };
    }

    /** Marshal one display-name value into a hand-written pass's {@code PhotonPass} block. A 4-component
     *  member fed an Integer is an ARGB color (the render graph's COLOR params); anything else is a count. */
    private static void setPassValue(PassUniforms uniforms, String name, Object value) {
        switch (value) {
            case Float f -> uniforms.set(name, f);
            case Boolean b -> uniforms.set(name, b ? 1f : 0f);
            case Vector2f v -> uniforms.set(name, v.x, v.y);
            case Vector3f v -> uniforms.set(name, v.x, v.y, v.z);
            case Vector4f v -> uniforms.set(name, v.x, v.y, v.z, v.w);
            case Integer i -> {
                if (uniforms.countOf(name) == 4) {
                    uniforms.set(name, ((i >> 16) & 0xFF) / 255f, ((i >> 8) & 0xFF) / 255f,
                            (i & 0xFF) / 255f, ((i >>> 24) & 0xFF) / 255f);
                } else {
                    uniforms.set(name, i);
                }
            }
            default -> { }
        }
    }

    /** Stage one display-name value into a graph pass's material — the same marshalling
     *  {@code ShaderGraphMaterial.applyOverride} uses. */
    static void stageValue(RenderTypeGraphMaterial material, CompiledShaderGraph compiled,
                           String name, Object value) {
        switch (value) {
            case RenderTypeGraphTypes.Sampler2DValue sampler -> {
                if (LDLib2.isValidResourceLocation(sampler.location())) {
                    material.setTexture(name, Identifier.parse(sampler.location()));
                }
            }
            case RenderTypeGraphTypes.GradientValue gradient -> material.setGradient(name, gradient);
            case RenderTypeGraphTypes.CurveValue curve -> material.setCurve(name, curve);
            case Vector2f v -> material.setUniform(name, v);
            case Vector3f v -> material.setUniform(name, v);
            case Vector4f v -> material.setUniform(name, v);
            case Float f -> material.setUniform(name, f);
            case Boolean b -> material.setUniform(name, b ? 1f : 0f);
            case Integer i -> {
                // an Integer is either an INT variable or a COLOR (ARGB) one — disambiguate by field type
                var field = compiled.uniformFields().get(name);
                if (field != null && field.type() == GlslType.VEC4) {
                    material.setColorUniform(name, i);
                } else {
                    material.setUniform(name, (float) i);
                }
            }
            default -> { }
        }
    }

    // ---- shared bits ----------------------------------------------------------------------------

    static GpuSampler linearClamp() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    static GpuTextureView missingView() {
        return RenderTypeGraphMaterial.missingView();
    }


    /** Null-safe effect identity — editor-preview effects compile without a source path. */
    private static String sourceName(CompiledEffect effect) {
        return effect.source() == null ? "editor_preview" : effect.source().getResourceName();
    }

    private static String sourceKey(CompiledEffect effect) {
        return String.valueOf(effect.source());
    }

    private static void logFailureOnce(CompiledEffect effect, String reason) {
        if (LOGGED_FAILURES.add(sourceKey(effect))) {
            Photon.LOGGER.warn("post effect '{}' skipped: {}", sourceName(effect), reason);
        }
    }
}
