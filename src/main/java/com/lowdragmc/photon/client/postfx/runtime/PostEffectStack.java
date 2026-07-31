package com.lowdragmc.photon.client.postfx.runtime;

import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.postfx.graph.TargetFormat;
import com.lowdragmc.photon.client.postfx.shadergraph.runtime.FullscreenGraphRuntime;
import com.lowdragmc.photon.client.render.PhotonFullscreenPass;
import com.mojang.blaze3d.textures.GpuTextureView;
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
import java.util.function.Consumer;

/**
 * The per-frame post-effect request collector + chain runner. Any source (Java API via
 * {@code PhotonPostFX}, timeline clips, other mods) {@link #submit}s activation requests each frame;
 * {@link #consumeAndExecute} — called from the view's last drain, over the HDR draw target and before it
 * composites back — groups them by effect, blends parameters by weight (Unity-Volume-style:
 * ascending-weight sequential lerp, non-lerpable take the highest weight, blended weight
 * {@code 1-Π(1-wᵢ)}), sorts by effect priority around the builtin bloom (priority 0), and runs each
 * effect once over the HDR chain — except requests carrying the reserved {@code Independent} param,
 * which skip merging and run once EACH (UE-material-instance-style).
 *
 * <p>The {@code consumedFrame} guard makes EFFECT consumption once-per-frame; bloom is not guarded,
 * because two views sharing a stack in one frame each still need it. Unconsumed requests are dropped at
 * the frame boundary ({@link #onFrameEnd}) — a source that stops submitting stops the effect next
 * frame. Render thread only.</p>
 */
public final class PostEffectStack {

    public static final PostEffectStack GLOBAL = new PostEffectStack();
    /** The editor SceneView's isolated stack — timeline previews in the editor never leak into the
     *  world render and vice versa. Views select theirs through {@code PhotonViewSettings}. */
    public static final PostEffectStack EDITOR_SCENE = new PostEffectStack();

    /** Requests below this blended weight skip the effect entirely. */
    private static final float MIN_WEIGHT = 1e-3f;
    /** Above this weight the auto final mix is a no-op and is skipped. */
    private static final float FULL_WEIGHT = 0.999f;

    private record Request(IResourcePath effect, Map<String, Object> params, float weight) {}

    /** {@code maskGroups}: null = fullscreen; empty = any mask group; else the exact set of group
     *  names — the COVERAGE UNION of the effect's requests (any unmasked request wins fullscreen;
     *  several groups bake an exact union mask at execution). */
    private record Invocation(CompiledEffect effect, float weight, Map<String, Object> params,
                              @Nullable java.util.Set<String> maskGroups, String sortKey) {}

    /** Effect priority, then a stable name — the ordering the builtin bloom slots into at 0. The name is
     *  resolved once per invocation, not inside the comparator, where it would allocate per comparison. */
    private static final Comparator<Invocation> BY_PRIORITY =
            Comparator.comparingInt((Invocation inv) -> inv.effect().priority())
                    .thenComparing(Invocation::sortKey);

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
    /** The pooled target the caller's composite is still reading; recycled at the frame boundary. */
    @Nullable
    private PostFXTargetPool.Target retiredOutput;

    /** Request {@code effect} for THIS frame. {@code params} override the effect's schema defaults by
     *  display name; {@code weight} (0..1] drives parameter blending and the auto final mix. */
    public void submit(@Nullable IResourcePath effect, Map<String, Object> params, float weight) {
        if (effect == null || weight <= 0) return;
        requests.add(new Request(effect, Map.copyOf(params), Math.min(1f, weight)));
    }

    /** Whether the global config lets effects run at all. The PER-VIEW toggle is
     *  {@code PhotonViewSettings.effects} — a stack is shared, a view's preference is not. */
    private static boolean effectsAllowed() {
        return com.lowdragmc.photon.PhotonConfig.INSTANCE.enableCustomEffects.get();
    }

    /** Whether there is work the config would actually let run — what a view checks before paying
     *  for the chain's target copy on a frame with nothing to draw. */
    public boolean wantsExecution() {
        return !requests.isEmpty() && effectsAllowed();
    }

    /** Whether any pending request would consume the CustomMask this frame (a MaskFilter request,
     *  or an effect whose graph reads the Custom Mask/Depth inputs) — the pipeline skips the whole
     *  mask sub-pass otherwise, so flagged emitters cost nothing while no effect looks at them. */
    public boolean hasPendingMaskConsumer() {
        if (!effectsAllowed()) return false;
        for (var request : requests) {
            if (request.params().containsKey(CompiledEffect.MASK_FILTER_PARAM)) return true;
            var effect = resolveEffect(request.effect());
            if (effect != null && effect.usesCustomMask()) return true;
        }
        return false;
    }

    /** Whether this stack already ran its chain this frame (keyed on the pool's frame clock). */
    public boolean isConsumedThisFrame() {
        return consumedFrame == PostFXTargetPool.currentFrame();
    }

