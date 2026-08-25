package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.compat.iris.IrisCompositeMode;
import com.lowdragmc.photon.client.compat.iris.IrisFrameTarget;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.postfx.runtime.MaskGroups;
import com.lowdragmc.photon.client.postfx.runtime.PostEffectStack;
import com.lowdragmc.photon.client.postfx.runtime.RenderGraphExecutor;
import com.lowdragmc.photon.client.postfx.runtime.SceneBlit;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL14;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Frame-scoped draw jobs for Photon's own draw slots. Every job carries the {@link PhotonStage} its
 * emitter's {@code RendererSetting.Layer} asked for, and a view drains one stage at a time:
 * <ul>
 *   <li>world: {@code RenderLevelStageEvent.AfterOpaqueFeatures} /
 *       {@code AfterTranslucentParticles} — inside the main frame pass, output targets already
 *       routed;</li>
 *   <li>LDLib2 scenes: {@code PhotonParticleManager.afterRender}, inside the scene FBO scope (see
 *       there for why the scene drains both stages at one point rather than per dispatch phase).</li>
 * </ul>
 * Within a stage, jobs sort by {@code orderInLayer}, then far-to-near — the 1.21 cross-batch
 * ordering.
 * <p>
 * Draw paths: render types with {@link PhotonRenderTypes.PhotonDrawInfo} use the vanilla-particle
 * ring-buffer pattern — the frame's vertices go once into a triple-buffered mapped GPU buffer
 * ({@link PhotonBufferCache}), and adjacent same-RenderType jobs merge into a single
 * {@code drawIndexed} (cross-emitter batching). Every RenderType Photon creates registers one — the
 * {@code RenderType.draw} branch is the safety net for a type registered without, which then keeps its
 * own draw-time hooks but takes no part in batching or bloom.
 * <p>
 * Target: every draw lands in RGBA16F storage of Photon's own, not the engine's RGBA8 output — HDR fx
 * colors above 1.0 would otherwise clamp before bloom ever sees them. Which storage depends on how the
 * result has to be merged, and a drain may use both:
 * <ul>
 *   <li><b>{@link PhotonDrawTarget}</b> — seeded from the output, drawn into against the ENGINE's depth
 *       view, and composited straight back at the end. The single clamp happens there.</li>
 *   <li><b>{@link PhotonFXLayer}</b> — cleared to transparent black instead of seeded, because the merge
 *       is a premultiplied blend somewhere else: onto the frame after the clouds
 *       ({@link FXCompositeMode#LATE}), or into a shader pack's own colortex. Every stage of a frame
 *       accumulates into ONE layer, merged once at {@link PhotonStage#LAST} — under a pack that has to
 *       happen inside the level render, before Iris consumes the buffer.</li>
 * </ul>
 * <p>
 * Bloom reads that same target — the draws are not repeated for it. What glows is decided by the luma
 * threshold, the 1.21 "everything participates by brightness" semantics, and occlusion comes free
 * because it is already resolved in the target. Runs per stage. Render-thread only.
 */
public final class PhotonWorldRenderState {

    /** One sortable draw in Photon's slot: a baked-mesh {@link Job} or an {@link InstancedJob}. */
    public sealed interface DrawJob permits Job, InstancedJob {
        /** Which frame slot this draw belongs to; a view drains one bucket per stage. */
        PhotonStage stage();

        int orderInLayer();

        float distanceSq();
    }

    /**
     * What a job contributes to the CustomMask sub-pass, or absent when its emitter isn't flagged.
     * A value record on purpose: it takes part in the run-merge test, so two emitters that share a
     * RenderType but write different mask groups still get their own draws.
     *
     * @param value        the group id in 0..1 ({@code MaskGroups.idOf(name) / 255})
     * @param alphaCutoff  0 = the mask covers the whole geometry; above 0 the sub-pass alpha-clips
     *                     against {@code clipTexture} so it hugs the sprite instead of the quad
     * @param clipTexture  the pass's own {@code Sampler0} texture (null = nothing to clip against,
     *                     which forces the cutoff off)
     */
    public record MaskWrite(float value, float alphaCutoff, @Nullable Identifier clipTexture) {

        /** The mask a flagged emitter writes; null when it isn't flagged. */
        @Nullable
        public static MaskWrite of(RendererSetting.Runtime renderer,
                                   @Nullable Identifier clipTexture) {
            if (!renderer.isWriteCustomMask()) {
                return null;
            }
            var cutoff = clipTexture == null ? 0f : renderer.getMaskAlphaCutoff();
            return new MaskWrite(
                    MaskGroups.idOf(renderer.getMaskGroup()) / 255f,
                    cutoff, clipTexture);
        }
    }

    public record Job(RenderType renderType, MeshData mesh, ByteBufferBuilder buffer,
                      PhotonStage stage, int orderInLayer, float distanceSq,
                      @Nullable MaskWrite mask) implements DrawJob {
    }

    /** An instanced draw: base geometry (sequential-quad indexed) + per-instance texel-buffer data
     *  (whole-buffer binds) + optional per-point pulling buffer. Textures/material values are
     *  carried directly — no RenderType involved. */
    public record InstancedJob(PhotonRenderTypes.PhotonDrawInfo.Programs programs,
                               InstancedGeometry geometry,
                               DrawBindings bindings,
                               Vector3f positionOffset, int blendEquation,
                               PhotonStage stage, int orderInLayer, float distanceSq,
                               PhotonPipelines.InstancedVariant variant,
                               @Nullable MaskWrite mask) implements DrawJob {
    }

    /**
     * The GPU geometry of one instanced draw: a base mesh every instance expands over, plus the
     * per-instance streams. {@code indices} null = the shared sequential-quad indices (every base mesh
     * except the ara tube ring); {@code points} is the vertex-pulling buffer trail/ara variants read;
     * {@code data}/{@code customData} are the additional-GPU-data records, present only when a material
     * on the pass reads them. {@code layout} is the divisor-attribute table {@code VertexArrayCacheMixin}
     * applies — it carries this emitter's attribute tail, so it is per-draw, not per-variant.
     */
    public record InstancedGeometry(GpuBuffer vertices, int indexCount, @Nullable GpuBuffer indices,
                                    GpuBuffer instances, int instanceCount,
                                    @Nullable GpuBuffer points,
                                    @Nullable GpuBuffer data, @Nullable GpuBuffer customData,
                                    PhotonInstancedDrawState.Layout layout) {
    }

    /**
     * The drain-side twin of {@link PhotonRenderTypes.PhotonDrawInfo.Bindings}: same role, but the
     * uniform buffers are already resolved to slices (extraction staged them; the draw only binds).
     * An instanced draw carries no RenderType, so this is the only place its bindings live.
     */
    public record DrawBindings(Map<String, Identifier> textures,
                               GpuBufferSlice materialSlice,
                               @Nullable GpuBufferSlice customSlice,
                               List<String> sceneSamplers,
                               @Nullable PhotonRenderTypes.GraphSource graph) {
    }

    /** A merged draw over the frame's ring buffer (adjacent same-RenderType, same-mask jobs). */
    private record Run(RenderType renderType, PhotonRenderTypes.PhotonDrawInfo info,
                       VertexFormat.Mode mode,
                       int baseVertex, int indexCount,
                       @Nullable MaskWrite mask) {
    }

    private record BoundTexture(String sampler, GpuTextureView view, GpuSampler samplerState) {
    }

    /** Apply a material's non-ADD blend equation as a raw-GL escape around a draw (D4-C — 26.1
     *  pipelines can't express glBlendEquation; same accepted debt as the bloom composite). */
    private static void withBlendEquation(int equation, Runnable draw) {
        if (equation == PhotonPipelines.BLEND_EQUATION_ADD) {
            draw.run();
            return;
        }
        GL14.glBlendEquation(equation);
        try {
            draw.run();
        } finally {
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        }
    }

    /** Constant draw inputs — the mask sub-pass and the fast path both build transforms from these
     *  every draw, and only the position offset ever varies. */
    private static final Vector4f NO_MODULATION = new Vector4f(1, 1, 1, 1);
    private static final Vector3f NO_OFFSET = new Vector3f();
    private static final Matrix4f IDENTITY = new Matrix4f();

    /** Views that took work this frame — the frame boundary frees whatever no drain consumed
     *  (a collector nobody drains, or a stage nobody asked for, must not leak its meshes). */
    private static final Set<IPhotonFXCollector> TRACKED_COLLECTORS =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private static final Comparator<DrawJob> DRAW_ORDER = Comparator.comparingInt(DrawJob::orderInLayer)
            .thenComparing(Comparator.comparingDouble(DrawJob::distanceSq).reversed());

    /** Instance rings written this frame — rotated once at the frame boundary, after the last drain
     *  that may read them. */
    private static final Set<PhotonInstanceRing> USED_RINGS =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /** Shared instanced base quads (POSITION format, sequential-quad indexed, z 0). */
    @Nullable
    private static GpuBuffer tileQuad;
    @Nullable
    private static GpuBuffer beamQuad;
    @Nullable
    private static GpuBuffer segmentQuad;

    /** 2-float corner quads (the 1.21 aPos vec2 layouts); tile keeps 3 floats. */
    private static GpuBuffer corners2(String label, float... xy) {
        var bytes = MemoryUtil.memAlloc(xy.length * Float.BYTES);
        try {
            for (var v : xy) {
                bytes.putFloat(v);
            }
            bytes.flip();
            return RenderSystem.getDevice().createBuffer(() -> label, GpuBuffer.USAGE_VERTEX, bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    private static GpuBuffer corners(String label, float... xy) {
        var bytes = MemoryUtil.memAlloc(4 * 3 * Float.BYTES);
        try {
            for (int i = 0; i < 4; i++) {
                bytes.putFloat(xy[i * 2]).putFloat(xy[i * 2 + 1]).putFloat(0);
            }
            bytes.flip();
            return RenderSystem.getDevice().createBuffer(() -> label, GpuBuffer.USAGE_VERTEX, bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    /** Billboard corners ±1 (the 1.21 instanced quad). */
    public static GpuBuffer tileQuad() {
        if (tileQuad == null) {
            tileQuad = corners("Photon tile quad", 1, -1, 1, 1, -1, 1, -1, -1);
        }
        return tileQuad;
    }

    /** Beam corners: x = start/end, y = side — CPU emission order p1(from-n) p0(from+n) p4(end+n) p3(end-n). */
    public static GpuBuffer beamQuad() {
        if (beamQuad == null) {
            beamQuad = corners2("Photon beam quad", 0, -1, 0, 1, 1, 1, 1, -1);
        }
        return beamQuad;
    }

    /** Trail segment corners: x = curr/next, y = up/down. Perimeter (curr-up, curr-down, next-down,
     *  next-up): sequential triangulation covers 1.21's (0,1,2),(1,3,2) with the same CCW winding. */
    public static GpuBuffer segmentQuad() {
        if (segmentQuad == null) {
            segmentQuad = corners2("Photon segment quad", 0, 1, 0, -1, 1, -1, 1, 1);
        }
        return segmentQuad;
    }

    @Nullable
    private static GpuBuffer araQuad;

    /** Ara flat-ribbon corners. 1.21 triangulates (N−,C−,C+),(C+,N+,N−) — the OPPOSITE winding to
     *  the trail quad — so the perimeter (next−, curr−, curr+, next+) reproduces it exactly under
     *  sequential quad indices. */
    public static GpuBuffer araQuad() {
        if (araQuad == null) {
            araQuad = corners2("Photon ara quad", 1, -1, 0, -1, 0, 1, 1, 1);
        }
        return araQuad;
    }

    /** Pooled ring buffers (vanilla ParticleFeatureRenderer pattern): acquired per drain,
     *  rotated + recycled at the frame boundary. */
    private static final Queue<PhotonBufferCache> AVAILABLE_CACHES = new ArrayDeque<>();
    private static final List<PhotonBufferCache> USED_CACHES = new ArrayList<>();

    private PhotonWorldRenderState() {
    }

    /** Register a view that was handed bake tasks this frame (called from the submit phase). */
    public static void trackCollector(IPhotonFXCollector collector) {
        TRACKED_COLLECTORS.add(collector);
    }

    /** Register an instance ring written this frame for the frame-boundary rotate. */
    public static void trackInstanceRing(PhotonInstanceRing ring) {
        USED_RINGS.add(ring);
    }

    /** Resources whose owners were rebuilt mid-frame: queued draws may still reference them, so
     *  they close at the frame boundary instead of immediately. */
    private static final List<AutoCloseable> CLOSE_AT_FRAME_END = new ArrayList<>();

    public static void closeAtFrameEnd(AutoCloseable resource) {
        CLOSE_AT_FRAME_END.add(resource);
    }

    /**
     * Bake one view's collected tasks and draw them. The view's {@link PhotonViewSettings} are known
     * here — which is the whole reason baking is deferred to this point — so a wireframe-only view
     * never generates shaded geometry, and a collector nobody drains never generates anything.
     */
    public static void drain(IPhotonFXCollector collector, PhotonStage stage) {
        var tasks = collector.photonFXTasks();
        var baked = collector.photonFXBaked();
        var settings = collector.photonViewSettings();
        if (!tasks.isEmpty()) {
            // first drain of the frame for this view: generate every stage's geometry once, with
            // this view's settings in hand — which is the whole reason baking is deferred here
            try {
                for (var task : tasks) {
                    task.bake(settings, baked);
                }
            } finally {
                // a bake that threw must not leave every later pipeline premultiplied
                PremultipliedBlendPlan.setAccumulating(false);
            }
            tasks.clear();
            // Ask for the opaque-depth snapshot here, at the FIRST drain of the frame: the capture is
            // taken right after the opaque drain, well before the translucent one that actually owns
            // the deferred layer. Demanding from that later drain would always land one frame late, and
            // the first frame of every effect would test against the live depth and be sliced by water.
            // Not under a shader pack: there the layer is merged mid-frame and tests against the live
            // depth, so the snapshot would be a full-screen blit nobody reads.
            if (!IrisCompat.isUsingShaderPack()) {
                for (var job : baked) {
                    if (job.stage() == PhotonStage.DEFERRED) {
                        OpaqueDepthCapture.demand();
                        break;
                    }
                }
            }
        }
        // Both buckets are collected — and DRAWN — at the translucent seam. The deferred one only
        // differs in where it lands (its own layer) and when it is merged (after the level render).
        // Drawing it later would put the geometry outside the level pass, where the modelview stack has
        // been unwound and the camera uniforms no longer describe the view. 1.21 made the same split
        // inside one build (inlineGroup / lateGroup) for the same reason.
        var inlineJobs = new ArrayList<DrawJob>();
        var layerJobs = new ArrayList<DrawJob>();
        var deferredBelongsHere = stage == PhotonStage.AFTER_TRANSLUCENT_PARTICLES;
        baked.removeIf(job -> {
            if (job.stage() == stage) {
                inlineJobs.add(job);
                return true;
            }
            if (deferredBelongsHere && job.stage() == PhotonStage.DEFERRED) {
                layerJobs.add(job);
                return true;
            }
            return false;
        });

        // Under a shader pack there is no in-place path at all: the frame belongs to the pack, so every
        // translucent draw becomes a layer that is merged into the pack's own target.
        var packLayer = deferredBelongsHere && PremultipliedBlendPlan.isLayerStage(stage);
        if (packLayer) {
            layerJobs.addAll(inlineJobs);
            inlineJobs.clear();
        }
        // The chain must see a frame that contains the FX, and a layer is merged after this drain — so
        // whenever one exists the chain is handed to PhotonPostFX to run after the composite instead.
        // It must never run over the layer itself: that holds the FX alone, not the picture.
        var runChainHere = stage == PhotonStage.LAST && layerJobs.isEmpty();

        // The post-effect chain runs on this view's LAST stage, so it sees everything the view drew —
        // and runs even with no jobs at all, because an effect is a property of the frame, not of
        // Photon having rendered something (a screen-wide colour grade must not blink off the moment
        // the last particle dies). The MASK, in contrast, is written on every stage: both stages'
        // flagged emitters accumulate into it before the chain reads it.

        // A view that runs on its own clock (the editor timeline) gets Minecraft's Globals block
        // substituted for a copy carrying THAT clock, so `GameTime` means the timeline for everything
        // Photon draws here — hand-written shaders and KilaGraph's Globals node alike, with no shader
        // change. Nothing is substituted for the world, where the engine's own block is already right.
        var substituted = PhotonGlobals.substitute();
        try {
            var bloom = PhotonConfig.INSTANCE.enableBloom.get() && settings.bloom();
            var stack2 = settings.effects() ? settings.postEffects() : null;
            drain(inlineJobs, bloom, stack2, runChainHere, false, false);
            // the layer never runs the chain (it is FX-only) and only takes the opaque snapshot on the
            // plain path — under a pack it is merged mid-frame and must test against the live depth
            drain(layerJobs, bloom, stack2, false, true, !packLayer);
            if (stage == PhotonStage.LAST) {
                // Every Photon stage of this frame has now drawn into the shared layer, so this is the
                // moment to merge it — and it must be inside the level render, because under a pack Iris
                // consumes its colortex at the end of renderLevel.
                finishLayer(outputSize());
            }
        } finally {
            if (substituted) {
                PhotonGlobals.restore(); // leaving ours bound would freeze the rest of the frame
            }
        }
    }

    private static void drain(List<DrawJob> jobs, boolean bloomEnabled,
                              @Nullable PostEffectStack stack, boolean lastStage, boolean layerMode,
                              boolean opaqueDepth) {
        // the chain runs on the view's last stage only; the MASK is written on every stage
        var runChain = lastStage && stack != null && stack.wantsExecution();
        if (jobs.isEmpty() && !runChain) {
            return;
        }
        jobs.sort(DRAW_ORDER);

        // one mapped write for every fast-path job's vertices, back-to-back
        var blocks = new ArrayList<ByteBuffer>();
        var totalBytes = 0;
        for (var drawJob : jobs) {
            if (drawJob instanceof Job job && PhotonRenderTypes.drawInfo(job.renderType()) != null) {
                var vertices = job.mesh().vertexBuffer();
                blocks.add(vertices);
                totalBytes += vertices.remaining();
            }
        }
        PhotonBufferCache cache = null;
        if (totalBytes > 0) {
            cache = AVAILABLE_CACHES.poll();
            if (cache == null) {
                cache = new PhotonBufferCache();
            }
            USED_CACHES.add(cache);
            cache.write(blocks, totalBytes);
        }

        var outputColor = PhotonRenderOutput.color();
        GpuTextureView depthTexture = PhotonRenderOutput.depth();

        // Every draw below goes into Photon's own HDR target rather than the engine's RGBA8 output:
        // seed it with the scene (blended fx need the real background), draw, then composite back at
        // the end of the drain. Depth stays the engine's, so occlusion is unaffected.
        //
        // The deferred stage is the exception on both counts: its layer starts EMPTY (the composite
        // blends it on later, so seeding it with the scene would draw the scene twice) and it tests
        // against the opaque-only depth snapshot (so a water surface cannot slice an effect in half).
        PhotonDrawTarget drawTarget = null;
        PhotonFXLayer layer = null;
        GpuTextureView colorTexture;
        if (layerMode) {
            layer = PhotonFXLayer.acquire(outputColor.getWidth(0), outputColor.getHeight(0));
            if (layer == null) {
                release(jobs);
                return;
            }
            layer.beginFrame();
            colorTexture = layer.view();
            if (opaqueDepth) {
                // LATE only. Under a shader pack the layer is merged at the translucent stage, still in
                // the middle of the frame, so its FX must test against the LIVE depth exactly as an
                // in-place draw would — substituting the opaque snapshot there would put them in front
                // of water nobody asked to see through.
                var snapshot = OpaqueDepthCapture.view();
                if (snapshot != null) {
                    depthTexture = snapshot;
                }
            }
        } else {
            drawTarget = PhotonDrawTarget.acquire(outputColor.getWidth(0), outputColor.getHeight(0));
            if (drawTarget == null) {
                release(jobs); // nothing to draw into — free the meshes rather than leak them
                return;
            }
            drawTarget.copyFrom(outputColor);
            colorTexture = drawTarget.view();
        }

        // pre-fx scene captures (the 1.21 "scene texture"): taken BEFORE any fx draws, consumed
        // by wireframe-inverse pipelines and SamplerScene* custom-shader samplers
        GpuTextureView sceneColor = null;
        GpuTextureView sceneDepth = null;
        var needColor = false;
        var needDepth = false;
        for (var drawJob : jobs) {
            List<String> scene;
            boolean wf;
            if (drawJob instanceof InstancedJob ij) {
                wf = PhotonPipelines.isWireframe(ij.programs().main());
                scene = ij.bindings().sceneSamplers();
            } else {
                var info = PhotonRenderTypes.drawInfo(((Job) drawJob).renderType());
                if (info == null) continue;
                wf = PhotonPipelines.isWireframe(info.programs().main());
                scene = info.bindings().sceneSamplers();
            }
            needColor |= wf;
            for (var name : scene) {
                if (name.contains("Depth")) needDepth = true;
                else needColor = true;
            }
        }
        // Captured from the OUTPUT, not from colorTexture: in layer mode colorTexture is the empty
        // layer, and an effect sampling SamplerSceneColor would read transparent black instead of the
        // scene. In the in-place mode the two hold the same pixels (the target was just seeded from the
        // output), so this is the same capture it always was.
        if (needColor) sceneColor = PhotonSceneCapture.captureColor(outputColor);
        if (needDepth) sceneDepth = PhotonSceneCapture.captureDepth(depthTexture);

        // main draws in sorted order; adjacent fast jobs with the same RenderType merge into one
        // draw; every fast/instanced draw is recorded for the bloom pass
        var runs = new ArrayList<Run>();
        var instancedJobs = new ArrayList<InstancedJob>();
        var baseVertex = 0;
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i) instanceof InstancedJob instanced) {
                instancedJobs.add(instanced);
                drawInstancedJob(instanced, instanced.programs().main(), colorTexture, depthTexture, sceneColor, sceneDepth);
                continue;
            }
            var job = (Job) jobs.get(i);
            var info = PhotonRenderTypes.drawInfo(job.renderType());
            if (info == null) {
                job.renderType().draw(job.mesh()); // consumes + closes the mesh
                job.buffer().close();
                continue;
            }
            var mode = job.mesh().drawState().mode();
            var vertexCount = job.mesh().drawState().vertexCount();
            var indexCount = job.mesh().drawState().indexCount();
            while (i + 1 < jobs.size()) {
                if (!(jobs.get(i + 1) instanceof Job next)
                        || next.renderType() != job.renderType()
                        // the mask sub-pass redraws a whole run with ONE group id, so jobs that write
                        // different masks must stay separate draws even when they share everything else
                        || !Objects.equals(next.mask(), job.mask())
                        || PhotonRenderTypes.drawInfo(next.renderType()) == null) {
                    break;
                }
                i++;
                vertexCount += next.mesh().drawState().vertexCount();
                indexCount += next.mesh().drawState().indexCount();
                closeFastJob(next);
            }
            var run = new Run(job.renderType(), info, mode, baseVertex, indexCount, job.mask());
            runs.add(run);
            drawRun(cache, run, run.info().programs().main(), colorTexture, depthTexture, sceneColor, sceneDepth);
            baseVertex += vertexCount;
            closeFastJob(job);
        }
        jobs.clear();

        // Bloom reads the HDR target the draws just landed in — no second geometry pass. That replay only
        // existed because pre-S3 the draws went to the engine's RGBA8 output, where anything above 1.0 was
        // already clamped away, so bloom needed its own un-clamped copy of the fx. Reading the target also
        // makes occlusion free (it is already resolved in there) and matches 1.21, whose bloom ran over the
        // whole DRAW_TARGET: what participates is decided by the luma threshold, not by who drew it.
        var drewSomething = !runs.isEmpty() || !instancedJobs.isEmpty();
        Consumer<GpuTextureView> bloomStep = !bloomEnabled || !drewSomething ? null : target -> {
            var bloom = PhotonBloom.acquire(target.getWidth(0), target.getHeight(0));
            if (bloom != null) {
                bloom.run(target);
            }
        };

        // CustomMask: redraw the flagged jobs as flat ids into their own target. Demand-driven — a
        // flagged emitter costs nothing on a frame when no effect looks at the mask.
        var mask = maskSubPass(cache, runs, instancedJobs, stack, colorTexture, depthTexture);

        if (runChain) {
            // the chain slots bloom in at effect priority 0 and hands back its final target (null when
            // nothing changed the picture); depth is the engine's, which is what SCENE_DEPTH passes read
            var inputs = RenderGraphExecutor.FrameInputs
                    .of(colorTexture, depthTexture);
            if (mask != null) {
                inputs = inputs.withMask(mask.colorView(), mask.depthView());
            }
            var chain = stack.consumeAndExecute(inputs, bloomStep);
            if (chain != null) {
                SceneBlit.writeBack(chain, colorTexture);
            }
        } else if (bloomStep != null) {
            bloomStep.accept(colorTexture);
        }

        if (layer == null) {
            drawTarget.compositeTo(outputColor);
        }
        // A layer is NOT merged here: every stage of the frame accumulates into the same one, so the
        // merge belongs to the frame's last stage. finishLayer() does it.
    }

    /** The frame's output size, which is what the shared layer is pooled by. */
    private static long outputSize() {
        var output = PhotonRenderOutput.color();
        return ((long) output.getWidth(0) << 32) | (output.getHeight(0) & 0xFFFFFFFFL);
    }

    /**
     * Merge the frame's FX layer, if anything drew into one.
     *
     * <p>Under a pack that means the colortex its particle program writes — which is what lets the
     * pack's own bloom/DOF/fog reach Photon's FX. A pack whose particle target holds encoded gbuffer
     * data instead ({@code AFTER_PACK}) has no colour buffer to write, so its layer is held back and
     * merged onto the finished frame at the post-level seam; so is {@link FXCompositeMode#LATE} on the
     * plain path, for its own reason.
     */
    private static void finishLayer(long size) {
        var layer = PhotonFXLayer.inUse((int) (size >>> 32), (int) size);
        if (layer == null) {
            return; // this view never layered anything — the editor scene, or a frame with no fx
        }
        var frame = IrisCompat.isUsingShaderPack() ? IrisCompat.resolveFrameTarget(true) : null;
        if (frame != null && frame.compositeMode() == IrisCompositeMode.DISABLED) {
            // The one mode that means "show nothing": a layout we refuse to write to, or the user's own
            // /photon_iris mode disabled. Dropping the layer is the whole point — parking it would put
            // the FX back on screen and make the switch look broken.
            return;
        }
        var packTarget = frame == null ? null : irisCompositeTarget(frame);
        if (packTarget != null) {
            // its alpha IS the coverage the pack blends with, unless the target is the scene colour
            layer.compositeTo(packTarget.view(), packTarget.writeAlpha());
        } else {
            // no pack, an unresolvable layout, or AFTER_PACK — merge onto the finished frame instead
            PhotonDeferredLayer.park(layer);
        }
    }

    /** Where a shader pack wants this frame's FX layer merged <i>now</i>. */
    private record PackTarget(GpuTextureView view, boolean writeAlpha) {}

    /**
     * The pack target to merge into during this drain, or null to hold the layer back for the
     * post-level seam.
     *
     * <p><b>The composite mode decides, not merely "is there a texture".</b> Only
     * {@link IrisCompositeMode#PREMULTIPLIED_ACCUM} and {@link IrisCompositeMode#SCENE_REPLACE} name a
     * buffer that means COLOUR. {@link IrisCompositeMode#AFTER_PACK} is the resolver saying the
     * opposite — the pack's particle program writes packed gbuffer data there and decodes it later
     * (Kappa, iterationRP, Photon/SixthSurge: {@code blend = off} on a non-float, non-scene target) —
     * so writing the FX into it puts them somewhere the pack is about to overwrite or misinterpret,
     * and they vanish. Those hold back and are merged onto the finished frame instead, which is the
     * honest trade the mode documents.
     *
     * <p>Reading {@code canComposite()} here was exactly that mistake: it answers "can these FX be
     * shown at all", which is true for AFTER_PACK too — the showing just happens somewhere else.
     */
    @Nullable
    private static PackTarget irisCompositeTarget(IrisFrameTarget frame) {
        if (!frame.canComposite()) return null;
        var mode = frame.compositeMode();
        if (mode != IrisCompositeMode.PREMULTIPLIED_ACCUM && mode != IrisCompositeMode.SCENE_REPLACE) {
            return null; // AFTER_PACK (and anything new that is not a colour target): merge after the pack
        }
        var view = IrisCompat.compositeTarget(frame);
        // The pack's scene colour owns its alpha channel; a separate translucent accumulator does not —
        // there the alpha is the coverage the pack's own blend pass reads back, so it must be written.
        return view == null ? null : new PackTarget(view, !frame.primaryIsSceneColor());
    }

    /**
     * The CustomMask sub-pass: every flagged run/instanced job redrawn as a flat group id into
     * {@link PhotonMaskTarget}. Returns the target when it holds this frame's mask, else null.
     * <p>
     * Skipped entirely — no target, no clear, no draws — unless something flagged is on screen AND some
     * pending effect this frame actually reads the mask ({@code hasPendingMaskConsumer}). That is what
     * lets an author leave the flag on permanently: it only costs anything while an effect uses it.
     */
    @Nullable
    private static PhotonMaskTarget maskSubPass(
            @Nullable PhotonBufferCache cache, List<Run> runs, List<InstancedJob> instancedJobs,
            @Nullable PostEffectStack maskStack,
            GpuTextureView colorTexture, GpuTextureView depthTexture) {
        if (maskStack == null || !maskStack.hasPendingMaskConsumer() || !anyMask(runs, instancedJobs)) {
            // nothing flagged on THIS stage — but an earlier one of the same frame may have written the
            // mask already, and the chain still has to see it
            return PhotonMaskTarget.writtenThisFrame(colorTexture.getWidth(0), colorTexture.getHeight(0));
        }
        var target = PhotonMaskTarget.acquire(colorTexture.getWidth(0), colorTexture.getHeight(0), depthTexture);

        // Resolve every draw BEFORE opening the pass — see resolveMask for why that split is mandatory.
        var draws = new ArrayList<MaskDraw>();
        for (var run : runs) {
            if (run.mask() != null && cache != null) {
                draws.add(new MaskDraw(PhotonPipelines.mask(null, run.mode()),
                        resolveMask(run.mask(), NO_OFFSET), run, null,
                        sequentialIndices(run.mode(), run.indexCount())));
            }
        }
        for (var job : instancedJobs) {
            if (job.mask() != null) {
                draws.add(new MaskDraw(PhotonPipelines.mask(job.variant(), VertexFormat.Mode.QUADS),
                        resolveMask(job.mask(), job.positionOffset()), null, job,
                        indicesFor(job.geometry())));
            }
        }

        // Then one pass for all of them. The attachments never change between draws, so a pass each would
        // only re-bind the same framebuffer N times; pipeline, id block and clip texture are per-draw state
        // a single pass changes freely. The colour clear rides along as this pass's own load op on the
        // frame's first sub-pass, which is why the target needs no pass of its own just to clear.
        var clear = target.takeClear();
        try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Photon custom mask", target.colorView(), clear ? OptionalInt.of(0) : OptionalInt.empty(),
                target.depthView(), OptionalDouble.empty())) {
            applyScissor(renderPass);
            RenderSystem.bindDefaultUniforms(renderPass);
            for (var draw : draws) {
                draw.bindings().bind(renderPass, draw.pipeline());
                if (draw.run() != null) {
                    renderPass.setVertexBuffer(0, cache.get());
                    renderPass.setIndexBuffer(draw.indices().buffer(), draw.indices().type());
                    renderPass.drawIndexed(draw.run().baseVertex(), 0, draw.run().indexCount(), 1);
                } else {
                    drawMaskInstanced(renderPass, draw);
                }
            }
        }
        target.markWritten();
        return target;
    }

    private static boolean anyMask(List<Run> runs, List<InstancedJob> instancedJobs) {
        for (var run : runs) {
            if (run.mask() != null) return true;
        }
        for (var job : instancedJobs) {
            if (job.mask() != null) return true;
        }
        return false;
    }

    /**
     * One mask draw, with every allocating lookup already done. Exactly one of {@code run}/{@code job}
     * is set — they differ only in where the geometry comes from.
     */
    private record MaskDraw(RenderPipeline pipeline, MaskBindings bindings, @Nullable Run run,
                            @Nullable InstancedJob job, Indices indices) {
    }

    /** What a mask draw binds. Every one of these three is resolved BEFORE the pass opens, because each
     *  allocates or uploads on first use — see {@link #resolveMask}. */
    private record MaskBindings(GpuBufferSlice transforms, GpuBufferSlice block, BoundTexture clip) {

        void bind(RenderPass renderPass, RenderPipeline pipeline) {
            renderPass.setPipeline(pipeline);
            renderPass.setUniform("DynamicTransforms", transforms);
            renderPass.setUniform("PhotonMask", block);
            renderPass.bindTexture("Sampler0", clip.view(), clip.samplerState());
        }
    }

    /**
     * The pre-pass half of a mask draw. All three lookups can allocate or upload the first time they see
     * a value — the transform appends to the dynamic-uniform buffer, an unseen (group, cutoff) pair
     * creates and fills its block, an unseen clip texture is loaded to the GPU — and all three are
     * illegal once a render pass is open.
     */
    private static MaskBindings resolveMask(MaskWrite mask, Vector3f positionOffset) {
        return new MaskBindings(
                RenderSystem.getDynamicUniforms().writeTransform(
                        RenderSystem.getModelViewMatrix(), NO_MODULATION, positionOffset, IDENTITY),
                PhotonMaskUniforms.sliceFor(mask),
                resolveMaskClip(mask));
    }

    private static void drawMaskInstanced(RenderPass renderPass, MaskDraw draw) {
        var job = draw.job();
        if (job.geometry().points() != null) {
            renderPass.setUniform("PhotonPoints", job.geometry().points());
        }
        renderPass.setVertexBuffer(0, job.geometry().vertices());
        renderPass.setIndexBuffer(draw.indices().buffer(), draw.indices().type());
        PhotonInstancedDrawState.begin(job.geometry().layout(), job.geometry().instances(),
                job.geometry().vertices());
        try {
            renderPass.drawIndexed(0, 0, job.geometry().indexCount(), job.geometry().instanceCount());
        } finally {
            PhotonInstancedDrawState.end();
        }
    }

    /** The shared sequential quad/strip indices for a CPU-baked run. */
    private static Indices sequentialIndices(VertexFormat.Mode mode, int indexCount) {
        var autoIndices = RenderSystem.getSequentialBuffer(mode);
        return new Indices(autoIndices.getBuffer(indexCount), autoIndices.type());
    }

    /** The base mesh's own index buffer when it has one (the ara tube ring shares vertices between
     *  adjacent section edges, which the shared quad pattern can't express), else the shared quads. */
    private record Indices(GpuBuffer buffer, VertexFormat.IndexType type) {
    }

    private static Indices indicesFor(InstancedGeometry geometry) {
        if (geometry.indices() != null) {
            return new Indices(geometry.indices(), VertexFormat.IndexType.INT);
        }
        var autoIndices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        return new Indices(autoIndices.getBuffer(geometry.indexCount()), autoIndices.type());
    }

    /** The texture the alpha clip samples — resolved before the pass opens (first use uploads), and
     *  the missing texture when the pass has none (the shader then never samples it: cutoff is 0). */
    private static BoundTexture resolveMaskClip(MaskWrite mask) {
        var id = mask.clipTexture() != null ? mask.clipTexture()
                : MissingTextureAtlasSprite.getLocation();
        var texture = Minecraft.getInstance().getTextureManager().getTexture(id);
        return new BoundTexture("Sampler0", texture.getTextureView(), texture.getSampler());
    }

    private static void applyScissor(RenderPass renderPass) {
        var scissor = RenderSystem.getScissorStateForRenderTypeDraws();
        if (scissor.enabled()) {
            renderPass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
        }
    }

    /** The instanced flavor of {@link #drawRun}: base quad + texel-buffer instance data,
     *  {@code drawIndexed(instanceCount)}. Same binding sequence and pre-pass texture resolution. */
    private static void drawInstancedJob(InstancedJob job, RenderPipeline pipeline,
                                         GpuTextureView colorTexture, GpuTextureView depthTexture,
                                @Nullable GpuTextureView sceneColor, @Nullable GpuTextureView sceneDepth) {
        // ModelOffset carries the facing-eye → render-origin delta: trail/beam facing math runs on
        // EYE-relative data (the editor SceneCamera's position() is ZERO, its true eye is sceneEye),
        // the shader adds the offset back into render-origin space. Zero in-world.
        var dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrix(),
                new Vector4f(1, 1, 1, 1),
                job.positionOffset(), new Matrix4f());
        if (job.bindings().graph() != null) {
            job.bindings().graph().material().prepareUniforms();
        }
        var indices = indicesFor(job.geometry());

        // resolve textures BEFORE opening the pass (first use triggers a GPU upload)
        var textureManager = Minecraft.getInstance().getTextureManager();
        var boundTextures = new ArrayList<BoundTexture>(job.bindings().textures().size());
        for (var entry : job.bindings().textures().entrySet()) {
            var texture = textureManager.getTexture(entry.getValue());
            boundTextures.add(new BoundTexture(entry.getKey(), texture.getTextureView(), texture.getSampler()));
        }
        withBlendEquation(job.blendEquation(), () -> {
        try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Photon fx instanced", colorTexture, OptionalInt.empty(), depthTexture, OptionalDouble.empty())) {
            renderPass.setPipeline(pipeline);
            applyScissor(renderPass);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setUniform("PhotonMaterial", job.bindings().materialSlice());
            var engineSlice = PhotonEngineUniforms.currentSlice();
            if (engineSlice != null) {
                renderPass.setUniform("PhotonEngine", engineSlice); // custom fsh U_* block
            }
            if (job.bindings().customSlice() != null) {
                renderPass.setUniform("PhotonCustomMaterial", job.bindings().customSlice());
            }
            if (job.geometry().points() != null) {
                renderPass.setUniform("PhotonPoints", job.geometry().points());
            }
            // additional GPU data (the shadergraph photon_data_*() / photon_custom_data() accessors);
            // present only when a material on the pass reads it, and then every variant's pipeline that
            // declares it is drawn from this same job
            if (job.geometry().data() != null) {
                renderPass.setUniform("PhotonData", job.geometry().data());
            }
            if (job.geometry().customData() != null) {
                renderPass.setUniform("PhotonCustomData", job.geometry().customData());
            }
            for (var bound : boundTextures) {
                renderPass.bindTexture(bound.sampler(), bound.view(), bound.samplerState());
            }
            // context-sensitive: level lightmap during world rendering, the 1x1 white UI lightmap
            // during GUI (editor PIP) — the 26.1 engine's own convention, and exactly the 1.21
            // editor full-bright semantics (particle.vsh samples via sample_lightmap, clamped)
            renderPass.bindTexture("Sampler2", Minecraft.getInstance().gameRenderer.lightmap(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            if (job.bindings().graph() != null) {
                job.bindings().graph().material().bindCustomUniforms(renderPass);
            }
            // Photon-owned scene samplers, after the graph's own binds — see the note in drawRun.
            if (sceneColor != null && PhotonPipelines.isWireframe(pipeline)) {
                renderPass.bindTexture("SamplerSceneColor", sceneColor, PhotonSceneCapture.sampler());
            }
            for (var name : job.bindings().sceneSamplers()) {
                var view = name.contains("Depth") ? sceneDepth : sceneColor;
                if (view != null) {
                    renderPass.bindTexture(name, view, PhotonSceneCapture.sampler());
                }
            }
            renderPass.setVertexBuffer(0, job.geometry().vertices());
            renderPass.setIndexBuffer(indices.buffer(), indices.type());
            // C1/C2: the 1.21 divisor attributes + RGBA32F texel respec apply inside this draw
            PhotonInstancedDrawState.begin(job.geometry().layout(), job.geometry().instances(), job.geometry().vertices());
            try {
                renderPass.drawIndexed(0, 0, job.geometry().indexCount(), job.geometry().instanceCount());
            } finally {
                PhotonInstancedDrawState.end();
            }
        }
        });
    }

    /** The vanilla RenderType.draw binding sequence, minus the immediate-buffer upload: vertices
     *  come from the frame's ring buffer at the run's base vertex. */
    private static void drawRun(PhotonBufferCache cache, Run run, RenderPipeline pipeline,
                                GpuTextureView colorTexture, GpuTextureView depthTexture,
                                @Nullable GpuTextureView sceneColor, @Nullable GpuTextureView sceneDepth) {
        var dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrix(),
                new Vector4f(1, 1, 1, 1),
                new Vector3f(), new Matrix4f());
        var autoIndices = RenderSystem.getSequentialBuffer(run.mode());
        GpuBuffer indices = autoIndices.getBuffer(run.indexCount());

        // resolve textures BEFORE opening the pass: first use of a texture triggers registerAndLoad
        // (a GPU upload), which is illegal while a render pass is open
        var textureManager = Minecraft.getInstance().getTextureManager();
        var boundTextures = new ArrayList<BoundTexture>(run.info().bindings().textures().size());
        for (var entry : run.info().bindings().textures().entrySet()) {
            var texture = textureManager.getTexture(entry.getValue());
            boundTextures.add(new BoundTexture(entry.getKey(), texture.getTextureView(), texture.getSampler()));
        }
        // KilaGraph graph materials: upload their UBOs + resolve their textures BEFORE the pass (both are
        // illegal inside one), then bind them in it — KG's own draw hook never fires for a pass we opened.
        var graph = run.info().bindings().graph();
        if (graph != null) {
            graph.material().prepareUniforms();
        }
        var recipe = run.info().instanced();
        var key = recipe == null ? null : recipe.key();
        withBlendEquation(key != null ? key.blendEquation() : PhotonPipelines.BLEND_EQUATION_ADD, () -> {
        try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Photon fx slot", colorTexture, OptionalInt.empty(), depthTexture, OptionalDouble.empty())) {
            renderPass.setPipeline(pipeline);
            applyScissor(renderPass);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            var materialSlice = PhotonMaterialUniforms.sliceFor(run.renderType());
            if (materialSlice != null) {
                renderPass.setUniform("PhotonMaterial", materialSlice);
            }
            // custom shaders register their own slice; shader graphs reference the block through
            // PhotonShaderCompiler.viewport() without registering, so fall back to the frame's own
            var engineSlice = PhotonEngineUniforms.sliceFor(run.renderType());
            if (engineSlice == null) {
                engineSlice = PhotonEngineUniforms.currentSlice();
            }
            if (engineSlice != null) {
                renderPass.setUniform("PhotonEngine", engineSlice);
            }
            var custom = run.info().bindings().customUniforms();
            if (custom != null && custom.slice() != null) {
                renderPass.setUniform("PhotonCustomMaterial", custom.slice());
            }
            for (var bound : boundTextures) {
                renderPass.bindTexture(bound.sampler(), bound.view(), bound.samplerState());
            }
            renderPass.bindTexture("Sampler2", Minecraft.getInstance().gameRenderer.lightmap(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            if (graph != null) {
                graph.material().bindCustomUniforms(renderPass);
            }
            // MUST stay after bindCustomUniforms — this is load-bearing, not style. Scene samplers are
            // Photon's to bind, but KilaGraph's material also re-binds every sampler in the graph's layout
            // each draw, and its "skip the scene ones" guard compares against KG_SceneColor/KG_SceneDepth
            // while the layout holds the name Photon's compiler renamed them to. So they slip through as
            // ordinary material textures whose default is the missing texture: bind before this call and
            // every Scene Color graph renders missingno. Photon 1.21 solved it the same way — its
            // ShaderGraphMaterial.begin() set the scene samplers last, after KilaGraph's values.
            if (sceneColor != null && PhotonPipelines.isWireframe(pipeline)) {
                renderPass.bindTexture("SamplerSceneColor", sceneColor, PhotonSceneCapture.sampler());
            }
            for (var name : run.info().bindings().sceneSamplers()) {
                var view = name.contains("Depth") ? sceneDepth : sceneColor;
                if (view != null) {
                    renderPass.bindTexture(name, view, PhotonSceneCapture.sampler());
                }
            }
            renderPass.setVertexBuffer(0, cache.get());
            renderPass.setIndexBuffer(indices, autoIndices.type());
            renderPass.drawIndexed(run.baseVertex(), 0, run.indexCount(), 1);
        }
        });
    }

    private static void closeFastJob(Job job) {
        job.mesh().close();
        job.buffer().close();
    }

    /** Frame boundary: rotate + recycle the frame's ring buffers, free anything no drain consumed. */
    public static void endFrame() {
        for (var usedCache : USED_CACHES) {
            usedCache.rotate();
        }
        AVAILABLE_CACHES.addAll(USED_CACHES);
        USED_CACHES.clear();
        for (var ring : USED_RINGS) {
            ring.rotate();
        }
        USED_RINGS.clear();
        for (var resource : CLOSE_AT_FRAME_END) {
            try {
                resource.close();
            } catch (Exception ignored) {
            }
        }
        CLOSE_AT_FRAME_END.clear();
        for (var collector : TRACKED_COLLECTORS) {
            collector.photonFXTasks().clear();
            release(collector.photonFXBaked());
        }
        TRACKED_COLLECTORS.clear();
        PhotonBloom.endFrame();
        PhotonFXLayer.endFrame();
        // a frame that ended without reaching AfterLevel (screenshot, crash mid-frame) must not carry
        // its layer into the next one, where it would be composited a second time
        PhotonDeferredLayer.discardPending();
        PhotonDrawTarget.endFrame();
        PhotonMaskTarget.endFrame();
    }

    private static void release(List<DrawJob> jobs) {
        for (var drawJob : jobs) {
            if (drawJob instanceof Job job) {
                job.mesh().close();
                job.buffer().close();
            }
        }
        jobs.clear();
    }
}
