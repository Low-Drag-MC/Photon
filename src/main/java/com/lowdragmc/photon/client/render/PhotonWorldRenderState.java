package com.lowdragmc.photon.client.render;

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
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
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
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.Set;

/**
 * Frame-scoped draw jobs for Photon's own draw slot (the 1.21 semantics: after vanilla translucent
 * particles). Translucent/HDR batches are baked straight into {@link MeshData} at extraction and
 * drained here:
 * <ul>
 *   <li>world: {@code RenderLevelStageEvent.AfterTranslucentParticles} — inside the main frame
 *       pass, output targets already routed;</li>
 *   <li>editor: {@code PhotonParticleManager.afterRender()} — inside the scene FBO scope, after
 *       the scene's translucent particles.</li>
 * </ul>
 * Jobs sort by {@code orderInLayer}, then far-to-near — the 1.21 cross-batch ordering.
 * <p>
 * Draw paths: render types with {@link PhotonRenderTypes.PhotonDrawInfo} use the vanilla-particle
 * ring-buffer pattern — the frame's vertices go once into a triple-buffered mapped GPU buffer
 * ({@link PhotonBufferCache}), and adjacent same-RenderType jobs merge into a single
 * {@code drawIndexed} (cross-emitter batching). Foreign types (KilaGraph graph materials) fall back
 * to {@code RenderType.draw}, keeping their own draw-time hooks intact.
 * <p>
 * Bloom: after the main draws, every fast-path run is drawn a second time into the
 * {@link PhotonBloom} source (encoded {@code ColorModulator = 1/HDR_SCALE}, depth-write-off
 * pipeline, tested against the same depth), then the mip chain composites back — the 1.21
 * "everything participates by brightness" semantics. Only this slot participates in bloom;
 * opaque fx render in the vanilla solid phase and stay LDR. Render-thread only.
 */
public final class PhotonWorldRenderState {

    /** One sortable draw in Photon's slot: a baked-mesh {@link Job} or an {@link InstancedJob}. */
    public sealed interface DrawJob permits Job, InstancedJob {
        int orderInLayer();

        float distanceSq();
    }

    public record Job(RenderType renderType, MeshData mesh, ByteBufferBuilder buffer,
                      int orderInLayer, float distanceSq) implements DrawJob {
    }

    /** An instanced draw: base geometry (sequential-quad indexed) + per-instance texel-buffer data
     *  (whole-buffer binds) + optional per-point pulling buffer. Textures/material values are
     *  carried directly — no RenderType involved. */
    public record InstancedJob(RenderPipeline pipeline, RenderPipeline bloomPipeline,
                               Map<String, Identifier> textures,
                               GpuBufferSlice materialSlice,
                               GpuBuffer vertices, int indexCount,
                               @Nullable GpuBuffer indices,
                               GpuBuffer instances, int instanceCount,
                               @Nullable GpuBuffer points,
                               @Nullable GpuBuffer data, @Nullable GpuBuffer customData,
                               Vector3f positionOffset, int blendEquation,
                               java.util.List<String> sceneSamplers,
                               @Nullable GpuBufferSlice customSlice,
                               PhotonInstancedDrawState.Layout instanceLayout,
                               @Nullable PhotonRenderTypes.GraphSource graph,
                               int orderInLayer, float distanceSq) implements DrawJob {
    }

    /** A merged draw over the frame's ring buffer (adjacent same-RenderType jobs). */
    private record Run(RenderType renderType, PhotonRenderTypes.PhotonDrawInfo info,
                       VertexFormat.Mode mode,
                       int baseVertex, int indexCount) {
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
        org.lwjgl.opengl.GL14.glBlendEquation(equation);
        try {
            draw.run();
        } finally {
            org.lwjgl.opengl.GL14.glBlendEquation(org.lwjgl.opengl.GL14.GL_FUNC_ADD);
        }
    }

