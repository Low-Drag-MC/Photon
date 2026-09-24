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
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;

/**
 * The draw engine behind {@link PhotonFeatureRenderer}. {@link Frame#prepare()} bakes, sorts, uploads and resolves
 * everything before any pass opens; {@link Frame#execute(PhotonStage)} only records draws.
 * <p>
 * Draws land in Photon's HDR target: {@link PhotonDrawTarget} (seeded from the output and composited back) or
 * {@link PhotonFXLayer} (starts empty, merged later by {@link FXCompositeMode#LATE} or a shader pack).
 */
public final class PhotonWorldRenderState {

    // ---- the job model the emitters bake into ----------------------------------------------------

    /** One sortable draw in Photon's slot: a baked-mesh {@link Job} or an {@link InstancedJob}. */
    public sealed interface DrawJob permits Job, InstancedJob {
        PhotonStage stage();

        int orderInLayer();

        float distanceSq();
    }

    /**
     * What a job writes into the CustomMask sub-pass. Part of the run-merge test, so different groups never merge.
     *
     * @param value       the group id in 0..1
     * @param alphaCutoff 0 = whole geometry; above 0 alpha-clips against {@code clipTexture}
     */
    public record MaskWrite(float value, float alphaCutoff, @Nullable Identifier clipTexture) {

        /** The mask a flagged emitter writes; null when it isn't flagged. */
        @Nullable
        public static MaskWrite of(RendererSetting.Runtime renderer, @Nullable Identifier clipTexture) {
            if (!renderer.isWriteCustomMask()) {
                return null;
            }
            var cutoff = clipTexture == null ? 0f : renderer.getMaskAlphaCutoff();
            return new MaskWrite(MaskGroups.idOf(renderer.getMaskGroup()) / 255f, cutoff, clipTexture);
        }
    }

    /** A CPU-baked mesh, consumed by {@link Frame#prepare()}. */
    public record Job(RenderType renderType, MeshData mesh, ByteBufferBuilder buffer,
                      PhotonStage stage, int orderInLayer, float distanceSq,
                      @Nullable MaskWrite mask) implements DrawJob {
    }

    public record InstancedJob(PhotonRenderTypes.PhotonDrawInfo.Programs programs,
                               InstancedGeometry geometry,
                               DrawBindings bindings,
                               Vector3f positionOffset,
                               PhotonStage stage, int orderInLayer, float distanceSq,
                               PhotonPipelines.InstancedGeometryKey geometryKey,
                               @Nullable MaskWrite mask) implements DrawJob {
    }

    /** {@code indices} null = the shared sequential-quad indices. */
    public record InstancedGeometry(GpuBuffer vertices, int indexCount, @Nullable GpuBuffer indices,
                                    GpuBufferSlice instances, int instanceCount,
                                    @Nullable GpuBuffer points,
                                    @Nullable GpuBuffer data, @Nullable GpuBuffer customData,
                                    @Nullable GpuBuffer vat, @Nullable GpuBufferSlice vatInfo) {

        public InstancedGeometry(GpuBuffer vertices, int indexCount, @Nullable GpuBuffer indices,
                                 GpuBufferSlice instances, int instanceCount,
                                 @Nullable GpuBuffer points,
                                 @Nullable GpuBuffer data, @Nullable GpuBuffer customData) {
            this(vertices, indexCount, indices, instances, instanceCount, points, data, customData, null, null);
        }
    }

    /** {@link PhotonRenderTypes.PhotonDrawInfo.Bindings} with the uniform blocks resolved to slices. */
    public record DrawBindings(Map<String, Identifier> textures,
                               GpuBufferSlice materialSlice,
                               @Nullable GpuBufferSlice customSlice,
                               List<String> sceneSamplers,
                               @Nullable PhotonRenderTypes.GraphSource graph,
                               boolean legacyDepth) {
    }

    // ---- shared instanced base meshes -------------------------------------------------------------

    @Nullable
    private static GpuBuffer tileQuad;
    @Nullable
    private static GpuBuffer beamQuad;
    @Nullable
    private static GpuBuffer segmentQuad;
    @Nullable
    private static GpuBuffer araQuad;

