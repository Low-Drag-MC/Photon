package com.lowdragmc.photon.client.postfx.runtime;

import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.PhotonShaders;
import com.lowdragmc.photon.client.postfx.shadergraph.runtime.FullscreenGraphRuntime;
import com.lowdragmc.photon.client.postprocessing.PhotonPostProcessing;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-frame post-effect request collector + chain runner. Any source (Java API via
 * {@code PhotonPostFX}, timeline clips, other mods) {@link #submit}s activation requests each frame;
 * {@link #consumeAndExecute} — called from {@code RenderPassPipeline.afterRendering()} — groups them by
 * effect, blends parameters by weight (Unity-Volume-style: ascending-weight sequential lerp, non-lerpable
 * take the highest weight, blended weight {@code 1-Π(1-wᵢ)}), sorts by effect priority around the builtin
 * bloom (priority 0), and runs each effect once over the HDR chain — except requests carrying the
 * reserved {@code Independent} param, which skip merging and run once EACH (UE-material-instance-style).
 *
 * <p>The {@code consumedFrame} guard makes consumption once-per-frame — the OPAQUE and TRANSLUCENT
 * particle queues each own a pipeline whose {@code build()} can run in the same frame, which previously
 * double-executed bloom; now the first build consumes everything and the second passes through.
 * Unconsumed requests are dropped at the frame boundary ({@link #onFrameEnd}) — a source that stops
 * submitting stops the effect next frame. Render thread only.</p>
 */
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

    /** {@code maskGroups}: null = fullscreen; empty = any mask group; else the exact set of group
     *  names — the COVERAGE UNION of the effect's requests (any unmasked request wins fullscreen;
     *  several groups bake an exact union mask at execution). */
    private record Invocation(CompiledEffect effect, float weight, Map<String, Object> params,
                              @Nullable java.util.Set<String> maskGroups) {}

    /** The mask coverage a request asks for: null = fullscreen, empty = any group, else groups.
     *  Accepts the string form (group name, blank = any) and the legacy numeric form. */
    @Nullable
    private static java.util.Set<String> requestMaskGroups(Request request) {
        var value = request.params().get(CompiledEffect.MASK_FILTER_PARAM);
        if (value instanceof String groupName) {
            return groupName.isBlank() ? java.util.Set.of() : java.util.Set.of(groupName);
        }
        if (value instanceof Number legacy) {
            if (legacy.floatValue() < 0) return null;
            int id = Math.round(legacy.floatValue());
            return id == 0 ? java.util.Set.of() : java.util.Set.of(String.valueOf(id));
        }
        return null;
    }

    /** Phase-1 effect resolution: a fullscreen graph path adapts to a single-pass effect, cached and
     *  identity-checked against its runtime entry (Phase 2 checks the render-graph library first). */
    private record AdapterEntry(FullscreenGraphRuntime.Entry sourceEntry, @Nullable CompiledEffect effect) {}
    private static final Map<IResourcePath, AdapterEntry> ADAPTERS = new HashMap<>();

    private final List<Request> requests = new ArrayList<>();
    private long consumedFrame = -1;

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

    /** Whether any pending request would consume the CustomMask this frame (a MaskFilter request,
     *  or an effect whose graph reads the Custom Mask/Depth inputs) — the pipeline skips the whole
     *  mask sub-pass otherwise, so flagged emitters cost nothing while no effect looks at them. */
    public boolean hasPendingMaskConsumer() {
        if (!effectsEnabled || !com.lowdragmc.photon.PhotonConfig.INSTANCE.enableCustomEffects.get()) return false;
        for (var request : requests) {
            if (request.params().containsKey(CompiledEffect.MASK_FILTER_PARAM)) return true;
            var effect = resolveEffect(request.effect());
            if (effect != null && effect.usesCustomMask()) return true;
        }
        return false;
    }

    /** Whether this stack already ran its chain this frame (keyed on the pool's frame clock). */
    // TODO(M3): consumeAndExecute(HDRTarget, ...) — the whole blended-chain execution (builtin bloom
    // slot-in, union masks, pooled HDR targets, final blit) — was cut with the 1.21 HDR pipeline and
    // returns as Photon frame passes via FrameGraphSetupEvent.

    public boolean isConsumedThisFrame() {
        return consumedFrame == PostFXTargetPool.currentFrame();
    }


    /** Frame boundary: recycle the displayed output and drop unconsumed (stale) requests. */
    public void onFrameEnd() {
        requests.clear();
    }

    /** Number of distinct groups one baked union mask can express (two vec4 id uniforms). */
    private static final int MAX_UNION_GROUPS = 8;

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
            // REQUEST-level opt-out of merging (the reserved "Independent" param, e.g. the clip
            // toggle): each such request runs its OWN fullscreen execution with its own
            // params/weight/mask filter (UE-material-instance-style); the rest merge as usual
            group.removeIf(request -> {
                if (!(request.params().get(CompiledEffect.INDEPENDENT_PARAM) instanceof Boolean independent)
                        || !independent) {
                    return false;
                }
                if (request.weight() >= MIN_WEIGHT) {
                    invocations.add(new Invocation(effect, request.weight(),
                            blendParams(effect, List.of(request)), requestMaskGroups(request)));
                }
                return true;
            });
            if (group.isEmpty()) return;
            group.sort(Comparator.comparingDouble(Request::weight));
            float weight = 0f;
            boolean sawMasked = false;
            boolean sawUnmasked = false;
            boolean anyGroup = false;
            var union = new HashSet<String>();
            for (var request : group) {
                weight += (1f - weight) * request.weight(); // sequential lerp of the implicit Weight=1
                var groups = requestMaskGroups(request);
                if (groups == null) {
                    sawUnmasked = true;
                } else {
                    sawMasked = true;
                    if (groups.isEmpty()) anyGroup = true;
                    else union.addAll(groups);
                }
            }
            // COVERAGE UNION across the same effect's requests (one execution per effect per frame):
            // any fullscreen request already covers every mask -> fullscreen; an "any group" request
            // covers every group; several named groups keep the exact set (baked to a union mask)
            java.util.Set<String> maskGroups = !sawMasked || sawUnmasked ? null
                    : (anyGroup ? java.util.Set.of() : union);
            if (weight < MIN_WEIGHT) return;
            invocations.add(new Invocation(effect, weight, blendParams(effect, group), maskGroups));
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

    /** 26.1: chain-wide GL state is pipeline-owned (depth/blend/color masks live on the fullscreen
     *  pass RenderPipelines) — nothing to set imperatively any more. Kept as no-ops for the M3 seam. */
    public static void setPostRenderState() {
    }

    public static void restorePostRenderState() {
    }
}
