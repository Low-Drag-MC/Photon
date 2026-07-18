package com.lowdragmc.photon.client.postfx.runtime;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.runtime.KGBuiltinUniforms;
import com.lowdragmc.kilagraph.rendertype.runtime.KGMaterialValues;
import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.client.shader.HDRTarget;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.postfx.shadergraph.PhotonFullscreenCompiler;
import com.lowdragmc.photon.client.postfx.shadergraph.runtime.FullscreenGraphRuntime;
import com.lowdragmc.photon.client.postprocessing.PhotonPostProcessing;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Executes one {@link CompiledEffect} invocation: resolves the transient resource sizes, then per pass —
 * acquire the output target from the pool, stage builtins + blended params + texture inputs (with their
 * {@code _TexelSize} companions), dispatch a fullscreen blit, and release inputs at their last use.
 * A broken pass graph skips the whole effect (chain passthrough), remembered per path so it logs once.
 *
 * <p>Caller contract: post render state is already set ({@link PostEffectStack} owns it for the chain);
 * the returned target is pool-owned by the CALLER (release it or hand it on). Render thread only.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RenderGraphExecutor {

    /** Effect paths whose failure was already logged — cleared when the source entry changes. */
    private static final Set<String> LOGGED_FAILURES = new HashSet<>();

    private RenderGraphExecutor() {}

    /**
     * Run {@code effect} over {@code chainInput}. Returns the pooled output target, or null when any
     * pass failed to resolve/compile — the chain passes through unchanged.
     *
     * @param sceneDepthTexture  the GL texture id SCENE_DEPTH inputs bind (-1 = none available)
     * @param maskTexture        the CustomMask color texture (-1 = no mask this frame)
     * @param customDepthTexture the mask target's depth texture (-1 = no mask this frame)
     */
    @Nullable
    public static HDRTarget execute(CompiledEffect effect, float weight, Map<String, Object> params,
                                    RenderTarget chainInput, int sceneDepthTexture,
                                    int maskTexture, int customDepthTexture) {
        if (effect.passes().isEmpty()) return null; // no-op effect: chain passthrough
        int resourceCount = effect.resources().size();
        int[] widths = new int[resourceCount];
        int[] heights = new int[resourceCount];
        for (int i = 0; i < resourceCount; i++) {
            var size = effect.resources().get(i).size();
            switch (size.mode()) {
                case SCREEN_RELATIVE -> {
                    widths[i] = Math.max(1, Math.round(chainInput.width * size.scale()));
                    heights[i] = Math.max(1, Math.round(chainInput.height * size.scale()));
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

        boolean debugGroup = Platform.isDevEnv() && GL.getCapabilities().GL_KHR_debug;
        if (debugGroup) {
            GL46.glPushDebugGroup(GL46.GL_DEBUG_SOURCE_APPLICATION, 0, "photonfx:" + sourceName(effect));
        }
        var targets = new HDRTarget[resourceCount];
        boolean succeeded = false;
        try {
            for (int passIndex = 0; passIndex < effect.passes().size(); passIndex++) {
                var pass = effect.passes().get(passIndex);
                int out = pass.outputResource();
                if (targets[out] == null) {
                    targets[out] = PostFXTargetPool.acquire(widths[out], heights[out],
                            effect.resources().get(out).format());
                }

                ShaderInstance shader;
                if (pass.customShader() != null) {
                    // hand-written core shader pass: uniforms set directly, defaults re-baked from
                    // the json each dispatch (uniform values persist on the instance across frames)
                    var customShader = CustomShaderPass.getShader(pass.customShader());
                    var info = CustomShaderPass.getInfo(pass.customShader());
                    if (customShader == null || info == null) {
                        logFailureOnce(effect, "custom shader '%s' failed to load (see log)"
                                .formatted(pass.customShader()));
                        return null;
                    }
                    shader = customShader;
                    for (var spec : info.uniforms()) {
                        var uniform = shader.getUniform(spec.name());
                        if (uniform != null) setFloats(uniform, spec.defaults());
                    }
                    pass.params().forEach((name, binding) -> {
                        var value = switch (binding) {
                            case CompiledEffect.ValueBinding.Constant constant -> constant.value();
                            case CompiledEffect.ValueBinding.ParamRef ref -> params.get(ref.schemaParam());
                            case CompiledEffect.ValueBinding.EffectWeight ignored -> weight;
                        };
                        if (value != null) setUniformValue(shader, name, value);
                    });
                } else {
                    var entry = FullscreenGraphRuntime.get(pass.graphPath());
                    if (entry == null || !entry.isValid()) {
                        logFailureOnce(effect, entry == null ? "missing pass graph" : entry.getErrorMessage());
                        return null;
                    }
                    var graphShader = entry.shader();
                    var compiled = entry.getCompiled();
                    var values = entry.values();
                    if (graphShader == null || compiled == null || values == null) {
                        logFailureOnce(effect, "pass shader failed to build (see log)");
                        return null;
                    }
                    shader = graphShader;

                    // engine uniforms the graph declared (a raw apply() won't set them)
                    KGBuiltinUniforms.bind(graphShader, compiled.builtinUniforms());

                    // exposed variables: graph defaults <- pass bindings (inline constants / blended params)
                    values.bakeDefaults(compiled);
                    pass.params().forEach((name, binding) -> {
                        var value = switch (binding) {
                            case CompiledEffect.ValueBinding.Constant constant -> constant.value();
                            case CompiledEffect.ValueBinding.ParamRef ref -> params.get(ref.schemaParam());
                            case CompiledEffect.ValueBinding.EffectWeight ignored -> weight;
                        };
                        if (value != null) stageValue(values, compiled, name, value);
                    });
                    values.apply(graphShader);
                }

                var screenSize = shader.getUniform("ScreenSize");
                if (screenSize != null) screenSize.set((float) widths[out], (float) heights[out]);
                var gameTime = shader.getUniform("GameTime");
                if (gameTime != null) gameTime.set(RenderSystem.getShaderGameTime());

                // texture inputs override the staged sampler defaults; TexelSize rides the same names
                for (var binding : pass.textures().entrySet()) {
                    int textureId;
                    int textureWidth;
                    int textureHeight;
                    switch (binding.getValue().source()) {
                        case SCENE_COLOR -> {
                            textureId = chainInput.getColorTextureId();
                            textureWidth = chainInput.width;
                            textureHeight = chainInput.height;
                        }
                        case SCENE_DEPTH -> {
                            textureId = sceneDepthTexture;
                            textureWidth = chainInput.width;
                            textureHeight = chainInput.height;
                        }
                        case CUSTOM_MASK -> {
                            // -1 must not reach setSampler: vanilla apply() skips binding at -1 and
                            // the sampler reads whatever texture the unit last held (garbage). The
                            // stack already skips mask-reading effects on maskless frames; this
                            // guards the remaining callers (editor preview).
                            if (maskTexture == -1) return null;
                            textureId = maskTexture;
                            textureWidth = chainInput.width;
                            textureHeight = chainInput.height;
                        }
                        case CUSTOM_DEPTH -> {
                            if (customDepthTexture == -1) return null;
                            textureId = customDepthTexture;
                            textureWidth = chainInput.width;
                            textureHeight = chainInput.height;
                        }
                        default -> {
                            int resource = binding.getValue().resource();
                            textureId = targets[resource] == null ? -1 : targets[resource].getColorTextureId();
                            textureWidth = widths[resource];
                            textureHeight = heights[resource];
                        }
                    }
                    shader.setSampler(binding.getKey(), textureId);
                    var texelSize = shader.getUniform(binding.getKey() + PhotonFullscreenCompiler.TEXEL_SIZE_SUFFIX);
                    if (texelSize != null) {
                        texelSize.set((float) textureWidth, (float) textureHeight,
                                1f / textureWidth, 1f / textureHeight);
                    }
                }

                PhotonPostProcessing.blitShader(shader, targets[out], false);

                // aliasing: anything last consumed by this pass goes straight back to the pool
                for (int resource = 0; resource < resourceCount; resource++) {
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
                if (target != null) PostFXTargetPool.release(target);
            }
            if (debugGroup) GL46.glPopDebugGroup();
            if (succeeded) LOGGED_FAILURES.remove(sourceKey(effect));
        }
    }

    /** Null-safe effect identity — editor-preview effects compile without a source path. */
    private static String sourceName(CompiledEffect effect) {
        return effect.source() == null ? "editor_preview" : effect.source().getResourceName();
    }

    private static String sourceKey(CompiledEffect effect) {
        return String.valueOf(effect.source());
    }

    /** Stage one display-name value into the store, typed by the compiled uniform field — the same
     *  marshalling {@code ShaderGraphMaterial.applyOverride} uses. */
    static void stageValue(KGMaterialValues values, CompiledShaderGraph compiled, String name, Object value) {
        switch (value) {
            case RenderTypeGraphTypes.Sampler2DValue sampler -> {
                if (LDLib2.isValidResourceLocation(sampler.location())) {
                    values.setTexture(name, ResourceLocation.parse(sampler.location()));
                }
            }
            case RenderTypeGraphTypes.GradientValue gradient -> values.setGradient(name, gradient);
            case RenderTypeGraphTypes.CurveValue curve -> values.setCurve(name, curve);
            case Vector2f v -> values.setUniform(name, v);
            case Vector3f v -> values.setUniform(name, v);
            case Vector4f v -> values.setUniform(name, v);
            case Float f -> values.setByVariable(name, f);
            case Boolean b -> values.setByVariable(name, b ? 1f : 0f);
            case Integer i -> {
                // an Integer is either an INT variable or a COLOR (ARGB) one — disambiguate by field type
                var field = compiled.uniformFields().get(name);
                if (field != null && field.type() == GlslType.VEC4) {
                    values.setColorUniform(name, i);
                } else {
                    values.setByVariable(name, i);
                }
            }
            default -> { }
        }
    }

    /** Reset a custom-shader uniform to its json defaults (1..4 floats). */
    private static void setFloats(Uniform uniform, float[] values) {
        switch (values.length) {
            case 1 -> uniform.set(values[0]);
            case 2 -> uniform.set(values[0], values[1]);
            case 3 -> uniform.set(values[0], values[1], values[2]);
            case 4 -> uniform.set(values[0], values[1], values[2], values[3]);
            default -> { }
        }
    }

    /** Direct uniform marshalling for custom-shader passes (no KGMaterialValues store). */
    private static void setUniformValue(ShaderInstance shader, String name, Object value) {
        var uniform = shader.getUniform(name);
        if (uniform == null) return;
        switch (value) {
            case Float f -> uniform.set(f);
            case Boolean b -> uniform.set(b ? 1f : 0f);
            case Vector2f v -> uniform.set(v.x, v.y);
            case Vector3f v -> uniform.set(v.x, v.y, v.z);
            case Vector4f v -> uniform.set(v.x, v.y, v.z, v.w);
            case Integer i -> {
                // an Integer is an INT param or an ARGB COLOR — a vec4 uniform gets rgba 0..1
                if (uniform.getType() == 7) {
                    uniform.set(((i >> 16) & 0xFF) / 255f, ((i >> 8) & 0xFF) / 255f,
                            (i & 0xFF) / 255f, ((i >>> 24) & 0xFF) / 255f);
                } else {
                    uniform.set((float) i);
                }
            }
            default -> { }
        }
    }

    private static void logFailureOnce(CompiledEffect effect, String reason) {
        if (LOGGED_FAILURES.add(sourceKey(effect))) {
            Photon.LOGGER.warn("post effect '{}' skipped: {}", sourceName(effect), reason);
        }
    }
}