    /**
     * Run this frame's blended effect chain over {@code inputs} and return the final target's view, or
     * null when the caller's own target is already the result (nothing requested, effects disabled, or
     * every effect skipped). The builtin bloom slots in at priority 0 — effects authored below that run
     * BEFORE it, everything else after — and runs exactly once whether or not any effect does.
     *
     * <p>The returned view belongs to a pooled target held until the frame boundary, so the caller can
     * composite from it and forget about it. Render thread only, outside any open render pass.</p>
     *
     * @param builtinBloom applies bloom in place to whatever target it is handed (null = this view
     *                     doesn't bloom)
     */
    @Nullable
    public GpuTextureView consumeAndExecute(RenderGraphExecutor.FrameInputs inputs,
                                            @Nullable Consumer<GpuTextureView> builtinBloom) {
        long frame = PostFXTargetPool.currentFrame();
        // a second view sharing this stack in the same frame still needs its bloom; only the EFFECTS
        // are once-per-frame (their requests were already consumed and cleared)
        boolean alreadyConsumed = consumedFrame == frame;
        // editors preview against the clean scene, as the chain receives it
        PostFXPreview.captureIfRequested(inputs.sceneColor(), inputs.sceneDepth());

        var invocations = alreadyConsumed || !effectsAllowed() ? List.<Invocation>of() : blendRequests();
        if (!alreadyConsumed) {
            consumedFrame = frame;
            requests.clear();
        }
        if (invocations.isEmpty()) {
            runBloom(builtinBloom, inputs.sceneColor());
            return null;
        }

        // a depth-reading effect needs the scene depth as a SAMPLEABLE texture, which the target's own
        // depth view is not always (the editor's PIP depth carries no USAGE_TEXTURE_BINDING) — resolve
        // that once here, and only when something actually reads it
        var frameInputs = inputs;
        for (var invocation : invocations) {
            if (invocation.effect().usesSceneDepth()) {
                frameInputs = inputs.withSampleableDepth();
                break;
            }
        }

        var chain = frameInputs.sceneColor();
        PostFXTargetPool.Target pooledChain = null;
        boolean bloomDone = builtinBloom == null;
        for (var invocation : invocations) {
            if (invocation.effect().passes().isEmpty()) continue; // no-op (scene passthrough) effect
            if (!bloomDone && invocation.effect().priority() >= 0) {
                runBloom(builtinBloom, chain);
                bloomDone = true;
            }

            var maskGroups = invocation.maskGroups();
            boolean masked = maskGroups != null;
            // no mask this frame: a mask-culled request applies nowhere, and a mask-READING graph must
            // not run either — it would sample a placeholder and garble the whole output
            if (frameInputs.maskColor() == null && (masked || invocation.effect().usesCustomMask())) continue;

            var params = invocation.params();
            boolean effectOwnsMask = masked && invocation.effect().declaresMaskFilter();
            if (effectOwnsMask) {
                // the graph declared a MaskFilter param = "I match the mask myself" (e.g. an outline's
                // edge pixels live OUTSIDE the mask — the universal mix would cut them): inject the
                // resolved group id and skip the mix culling below
                var owned = new HashMap<>(params);
                owned.put(CompiledEffect.MASK_FILTER_PARAM, maskGroups.size() == 1
                        ? (float) MaskGroups.idOf(maskGroups.iterator().next()) : 0f);
                params = owned;
            }

            var output = RenderGraphExecutor.execute(invocation.effect(), invocation.weight(), params,
                    frameInputs.withSceneColor(chain));
            if (output == null) continue; // broken effect: chain passes through

            // non-null exactly when the universal mix must cull by mask (the effect didn't opt to do it)
            var cullGroups = masked && !effectOwnsMask ? maskGroups : null;
            var result = output;
            if (cullGroups != null || (invocation.effect().autoBlend() && invocation.weight() < FULL_WEIGHT)) {
                var mixed = mix(invocation, chain, output, frameInputs, cullGroups);
                if (mixed != null) {
                    PostFXTargetPool.release(output);
                    result = mixed;
                }
            }

            PostFXTargetPool.release(pooledChain);
            pooledChain = result;
            chain = result.view();
        }
        if (!bloomDone) {
            runBloom(builtinBloom, chain);
        }
        // the caller composites this target right after we return, so a pooled final target stays out
        // of the pool until the frame boundary
        PostFXTargetPool.release(retiredOutput);
        retiredOutput = pooledChain;
        return pooledChain == null ? null : chain;
    }

    private static void runBloom(@Nullable Consumer<GpuTextureView> builtinBloom, GpuTextureView target) {
        if (builtinBloom != null) builtinBloom.accept(target);
    }