    private static final List<DrawJob> WORLD_JOBS = new ArrayList<>();
    private static final List<DrawJob> EDITOR_JOBS = new ArrayList<>();

    private static final Comparator<DrawJob> DRAW_ORDER = Comparator.comparingInt(DrawJob::orderInLayer)
            .thenComparing(Comparator.comparingDouble(DrawJob::distanceSq).reversed());

    /** Instance rings written this frame — rotated once at the frame boundary (after the last
     *  drain that may read them, main + bloom draws included). */
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

    public static void add(boolean editorScene, DrawJob job) {
        (editorScene ? EDITOR_JOBS : WORLD_JOBS).add(job);
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

    public static void drainWorld() {
        drain(WORLD_JOBS, com.lowdragmc.photon.PhotonConfig.INSTANCE.enableBloom.get());
    }

    public static void drainEditor() {
        drain(EDITOR_JOBS, com.lowdragmc.photon.PhotonConfig.INSTANCE.enableBloom.get()
                && PhotonEditorRenderState.bloomEnabled);
    }

    private static void drain(List<DrawJob> jobs, boolean bloomEnabled) {
        if (jobs.isEmpty()) {
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

        var mainTarget = Minecraft.getInstance().getMainRenderTarget();
        var colorTexture = RenderSystem.outputColorTextureOverride != null
                ? RenderSystem.outputColorTextureOverride : mainTarget.getColorTextureView();
        var depthTexture = RenderSystem.outputDepthTextureOverride != null
                ? RenderSystem.outputDepthTextureOverride : mainTarget.getDepthTextureView();

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
                wf = PhotonPipelines.isWireframe(ij.pipeline());
                scene = ij.sceneSamplers();
            } else {
                var info = PhotonRenderTypes.drawInfo(((Job) drawJob).renderType());
                if (info == null) continue;
                wf = PhotonPipelines.isWireframe(info.pipeline());
                scene = info.sceneSamplers();
            }
            needColor |= wf;
            for (var name : scene) {
                if (name.contains("Depth")) needDepth = true;
                else needColor = true;
            }
        }
        if (needColor) sceneColor = PhotonSceneCapture.captureColor(colorTexture);
        if (needDepth) sceneDepth = PhotonSceneCapture.captureDepth(depthTexture);

        // main draws in sorted order; adjacent fast jobs with the same RenderType merge into one
        // draw; every fast/instanced draw is recorded for the bloom pass
        var runs = new ArrayList<Run>();
        var instancedJobs = new ArrayList<InstancedJob>();
        var baseVertex = 0;
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i) instanceof InstancedJob instanced) {
                instancedJobs.add(instanced);
                drawInstancedJob(instanced, instanced.pipeline(), colorTexture, depthTexture, 1f, sceneColor, sceneDepth);
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
                        || PhotonRenderTypes.drawInfo(next.renderType()) == null) {
                    break;
                }
                i++;
                vertexCount += next.mesh().drawState().vertexCount();
                indexCount += next.mesh().drawState().indexCount();
                closeFastJob(next);
            }
            var run = new Run(job.renderType(), info, mode, baseVertex, indexCount);
            runs.add(run);
            drawRun(cache, run, run.info().pipeline(), colorTexture, depthTexture, 1f, sceneColor, sceneDepth);
            baseVertex += vertexCount;
            closeFastJob(job);
        }
        jobs.clear();

        // bloom pass: draw every fast/instanced run again into the encoded source, run the mip chain
        if (bloomEnabled && (!runs.isEmpty() && cache != null || !instancedJobs.isEmpty())) {
            var bloom = PhotonBloom.acquire(colorTexture.getWidth(0), colorTexture.getHeight(0));
            bloom.clearSource();
            for (var run : runs) {
                drawRun(cache, run, run.info().bloomPipeline(), bloom.sourceView(), depthTexture,
                        1f / PhotonBloom.HDR_SCALE, sceneColor, sceneDepth);
            }
            for (var instanced : instancedJobs) {
                drawInstancedJob(instanced, instanced.bloomPipeline(), bloom.sourceView(), depthTexture,
                        1f / PhotonBloom.HDR_SCALE, sceneColor, sceneDepth);
            }
            bloom.run(colorTexture);
        }
    }

    /** The instanced flavor of {@link #drawRun}: base quad + texel-buffer instance data,
     *  {@code drawIndexed(instanceCount)}. Same binding sequence and pre-pass texture resolution. */
    private static void drawInstancedJob(InstancedJob job, RenderPipeline pipeline,
                                         GpuTextureView colorTexture, GpuTextureView depthTexture,
                                         float colorModulator,
                                @Nullable GpuTextureView sceneColor, @Nullable GpuTextureView sceneDepth) {
        // ModelOffset carries the facing-eye → render-origin delta: trail/beam facing math runs on
        // EYE-relative data (the editor SceneCamera's position() is ZERO, its true eye is sceneEye),
        // the shader adds the offset back into render-origin space. Zero in-world.
        var dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrix(),
                new Vector4f(colorModulator, colorModulator, colorModulator, 1),
                job.positionOffset(), new Matrix4f());
        if (job.graph() != null) {
            job.graph().material().prepareUniforms();
        }
        // the base mesh's own index buffer when it has one (the ara tube ring shares vertices between
        // adjacent section edges, which the shared quad pattern can't express), else the shared quads
        var autoIndices = job.indices() == null
                ? RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS) : null;
        final GpuBuffer indices = autoIndices == null
                ? job.indices() : autoIndices.getBuffer(job.indexCount());
        final VertexFormat.IndexType indexType = autoIndices == null
                ? VertexFormat.IndexType.INT : autoIndices.type();

        // resolve textures BEFORE opening the pass (first use triggers a GPU upload)
        var textureManager = Minecraft.getInstance().getTextureManager();
        var boundTextures = new ArrayList<BoundTexture>(job.textures().size());
        for (var entry : job.textures().entrySet()) {
            var texture = textureManager.getTexture(entry.getValue());
            boundTextures.add(new BoundTexture(entry.getKey(), texture.getTextureView(), texture.getSampler()));
        }
        withBlendEquation(job.blendEquation(), () -> {
        try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Photon fx instanced", colorTexture, OptionalInt.empty(), depthTexture, OptionalDouble.empty())) {
            renderPass.setPipeline(pipeline);
            var scissor = RenderSystem.getScissorStateForRenderTypeDraws();
            if (scissor.enabled()) {
                renderPass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
            }
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setUniform("PhotonMaterial", job.materialSlice());
            var engineSlice = PhotonEngineUniforms.currentSlice();
            if (engineSlice != null) {
                renderPass.setUniform("PhotonEngine", engineSlice); // custom fsh U_* block
            }
            if (job.customSlice() != null) {
                renderPass.setUniform("PhotonCustomMaterial", job.customSlice());
            }
            if (job.points() != null) {
                renderPass.setUniform("PhotonPoints", job.points());
            }
            // additional GPU data (the shadergraph photon_data_*() / photon_custom_data() accessors);
            // present only when a material on the pass reads it, and then every variant's pipeline that
            // declares it is drawn from this same job
            if (job.data() != null) {
                renderPass.setUniform("PhotonData", job.data());
            }
            if (job.customData() != null) {
                renderPass.setUniform("PhotonCustomData", job.customData());
            }
            for (var bound : boundTextures) {
                renderPass.bindTexture(bound.sampler(), bound.view(), bound.samplerState());
            }
            // context-sensitive: level lightmap during world rendering, the 1x1 white UI lightmap
            // during GUI (editor PIP) — the 26.1 engine's own convention, and exactly the 1.21
            // editor full-bright semantics (particle.vsh samples via sample_lightmap, clamped)
            renderPass.bindTexture("Sampler2", Minecraft.getInstance().gameRenderer.lightmap(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            if (sceneColor != null && PhotonPipelines.isWireframe(pipeline)) {
                renderPass.bindTexture("SamplerSceneColor", sceneColor, PhotonSceneCapture.sampler());
            }
            for (var name : job.sceneSamplers()) {
                var view = name.contains("Depth") ? sceneDepth : sceneColor;
                if (view != null) {
                    renderPass.bindTexture(name, view, PhotonSceneCapture.sampler());
                }
            }
            if (job.graph() != null) {
                job.graph().material().bindCustomUniforms(renderPass);
            }
            renderPass.setVertexBuffer(0, job.vertices());
            renderPass.setIndexBuffer(indices, indexType);
            // C1/C2: the 1.21 divisor attributes + RGBA32F texel respec apply inside this draw
            PhotonInstancedDrawState.begin(job.instanceLayout(), job.instances(), job.vertices());
            try {
                renderPass.drawIndexed(0, 0, job.indexCount(), job.instanceCount());
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
                                float colorModulator,
                                @Nullable GpuTextureView sceneColor, @Nullable GpuTextureView sceneDepth) {
        var dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrix(),
                new Vector4f(colorModulator, colorModulator, colorModulator, 1),
                new Vector3f(), new Matrix4f());
        var autoIndices = RenderSystem.getSequentialBuffer(run.mode());
        GpuBuffer indices = autoIndices.getBuffer(run.indexCount());

        // resolve textures BEFORE opening the pass: first use of a texture triggers registerAndLoad
        // (a GPU upload), which is illegal while a render pass is open
        var textureManager = Minecraft.getInstance().getTextureManager();
        var boundTextures = new ArrayList<BoundTexture>(run.info().textures().size());
        for (var entry : run.info().textures().entrySet()) {
            var texture = textureManager.getTexture(entry.getValue());
            boundTextures.add(new BoundTexture(entry.getKey(), texture.getTextureView(), texture.getSampler()));
        }
        // KilaGraph graph materials: upload their UBOs + resolve their textures BEFORE the pass (both are
        // illegal inside one), then bind them in it — KG's own draw hook never fires for a pass we opened.
        var graph = run.info().graph();
        if (graph != null) {
            graph.material().prepareUniforms();
        }
        var key = run.info().hdrPipelineKey();
        withBlendEquation(key != null ? key.blendEquation() : PhotonPipelines.BLEND_EQUATION_ADD, () -> {
        try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Photon fx slot", colorTexture, OptionalInt.empty(), depthTexture, OptionalDouble.empty())) {
            renderPass.setPipeline(pipeline);
            var scissor = RenderSystem.getScissorStateForRenderTypeDraws();
            if (scissor.enabled()) {
                renderPass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
            }
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
            var custom = run.info().customUniforms();
            if (custom != null && custom.slice() != null) {
                renderPass.setUniform("PhotonCustomMaterial", custom.slice());
            }
            for (var bound : boundTextures) {
                renderPass.bindTexture(bound.sampler(), bound.view(), bound.samplerState());
            }
            renderPass.bindTexture("Sampler2", Minecraft.getInstance().gameRenderer.lightmap(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            if (sceneColor != null && PhotonPipelines.isWireframe(pipeline)) {
                renderPass.bindTexture("SamplerSceneColor", sceneColor, PhotonSceneCapture.sampler());
            }
            for (var name : run.info().sceneSamplers()) {
                var view = name.contains("Depth") ? sceneDepth : sceneColor;
                if (view != null) {
                    renderPass.bindTexture(name, view, PhotonSceneCapture.sampler());
                }
            }
            if (graph != null) {
                graph.material().bindCustomUniforms(renderPass);
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
        release(WORLD_JOBS);
        release(EDITOR_JOBS);
        PhotonBloom.endFrame();
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