    private static GpuBuffer corners(String label, int components, float... values) {
        var bytes = MemoryUtil.memAlloc(values.length * Float.BYTES);
        try {
            for (var v : values) {
                bytes.putFloat(v);
            }
            bytes.flip();
            return RenderSystem.getDevice().createBuffer(() -> label, GpuBuffer.USAGE_VERTEX, bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    /** Billboard corners ±1. */
    public static GpuBuffer tileQuad() {
        if (tileQuad == null) {
            tileQuad = corners("Photon tile quad", 3, 1, -1, 0, 1, 1, 0, -1, 1, 0, -1, -1, 0);
        }
        return tileQuad;
    }

    /** Beam corners: x = start/end, y = side, in the CPU emission order. */
    public static GpuBuffer beamQuad() {
        if (beamQuad == null) {
            beamQuad = corners("Photon beam quad", 2, 0, -1, 0, 1, 1, 1, 1, -1);
        }
        return beamQuad;
    }

    /** Trail segment corners: x = curr/next, y = up/down, wound like 1.21. */
    public static GpuBuffer segmentQuad() {
        if (segmentQuad == null) {
            segmentQuad = corners("Photon segment quad", 2, 0, 1, 0, -1, 1, -1, 1, 1);
        }
        return segmentQuad;
    }

    /** Ara ribbon corners; 1.21 winds these opposite to the trail quad. */
    public static GpuBuffer araQuad() {
        if (araQuad == null) {
            araQuad = corners("Photon ara quad", 2, 1, -1, 0, -1, 0, 1, 1, 1);
        }
        return araQuad;
    }

    // ---- frame bookkeeping ------------------------------------------------------------------------

    private static final Comparator<DrawJob> DRAW_ORDER = Comparator.comparingInt(DrawJob::orderInLayer)
            .thenComparing(Comparator.comparingDouble(DrawJob::distanceSq).reversed());

    private static final Vector4f NO_MODULATION = new Vector4f(1, 1, 1, 1);

    /** Resources queued draws may still reference. */
    private static final List<AutoCloseable> CLOSE_AT_FRAME_END = new ArrayList<>();

    public static void closeAtFrameEnd(AutoCloseable resource) {
        CLOSE_AT_FRAME_END.add(resource);
    }

    private PhotonWorldRenderState() {
    }

    public static void endFrame() {
        for (var resource : CLOSE_AT_FRAME_END) {
            try {
                resource.close();
            } catch (Exception ignored) {
            }
        }
        CLOSE_AT_FRAME_END.clear();
        PhotonTexelPool.endFrame();
        PhotonBloom.endFrame();
        PhotonFXLayer.endFrame();
        // a frame that never reached AfterLevel must not composite its layer twice
        PhotonDeferredLayer.discardPending();
        PhotonDrawTarget.endFrame();
        PhotonMaskTarget.endFrame();
    }

    // ---- resolved draws ---------------------------------------------------------------------------

    private record BoundTexture(String sampler, GpuTextureView view, GpuSampler samplerState) {
    }

    /** A fully resolved draw call. {@code instances} null = CPU geometry on binding 0 alone. */
    private record Draw(RenderPipeline pipeline, GpuBufferSlice transforms,
                        @Nullable GpuBufferSlice material, @Nullable GpuBufferSlice custom,
                        List<BoundTexture> textures, @Nullable PhotonRenderTypes.GraphSource graph,
                        List<String> sceneSamplers, boolean legacyDepth,
                        GpuBufferSlice vertices, @Nullable GpuBufferSlice instances,
                        @Nullable GpuBuffer points, @Nullable GpuBuffer data, @Nullable GpuBuffer customData,
                        @Nullable GpuBuffer vat, @Nullable GpuBufferSlice vatInfo,
                        @Nullable GpuBuffer indices, IndexType indexType, PrimitiveTopology sequentialTopology,
                        int indexCount, int instanceCount, int baseVertex) {

        int vertexBindings() {
            return instances == null ? 1 : 2;
        }

        boolean wireframe() {
            return PhotonPipelines.isWireframe(pipeline);
        }
    }

    private record MaskDraw(RenderPipeline pipeline, GpuBufferSlice transforms, GpuBufferSlice block,
                            BoundTexture clip, Draw geometry) {
    }

    private static final class Stage {
        final List<Draw> draws = new ArrayList<>();
        final List<MaskDraw> maskDraws = new ArrayList<>();
        final EnumMap<PrimitiveTopology, Integer> sequentialIndexCounts = new EnumMap<>(PrimitiveTopology.class);
        boolean needsSceneColor;
        boolean needsSceneDepth;
        boolean needsLegacyDepth;

        boolean isEmpty() {
            return draws.isEmpty();
        }
    }

    /** One view's Photon work for one frame, created by {@link PhotonFXRenderState#submit}. */
    public static final class Frame {
        private final List<PhotonBakeTask> tasks;
        private final PhotonViewSettings settings;
        private boolean prepared;
        private final EnumMap<PhotonStage, Stage> inline = new EnumMap<>(PhotonStage.class);
        private final Stage layer = new Stage();
        /** Under a shader pack every translucent draw goes into the layer. */
        private boolean packLayer;

        Frame(List<PhotonBakeTask> tasks, PhotonViewSettings settings) {
            this.tasks = tasks;
            this.settings = settings;
        }

        public PhotonViewSettings settings() {
            return settings;
        }

        /** Idempotent: both phases of a view prepare, only the first does the work. No pass may be open. */
        public void prepare() {
            if (prepared) {
                return;
            }
            prepared = true;
            var output = PhotonRenderOutput.color();
            PhotonEngineUniforms.ensure(output == null ? 1 : output.getWidth(0),
                    output == null ? 1 : output.getHeight(0));
            var baked = new ArrayList<DrawJob>();
            try {
                for (var task : tasks) {
                    task.bake(settings, baked);
                }
            } finally {
                // a bake that threw must not leave every later pipeline premultiplied
                PremultipliedBlendPlan.setAccumulating(false);
            }

            var opaque = new ArrayList<DrawJob>();
            var translucent = new ArrayList<DrawJob>();
            var deferred = new ArrayList<DrawJob>();
            for (var job : baked) {
                switch (job.stage()) {
                    case AFTER_OPAQUE_FEATURES -> opaque.add(job);
                    case AFTER_TRANSLUCENT_PARTICLES -> translucent.add(job);
                    case DEFERRED -> deferred.add(job);
                }
            }
            packLayer = PremultipliedBlendPlan.isLayerStage(PhotonStage.LAST);
            if (packLayer) {
                deferred.addAll(translucent);
                translucent.clear();
            }
            // LATE tests against the opaque depth snapshot, taken later this frame; a pack uses the live depth
            if (!deferred.isEmpty() && !packLayer && !IrisCompat.isUsingShaderPack()) {
                OpaqueDepthCapture.demand();
            }
            inline.put(PhotonStage.AFTER_OPAQUE_FEATURES, resolve(opaque));
            inline.put(PhotonStage.AFTER_TRANSLUCENT_PARTICLES, resolve(translucent));
            var resolvedLayer = resolve(deferred);
            layer.draws.addAll(resolvedLayer.draws);
            layer.maskDraws.addAll(resolvedLayer.maskDraws);
            layer.sequentialIndexCounts.putAll(resolvedLayer.sequentialIndexCounts);
            layer.needsSceneColor = resolvedLayer.needsSceneColor;
            layer.needsSceneDepth = resolvedLayer.needsSceneDepth;
            layer.needsLegacyDepth = resolvedLayer.needsLegacyDepth;
        }

        /** The last stage also draws and merges the layer and runs the post-effect chain, even with no jobs. */
        public void execute(PhotonStage stage) {
            if (!prepared) {
                prepare();
            }
            var last = stage == PhotonStage.LAST;
            // views on their own clock (the editor timeline) get their own Globals
            var substituted = PhotonGlobals.substitute();
            try {
                var bloom = PhotonConfig.INSTANCE.enableBloom.get() && settings.bloom();
                var chainStack = settings.effects() ? settings.postEffects() : null;
                var inlineStage = inline.get(stage);
                // with a layer, the chain runs after the layer composite instead (PhotonPostFX)
                var runChainHere = last && layer.isEmpty();
                if (inlineStage != null) {
                    drain(inlineStage, bloom, chainStack, runChainHere, false, false);
                }
                if (last) {
                    drain(layer, bloom, chainStack, false, true, !packLayer);
                    // inside the level render: Iris consumes its colortex at the end of renderLevel
                    finishLayer(outputSize());
                }
            } finally {
                if (substituted) {
                    PhotonGlobals.restore();
                }
            }
        }
    }

    // ---- prepare: resolve jobs into draws -------------------------------------------------------

    /** Sorts the jobs, merges adjacent CPU jobs into runs and uploads their vertices in one transient block. */
    private static Stage resolve(List<DrawJob> jobs) {
        var stage = new Stage();
        if (jobs.isEmpty()) {
            return stage;
        }
        jobs.sort(DRAW_ORDER);

        var blocks = new ArrayList<ByteBuffer>();
        for (var drawJob : jobs) {
            if (drawJob instanceof Job job) {
                blocks.add(job.mesh().vertexBuffer());
            }
        }
        var cpuVertices = blocks.isEmpty() ? null : PhotonUploads.vertices(blocks);

        var textureManager = Minecraft.getInstance().getTextureManager();
        var baseVertex = 0;
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i) instanceof InstancedJob instanced) {
                resolveInstanced(stage, instanced, textureManager);
                continue;
            }
            var job = (Job) jobs.get(i);
            var info = PhotonRenderTypes.drawInfo(job.renderType());
            var mode = job.mesh().drawState().primitiveTopology();
            var vertexCount = job.mesh().drawState().vertexCount();
            var indexCount = job.mesh().drawState().indexCount();
            // cross-emitter batching; strips and fans cannot be joined
            while (i + 1 < jobs.size()
                    && jobs.get(i + 1) instanceof Job next
                    && next.renderType() == job.renderType()
                    && Objects.equals(next.mask(), job.mask())
                    && !mode.connectedPrimitives) {
                i++;
                vertexCount += next.mesh().drawState().vertexCount();
                indexCount += next.mesh().drawState().indexCount();
                closeMesh(next);
            }
            if (info != null && cpuVertices != null) {
                var draw = resolveRun(stage, job, info, cpuVertices, baseVertex, indexCount, mode, textureManager);
                if (job.mask() != null) {
                    stage.maskDraws.add(resolveMask(job.mask(), PhotonPipelines.mask(null, mode), draw,
                            new Vector3f()));
                }
            }
            baseVertex += vertexCount;
            closeMesh(job);
        }
        return stage;
    }