    /**
     * The universal fade + per-object culling: {@code mix(chain, effect, weight * match(mask))} — no
     * graph cooperation needed. {@code autoBlend=false} effects handle weight themselves, so the masked
     * mix then blends by mask match alone. Null = the mix could not run; the raw output stands.
     *
     * @param cullGroups the mask groups to cull to, or null for a plain weighted fade
     */
    @Nullable
    private static PostFXTargetPool.Target mix(Invocation invocation, GpuTextureView chain,
                                               PostFXTargetPool.Target output,
                                               RenderGraphExecutor.FrameInputs inputs,
                                               @Nullable java.util.Set<String> cullGroups) {
        var shader = CustomShaderPass.get(cullGroups != null ? WEIGHT_MASK_MIX_SHADER : WEIGHT_MIX_SHADER);
        var mixed = PostFXTargetPool.acquire(inputs.width(), inputs.height());
        if (shader == null || mixed == null) {
            PostFXTargetPool.release(mixed);
            return null;
        }
        PostFXTargetPool.Target unionMask = null;
        var maskTexture = inputs.maskColor();
        float maskFilter = 0f;
        if (cullGroups != null && cullGroups.size() == 1) {
            maskFilter = MaskGroups.idOf(cullGroups.iterator().next());
        } else if (cullGroups != null && cullGroups.size() > 1 && cullGroups.size() <= MAX_UNION_GROUPS) {
            // several groups: bake their EXACT union as a binary mask (R=1 where any of them wrote),
            // then match "any" against it — no over-coverage
            unionMask = buildUnionMask(cullGroups, inputs);
            if (unionMask != null) maskTexture = unionMask.view();
        } // empty (any group) / more than MAX groups: filter 0 on the raw mask

        var uniforms = shader.uniforms();
        uniforms.resetToDefaults();
        uniforms.set("Weight", invocation.effect().autoBlend() ? invocation.weight() : 1f);
        uniforms.set("MaskFilter", maskFilter);
        uniforms.upload();
        var boundMask = maskTexture;
        PhotonFullscreenPass.draw("photonfx mix", shader.pipeline(), mixed.view(), pass -> {
            pass.bindTexture("SamplerA", chain, RenderGraphExecutor.linearClamp());
            pass.bindTexture("SamplerB", output.view(), RenderGraphExecutor.linearClamp());
            if (cullGroups != null) {
                pass.bindTexture("MaskSampler", boundMask == null
                        ? RenderGraphExecutor.missingView() : boundMask, RenderGraphExecutor.linearClamp());
            }
            uniforms.bindTo(pass);
        });
        PostFXTargetPool.release(unionMask);
        return mixed;
    }

    /** Frame boundary: recycle the displayed output and drop unconsumed (stale) requests. */
    public void onFrameEnd() {
        PostFXTargetPool.release(retiredOutput);
        retiredOutput = null;
        requests.clear();
    }

    /** Number of distinct groups one baked union mask can express (two vec4 id uniforms). */
    private static final int MAX_UNION_GROUPS = 8;

    private static final String WEIGHT_MIX_SHADER = "photon:weight_mix";
    private static final String WEIGHT_MASK_MIX_SHADER = "photon:weight_mask_mix";
    private static final String MASK_UNION_SHADER = "photon:mask_union";

    /** Bake "mask id ∈ groups" into a pooled binary mask (R=1 where matched; R8 — one channel is all a
     *  binary mask needs). Null when the shader or the target is unavailable. */
    @Nullable
    private static PostFXTargetPool.Target buildUnionMask(java.util.Set<String> groups,
                                                          RenderGraphExecutor.FrameInputs inputs) {
        var shader = CustomShaderPass.get(MASK_UNION_SHADER);
        var target = PostFXTargetPool.acquire(inputs.width(), inputs.height(), TargetFormat.R8);
        var mask = inputs.maskColor();
        if (shader == null || target == null || mask == null) {
            PostFXTargetPool.release(target);
            return null;
        }
        var ids = new float[MAX_UNION_GROUPS];
        int count = 0;
        for (var groupName : groups) {
            if (count >= MAX_UNION_GROUPS) break;
            ids[count++] = MaskGroups.idOf(groupName);
        }
        var uniforms = shader.uniforms();
        uniforms.resetToDefaults();
        uniforms.set("IdsA", ids[0], ids[1], ids[2], ids[3]);
        uniforms.set("IdsB", ids[4], ids[5], ids[6], ids[7]);
        uniforms.set("IdCount", count);
        uniforms.upload();
        PhotonFullscreenPass.draw("photonfx mask union", shader.pipeline(), target.view(), pass -> {
            pass.bindTexture("MaskSampler", mask, RenderGraphExecutor.linearClamp());
            uniforms.bindTo(pass);
        });
        return target;
    }

    // ---- blending --------------------------------------------------------------------------------

    /** Ascending request weight — the order the sequential lerp needs. */
    private static final Comparator<Request> BY_WEIGHT = Comparator.comparingDouble(Request::weight);

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
                            blendParams(effect, List.of(request)), requestMaskGroups(request),
                            String.valueOf(effect.source())));
                }
                return true;
            });
            if (group.isEmpty()) return;
            group.sort(BY_WEIGHT);
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
            invocations.add(new Invocation(effect, weight, blendParams(effect, group), maskGroups,
                    String.valueOf(effect.source())));
        });
        invocations.sort(BY_PRIORITY);
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

}
