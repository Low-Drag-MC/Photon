package com.lowdragmc.photon.client.postfx.runtime;

import com.lowdragmc.lowdraglib2.client.shader.HDRTarget;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.PhotonShaders;
import com.lowdragmc.photon.client.postfx.shadergraph.runtime.FullscreenGraphRuntime;
import com.lowdragmc.photon.client.postprocessing.PhotonPostProcessing;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-frame post-effect request collector + chain runner. Any source (Java API via
 * {@code PhotonPostFX}, timeline clips, other mods) {@link #submit}s activation requests each frame;
 * {@link #consumeAndExecute} — called from {@code RenderPassPipeline.afterRendering()} — groups them by
 * effect, blends parameters by weight (Unity-Volume-style: ascending-weight sequential lerp, non-lerpable
 * take the highest weight, blended weight {@code 1-Π(1-wᵢ)}), sorts by effect priority around the builtin
 * bloom (priority 0), and runs each effect once over the HDR chain.
 *
 * <p>The {@code consumedFrame} guard makes consumption once-per-frame — the OPAQUE and TRANSLUCENT
 * particle queues each own a pipeline whose {@code build()} can run in the same frame, which previously
 * double-executed bloom; now the first build consumes everything and the second passes through.
 * Unconsumed requests are dropped at the frame boundary ({@link #onFrameEnd}) — a source that stops
 * submitting stops the effect next frame. Render thread only.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PostEffectStack {

    public static final PostEffectStack GLOBAL = new PostEffectStack();
    /** The editor SceneView's isolated stack — timeline previews in the editor never leak into the
     *  world render and vice versa. */
    public static final PostEffectStack EDITOR_SCENE = new PostEffectStack();

    /** True while the editor scene is rendering (relayed by PhotonParticleManager, like
     *  sceneBloomEnabled) — routes {@link #currentSink()} to {@link #EDITOR_SCENE}. */
    private static boolean editorSceneRendering;

    public static void setEditorSceneRendering(boolean rendering) {
        editorSceneRendering = rendering;
    }

    /** Per-stack gate (the SceneView effects toggle): disabled = drop requests, bloom-only. */
    private boolean effectsEnabled = true;

    public void setEffectsEnabled(boolean enabled) {
        this.effectsEnabled = enabled;
    }

    /** Requests below this blended weight skip the effect entirely. */
    private static final float MIN_WEIGHT = 1e-3f;
    /** Above this weight the auto final mix is a no-op and is skipped. */
    private static final float FULL_WEIGHT = 0.999f;

    private record Request(IResourcePath effect, Map<String, Object> params, float weight) {}

    private record Invocation(CompiledEffect effect, float weight, Map<String, Object> params) {}

    /** Phase-1 effect resolution: a fullscreen graph path adapts to a single-pass effect, cached and
     *  identity-checked against its runtime entry (Phase 2 checks the render-graph library first). */
    private record AdapterEntry(FullscreenGraphRuntime.Entry sourceEntry, @Nullable CompiledEffect effect) {}
    private static final Map<IResourcePath, AdapterEntry> ADAPTERS = new HashMap<>();

    private final List<Request> requests = new ArrayList<>();
    private long consumedFrame = -1;
    /** The pooled target the main blit is still reading this frame; recycled at the frame boundary. */
    @Nullable
    private HDRTarget retiredOutput;

    /** The stack the current render context feeds: the editor scene's while it renders, else the world's. */
    public static PostEffectStack currentSink() {
        return editorSceneRendering ? EDITOR_SCENE : GLOBAL;
    }

    /** Request {@code effect} for THIS frame. {@code params} override the effect's schema defaults by
     *  display name; {@code weight} (0..1] drives parameter blending and the auto final mix. */
    public void submit(@Nullable IResourcePath effect, Map<String, Object> params, float weight) {
        if (effect == null || weight <= 0) return;
        requests.add(new Request(effect, Map.copyOf(params), Math.min(1f, weight)));
    }

    public boolean hasPending() {
        return !requests.isEmpty();
    }

    /** Whether this stack already ran its chain this frame (keyed on the pool's frame clock). */
    public boolean isConsumedThisFrame() {
        return consumedFrame == PostFXTargetPool.currentFrame();
    }

    public RenderTarget consumeAndExecute(HDRTarget chainInput, boolean doBuiltinBloom) {
        return consumeAndExecute(chainInput, doBuiltinBloom, chainInput.getDepthTextureId());
    }

    /**
     * Run this frame's blended effect chain over {@code chainInput} and return the final target (==
     * {@code chainInput} when there is nothing to do, or on the second pipeline build of the frame).
     * The builtin bloom keeps its exact previous conditions and slots in at priority 0.
     *
     * @param sceneDepthTexture the depth texture SCENE_DEPTH inputs read — passed explicitly because
     *                          the standalone (no-particle) path's chain copy carries no depth
     */
    public RenderTarget consumeAndExecute(HDRTarget chainInput, boolean doBuiltinBloom, int sceneDepthTexture) {
        long frame = PostFXTargetPool.currentFrame();
        if (consumedFrame == frame) return chainInput;
        consumedFrame = frame;
        PostFXPreview.captureIfRequested(chainInput); // editors preview against the clean scene

        if (!effectsEnabled) {
            requests.clear();
            return doBuiltinBloom ? PhotonPostProcessing.postTarget(chainInput) : chainInput;
        }
        var invocations = blendRequests();
        requests.clear();
        if (invocations.isEmpty()) {
            return doBuiltinBloom ? PhotonPostProcessing.postTarget(chainInput) : chainInput;
        }

        RenderTarget chain = chainInput;
        HDRTarget pooledChain = null;
        boolean bloomDone = !doBuiltinBloom;
        setPostRenderState();
        try {
            for (var invocation : invocations) {
                if (invocation.effect().passes().isEmpty()) continue; // no-op (scene passthrough) effect
                if (!bloomDone && invocation.effect().priority() >= 0) {
                    chain = PhotonPostProcessing.postTarget(chain);
                    if (pooledChain != null) {
                        PostFXTargetPool.release(pooledChain);
                        pooledChain = null;
                    }
                    bloomDone = true;
                    setPostRenderState(); // renderBloom restores world state at its end
                }

                var output = RenderGraphExecutor.execute(invocation.effect(), invocation.weight(),
                        invocation.params(), chain, sceneDepthTexture);
                if (output == null) continue; // broken effect: chain passes through

                var result = output;
                if (invocation.effect().autoBlend() && invocation.weight() < FULL_WEIGHT) {
                    // universal fade: mix(chain, effect, weight) — no graph cooperation needed
                    var mixed = PostFXTargetPool.acquire(chain.width, chain.height);
                    var mixShader = PhotonShaders.getWeightMixShader();
                    mixShader.setSampler("SamplerA", chain.getColorTextureId());
                    mixShader.setSampler("SamplerB", output.getColorTextureId());
                    mixShader.safeGetUniform("Weight").set(invocation.weight());
                    PhotonPostProcessing.blitShader(mixShader, mixed, false);
                    PostFXTargetPool.release(output);
                    result = mixed;
                }

                if (pooledChain != null) PostFXTargetPool.release(pooledChain);
                pooledChain = result;
                chain = result;
            }
            if (!bloomDone) {
                chain = PhotonPostProcessing.postTarget(chain);
                if (pooledChain != null) {
                    PostFXTargetPool.release(pooledChain);
                    pooledChain = null;
                }
            }
        } finally {
            restorePostRenderState();
        }
        // the caller blits this target to the main/Iris framebuffer right after we return, so a pooled
        // final target stays out of the pool until the frame boundary
        retiredOutput = pooledChain;
        return chain;
    }

    /** Frame boundary: recycle the displayed output and drop unconsumed (stale) requests. */
    public void onFrameEnd() {
        if (retiredOutput != null) {
            PostFXTargetPool.release(retiredOutput);
            retiredOutput = null;
        }
        requests.clear();
    }

    // ---- blending --------------------------------------------------------------------------------

    /** Group requests by effect, blend weights + parameters, order by priority (path tie-break). */
    private List<Invocation> blendRequests() {
        if (requests.isEmpty()) return List.of();
        var grouped = new LinkedHashMap<IResourcePath, List<Request>>();
        for (var request : requests) {
            grouped.computeIfAbsent(request.effect(), k -> new ArrayList<>()).add(request);
        }
        var invocations = new ArrayList<Invocation>();
        grouped.forEach((path, group) -> {
            var effect = resolveEffect(path);
            if (effect == null) return;
            group.sort(Comparator.comparingDouble(Request::weight));
            float weight = 0f;
            for (var request : group) {
                weight += (1f - weight) * request.weight(); // sequential lerp of the implicit Weight=1
            }
            if (weight < MIN_WEIGHT) return;
            invocations.add(new Invocation(effect, weight, blendParams(effect, group)));
        });
        invocations.sort(Comparator.comparingInt((Invocation inv) -> inv.effect().priority())
                .thenComparing(inv -> inv.effect().source().toString()));
        return invocations;
    }

    /**
     * Per schema param: lerpable values sequential-lerp ascending weight starting from the schema
     * default; the rest take the highest-weight override. The FULL schema is emitted (defaults
     * included) — a ParamRef binding must always resolve, because the blackboard default may differ
     * from the pass graph's own variable default.
     */
    private static Map<String, Object> blendParams(CompiledEffect effect, List<Request> group) {
        if (effect.schema().isEmpty()) return Map.of();
        var params = new HashMap<String, Object>();
        for (var spec : effect.schema()) {
            Object current = spec.defaultValue();
            if (spec.lerpable()) {
                for (var request : group) { // already ascending weight
                    var override = request.params().get(spec.name());
                    if (override != null) current = lerpValue(current, override, request.weight());
                }
            } else {
                float bestWeight = -1f;
                for (var request : group) {
                    var override = request.params().get(spec.name());
                    if (override != null && request.weight() > bestWeight) {
                        bestWeight = request.weight();
                        current = override;
                    }
                }
            }
            if (current != null) params.put(spec.name(), current);
        }
        return params;
    }

    /** Componentwise lerp for the blendable types; mismatched/unknown pairs snap to the override. */
    private static Object lerpValue(@Nullable Object base, Object target, float t) {
        return switch (target) {
            case Float f when base instanceof Float b -> b + (f - b) * t;
            case Vector2f v when base instanceof Vector2f b -> new Vector2f(b).lerp(v, t);
            case Vector3f v when base instanceof Vector3f b -> new Vector3f(b).lerp(v, t);
            case Vector4f v when base instanceof Vector4f b -> new Vector4f(b).lerp(v, t);
            case Integer argb when base instanceof Integer b -> lerpArgb(b, argb, t);
            default -> target;
        };
    }

    private static int lerpArgb(int from, int to, float t) {
        int a = lerpChannel(from >>> 24, to >>> 24, t);
        int r = lerpChannel((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
        int g = lerpChannel((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
        int b = lerpChannel(from & 0xFF, to & 0xFF, t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerpChannel(int from, int to, float t) {
        return Math.min(255, Math.max(0, Math.round(from + (to - from) * t)));
    }

    // ---- effect resolution -----------------------------------------------------------------------

    /** Effect resolution: a render-graph asset wins; a bare fullscreen graph falls back to the
     *  single-pass adapter (so simple one-pass effects need no render graph at all). Public: the
     *  clip editor resolves the schema to offer parameter-override rows. */
    @Nullable
    public static CompiledEffect resolveEffect(IResourcePath path) {
        var renderGraph = RenderGraphRuntime.get(path);
        if (renderGraph != null) { // the path exists in the render-graph library (possibly broken)
            return renderGraph.getEffect();
        }
        var entry = FullscreenGraphRuntime.get(path);
        if (entry == null) return null;
        var cached = ADAPTERS.get(path);
        if (cached != null && cached.sourceEntry() == entry) return cached.effect();
        var effect = entry.isValid() ? CompiledEffect.singlePass(path, entry) : null;
        if (effect == null) {
            Photon.LOGGER.warn("post effect '{}' unavailable: {}", path.getResourceName(), entry.getErrorMessage());
        }
        ADAPTERS.put(path, new AdapterEntry(entry, effect));
        return effect;
    }

    // ---- render state ----------------------------------------------------------------------------

    /** The chain-wide state every fullscreen pass runs under (mirrors renderBloom's setup).
     *  Public: the editor preview runs the executor outside the stack and needs the same state. */
    public static void setPostRenderState() {
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
    }

    public static void restorePostRenderState() {
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
    }
}