    private static void closeMesh(Job job) {
        job.mesh().close();
        job.buffer().close();
    }

    private static void needSequential(Stage stage, PrimitiveTopology topology, int indexCount) {
        stage.sequentialIndexCounts.merge(topology, indexCount, Math::max);
    }

    private static List<BoundTexture> resolveTextures(Map<String, Identifier> textures,
                                                      TextureManager manager) {
        var bound = new ArrayList<BoundTexture>(textures.size());
        for (var entry : textures.entrySet()) {
            var texture = manager.getTexture(entry.getValue());
            bound.add(new BoundTexture(entry.getKey(), texture.getTextureView(), texture.getSampler()));
        }
        return bound;
    }

    private static void noteSceneSamplers(Stage stage, RenderPipeline pipeline, List<String> sceneSamplers,
                                          boolean legacyDepth) {
        stage.needsSceneColor |= PhotonPipelines.isWireframe(pipeline);
        for (var name : sceneSamplers) {
            if (name.contains("Depth")) {
                stage.needsSceneDepth = true;
                stage.needsLegacyDepth |= legacyDepth;
            } else {
                stage.needsSceneColor = true;
            }
        }
    }

    /** Custom-shader materials own no block but still declare it, so they bind the defaults. */
    static GpuBufferSlice materialSlice(RenderType renderType) {
        var slice = PhotonMaterialUniforms.sliceFor(renderType);
        return slice != null ? slice : PhotonMaterialUniforms.sliceFor(PhotonMaterialUniforms.Values.DEFAULT);
    }

    private static Draw resolveRun(Stage stage, Job job, PhotonRenderTypes.PhotonDrawInfo info,
                                   GpuBufferSlice vertices, int baseVertex, int indexCount, PrimitiveTopology mode,
                                   TextureManager manager) {
        var pipeline = info.programs().main();
        var graph = info.bindings().graph();
        if (graph != null) {
            graph.material().prepareUniforms();
        }
        var custom = info.bindings().customUniforms();
        var draw = new Draw(pipeline,
                RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy(),
                        new Vector4f(NO_MODULATION), new Vector3f(), new Matrix4f()),
                materialSlice(job.renderType()),
                custom == null ? null : custom.slice(),
                resolveTextures(info.bindings().textures(), manager), graph, info.bindings().sceneSamplers(),
                info.bindings().legacyDepth(),
                vertices, null, null, null, null, null, null,
                null, IndexType.INT, mode, indexCount, 1, baseVertex);
        needSequential(stage, mode, indexCount);
        noteSceneSamplers(stage, pipeline, info.bindings().sceneSamplers(), info.bindings().legacyDepth());
        stage.draws.add(draw);
        return draw;
    }

    private static void resolveInstanced(Stage stage, InstancedJob job,
                                         TextureManager manager) {
        var pipeline = job.programs().main();
        var geometry = job.geometry();
        var graph = job.bindings().graph();
        if (graph != null) {
            graph.material().prepareUniforms();
        }
        // ModelOffset: eye → render-origin delta for eye-relative trail/beam data (zero in-world)
        var transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy(),
                new Vector4f(NO_MODULATION), new Vector3f(job.positionOffset()), new Matrix4f());
        var ownIndices = geometry.indices();
        var draw = new Draw(pipeline, transforms, job.bindings().materialSlice(), job.bindings().customSlice(),
                resolveTextures(job.bindings().textures(), manager), graph, job.bindings().sceneSamplers(),
                job.bindings().legacyDepth(),
                geometry.vertices().slice(), geometry.instances(),
                geometry.points(), geometry.data(), geometry.customData(), geometry.vat(), geometry.vatInfo(),
                ownIndices, IndexType.INT, PrimitiveTopology.QUADS,
                geometry.indexCount(), geometry.instanceCount(), 0);
        if (ownIndices == null) {
            needSequential(stage, PrimitiveTopology.QUADS, geometry.indexCount());
        }
        noteSceneSamplers(stage, pipeline, job.bindings().sceneSamplers(), job.bindings().legacyDepth());
        stage.draws.add(draw);
        if (job.mask() != null) {
            stage.maskDraws.add(resolveMask(job.mask(),
                    PhotonPipelines.mask(job.geometryKey(), PrimitiveTopology.QUADS), draw, job.positionOffset()));
        }
    }

    private static MaskDraw resolveMask(MaskWrite mask, RenderPipeline pipeline, Draw geometry,
                                        Vector3f positionOffset) {
        var id = mask.clipTexture() != null ? mask.clipTexture() : MissingTextureAtlasSprite.getLocation();
        var texture = Minecraft.getInstance().getTextureManager().getTexture(id);
        return new MaskDraw(pipeline,
                RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy(),
                        new Vector4f(NO_MODULATION), new Vector3f(positionOffset), new Matrix4f()),
                PhotonMaskUniforms.sliceFor(mask),
                new BoundTexture("Sampler0", texture.getTextureView(), texture.getSampler()),
                geometry);
    }

    // ---- execute: draw one stage ------------------------------------------------------------------

    private static void drain(Stage stage, boolean bloomEnabled, @Nullable PostEffectStack stack,
                              boolean lastStage, boolean layerMode, boolean opaqueDepth) {
        var runChain = lastStage && stack != null && stack.wantsExecution();
        if (stage.isEmpty() && !runChain) {
            return;
        }
        var outputColor = PhotonRenderOutput.color();
        var depthTexture = PhotonRenderOutput.depth();
        if (outputColor == null || depthTexture == null) {
            return;
        }
        var scissor = PhotonRenderOutput.scissor(outputColor.getWidth(0), outputColor.getHeight(0));
        if (PhotonRenderOutput.isEmpty(scissor)) {
            return;
        }

        PhotonDrawTarget drawTarget = null;
        GpuTextureView colorTexture;
        if (layerMode) {
            var layer = PhotonFXLayer.acquire(outputColor.getWidth(0), outputColor.getHeight(0));
            layer.beginFrame();
            colorTexture = layer.view();
            if (opaqueDepth) {
                // LATE only: keeps water from slicing effects
                var snapshot = OpaqueDepthCapture.view(depthTexture);
                if (snapshot != null) {
                    depthTexture = snapshot;
                }
            }
        } else {
            drawTarget = PhotonDrawTarget.acquire(outputColor.getWidth(0), outputColor.getHeight(0));
            drawTarget.copyFrom(outputColor, scissor);
            colorTexture = drawTarget.view();
        }

        // pre-fx captures, from the output (in layer mode colorTexture is the empty layer)
        GpuTextureView sceneColor = stage.needsSceneColor ? PhotonSceneCapture.captureColor(outputColor) : null;
        GpuTextureView sceneDepth = stage.needsSceneDepth ? PhotonSceneCapture.captureDepth(depthTexture) : null;
        // a declared sampler must always be bound, so fall back to stand-ins
        if (stage.needsSceneColor && sceneColor == null) sceneColor = PhotonSceneCapture.standInColor();
        GpuTextureView legacyDepth = null;
        if (stage.needsLegacyDepth) {
            legacyDepth = sceneDepth != null ? PhotonSceneCapture.legacyDepth(sceneDepth)
                    : PhotonSceneCapture.legacyStandInDepth();
        }
        if (stage.needsSceneDepth && sceneDepth == null) sceneDepth = PhotonSceneCapture.standInDepth();

        if (!stage.isEmpty()) {
            recordDraws(stage, colorTexture, depthTexture, scissor, sceneColor, sceneDepth, legacyDepth);
        }

        Consumer<GpuTextureView> bloomStep = !bloomEnabled || stage.isEmpty() ? null : target -> {
            var bloom = PhotonBloom.acquire(target.getWidth(0), target.getHeight(0));
            bloom.run(target);
        };

        var mask = maskSubPass(stage, stack, colorTexture, depthTexture, scissor);

        if (runChain) {
            // bloom runs inside the chain at priority 0
            var inputs = RenderGraphExecutor.FrameInputs.of(colorTexture, depthTexture);
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

        if (drawTarget != null) {
            drawTarget.compositeTo(outputColor, scissor);
        }
    }

    /** One pass per run of draws with the same vertex-binding count; a pass cannot drop a binding. */
    private static void recordDraws(Stage stage, GpuTextureView colorTexture, GpuTextureView depthTexture,
                                    ScissorState scissor, @Nullable GpuTextureView sceneColor,
                                    @Nullable GpuTextureView sceneDepth, @Nullable GpuTextureView legacyDepth) {
        // grown before the pass opens
        var sequential = new EnumMap<PrimitiveTopology, GpuBuffer>(PrimitiveTopology.class);
        var sequentialTypes = new EnumMap<PrimitiveTopology, IndexType>(PrimitiveTopology.class);
        stage.sequentialIndexCounts.forEach((topology, count) -> {
            var autoIndices = RenderSystem.getSequentialBuffer(topology);
            sequential.put(topology, autoIndices.getBuffer(count));
            sequentialTypes.put(topology, autoIndices.type());
        });
        var lightmap = Minecraft.getInstance().gameRenderer.lightmap();
        var lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        var engine = PhotonEngineUniforms.currentSlice();
        var legacyEngine = PhotonEngineUniforms.legacySlice();
        var captureSampler = PhotonSceneCapture.sampler();

        var index = 0;
        while (index < stage.draws.size()) {
            var bindings = stage.draws.get(index).vertexBindings();
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Photon fx", colorTexture, Optional.empty(), depthTexture, OptionalDouble.empty())) {
                if (scissor.enabled()) {
                    pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
                }
                RenderSystem.bindDefaultUniforms(pass);
                while (index < stage.draws.size() && stage.draws.get(index).vertexBindings() == bindings) {
                    var draw = stage.draws.get(index++);
                    pass.setPipeline(draw.pipeline());
                    pass.setUniform("DynamicTransforms", draw.transforms());
                    if (draw.material() != null) {
                        pass.setUniform(PhotonMaterialUniforms.UBO_NAME, draw.material());
                    }
                    // legacy shaders get the inverse projection matching their legacy depth
                    var drawEngine = draw.legacyDepth() ? legacyEngine : engine;
                    if (drawEngine != null) {
                        pass.setUniform(PhotonEngineUniforms.UBO_NAME, drawEngine);
                    }
                    if (draw.custom() != null) {
                        pass.setUniform(PhotonCustomUniforms.UBO_NAME, draw.custom());
                    }
                    bindSideBuffers(pass, draw);
                    for (var bound : draw.textures()) {
                        pass.bindTexture(bound.sampler(), bound.view(), bound.samplerState());
                    }
                    pass.bindTexture("Sampler2", lightmap, lightmapSampler);
                    if (draw.graph() != null) {
                        draw.graph().material().bindCustomUniforms(pass);
                    }
                    // after bindCustomUniforms, which binds the renamed scene samplers to the missing texture
                    if (sceneColor != null && draw.wireframe()) {
                        pass.bindTexture("SamplerSceneColor", sceneColor, captureSampler);
                    }
                    for (var name : draw.sceneSamplers()) {
                        var view = name.contains("Depth")
                                ? (draw.legacyDepth() ? legacyDepth : sceneDepth)
                                : sceneColor;
                        if (view != null) {
                            pass.bindTexture(name, view, captureSampler);
                        }
                    }
                    drawGeometry(pass, draw, sequential, sequentialTypes);
                }
            }
        }
    }

    private static void bindSideBuffers(RenderPass pass, Draw draw) {
        if (draw.points() != null) {
            pass.setUniform("PhotonPoints", draw.points());
        }
        if (draw.data() != null) {
            pass.setUniform("PhotonData", draw.data());
        }
        if (draw.customData() != null) {
            pass.setUniform("PhotonCustomData", draw.customData());
        }
        if (draw.vat() != null && draw.vatInfo() != null) {
            pass.setUniform("PhotonVat", draw.vat());
            pass.setUniform(PhotonVatUniforms.UBO_NAME, draw.vatInfo());
        }
    }

    private static void drawGeometry(RenderPass pass, Draw draw, Map<PrimitiveTopology, GpuBuffer> sequential,
                                     Map<PrimitiveTopology, IndexType> sequentialTypes) {
        pass.setVertexBuffer(0, draw.vertices());
        if (draw.instances() != null) {
            pass.setVertexBuffer(1, draw.instances());
        }
        if (draw.indices() != null) {
            pass.setIndexBuffer(draw.indices(), draw.indexType());
        } else {
            pass.setIndexBuffer(sequential.get(draw.sequentialTopology()),
                    sequentialTypes.get(draw.sequentialTopology()));
        }
        // firstInstance stays 0: GL's gl_InstanceID ignores it, Vulkan's gl_InstanceIndex includes it
        pass.drawIndexed(draw.indexCount(), draw.instanceCount(), 0, draw.baseVertex(), 0);
    }

    /**
     * Redraws flagged draws as flat group ids into {@link PhotonMaskTarget}; skipped unless a pending effect
     * reads the mask. Returns the target when it holds this frame's mask.
     */
    @Nullable
    private static PhotonMaskTarget maskSubPass(Stage stage, @Nullable PostEffectStack maskStack,
                                                GpuTextureView colorTexture, GpuTextureView depthTexture,
                                                ScissorState scissor) {
        if (maskStack == null || !maskStack.hasPendingMaskConsumer() || stage.maskDraws.isEmpty()) {
            // an earlier stage may have written it
            return PhotonMaskTarget.writtenThisFrame(colorTexture.getWidth(0), colorTexture.getHeight(0));
        }
        var target = PhotonMaskTarget.acquire(colorTexture.getWidth(0), colorTexture.getHeight(0), depthTexture);
        var sequential = new EnumMap<PrimitiveTopology, GpuBuffer>(PrimitiveTopology.class);
        var sequentialTypes = new EnumMap<PrimitiveTopology, IndexType>(PrimitiveTopology.class);
        stage.sequentialIndexCounts.forEach((topology, count) -> {
            var autoIndices = RenderSystem.getSequentialBuffer(topology);
            sequential.put(topology, autoIndices.getBuffer(count));
            sequentialTypes.put(topology, autoIndices.type());
        });
        // cleared by the frame's first mask pass
        var clear = target.takeClear() ? Optional.<Vector4fc>of(new Vector4f(0, 0, 0, 0))
                : Optional.<Vector4fc>empty();
        var index = 0;
        while (index < stage.maskDraws.size()) {
            var bindings = stage.maskDraws.get(index).geometry().vertexBindings();
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Photon custom mask", target.colorView(), clear, target.depthView(),
                    OptionalDouble.empty())) {
                clear = Optional.empty();
                if (scissor.enabled()) {
                    pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
                }
                RenderSystem.bindDefaultUniforms(pass);
                while (index < stage.maskDraws.size()
                        && stage.maskDraws.get(index).geometry().vertexBindings() == bindings) {
                    var draw = stage.maskDraws.get(index++);
                    pass.setPipeline(draw.pipeline());
                    pass.setUniform("DynamicTransforms", draw.transforms());
                    pass.setUniform(PhotonMaskUniforms.UBO_NAME, draw.block());
                    pass.bindTexture(draw.clip().sampler(), draw.clip().view(), draw.clip().samplerState());
                    bindSideBuffers(pass, draw.geometry());
                    drawGeometry(pass, draw.geometry(), sequential, sequentialTypes);
                }
            }
        }
        target.markWritten();
        return target;
    }

    // ---- the layer merge --------------------------------------------------------------------------

    private static long outputSize() {
        var output = PhotonRenderOutput.color();
        return output == null ? 0 : ((long) output.getWidth(0) << 32) | (output.getHeight(0) & 0xFFFFFFFFL);
    }

    /**
     * Merges the frame's FX layer into the pack's particle colortex, or parks it for the post-level seam
     * ({@link FXCompositeMode#LATE}, {@code AFTER_PACK}).
     */
    private static void finishLayer(long size) {
        var layer = PhotonFXLayer.inUse((int) (size >>> 32), (int) size);
        if (layer == null) {
            return;
        }
        var frame = IrisCompat.isUsingShaderPack() ? IrisCompat.resolveFrameTarget(true) : null;
        if (frame != null && frame.compositeMode() == IrisCompositeMode.DISABLED) {
            return;
        }
        var packTarget = frame == null ? null : irisCompositeTarget(frame);
        if (packTarget != null) {
            layer.compositeTo(packTarget.view(), packTarget.writeAlpha());
        } else {
            PhotonDeferredLayer.park(layer);
        }
    }

    private record PackTarget(GpuTextureView view, boolean writeAlpha) {
    }

    /** Null holds the layer back for the post-level seam; only these two modes name a colour buffer. */
    @Nullable
    private static PackTarget irisCompositeTarget(IrisFrameTarget frame) {
        if (!frame.canComposite()) return null;
        var mode = frame.compositeMode();
        if (mode != IrisCompositeMode.PREMULTIPLIED_ACCUM && mode != IrisCompositeMode.SCENE_REPLACE) {
            return null;
        }
        var view = IrisCompat.compositeTarget(frame);
        return view == null ? null : new PackTarget(view, !frame.primaryIsSceneColor());
    }
}
