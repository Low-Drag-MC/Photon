package com.lowdragmc.photon.client.gameobject.emitter;

import com.lowdragmc.lowdraglib2.client.scene.SceneCamera;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.DummyWorld;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.AdditionalGPUDataSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.render.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialRenderTypes;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.ShaderGraphMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.UIResourceMaterial;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;
import org.joml.Vector4f;
import lombok.Getter;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@ParametersAreNonnullByDefault
public abstract class Emitter extends FXObject implements IParticleEmitter {
    // runtime
    @Nullable
    protected Vector3f previousPosition;
    protected Vector3f velocity = new Vector3f();
    @Getter
    protected float t;
    /** Fractional simulation age (ticks). Replaces the integer {@code age} so the speed track can advance
     *  it by a fractional {@code dt}; {@link #getAge()} exposes the rounded value for display/emission. */
    protected float ageF = 0;
    @Getter
    protected ConcurrentHashMap<Object, Float> memRandom = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BlockPos, Integer> lightCache = new ConcurrentHashMap<>();
    /** True while particles update on worker threads: {@link #getLightColor} must not touch the level. */
    private volatile boolean parallelLightPhase = false;
    /** Positions particles asked for during the parallel phase; refreshed on the game thread post-loop. */
    private final Set<BlockPos> lightQueryQueue = ConcurrentHashMap.newKeySet();

    protected Emitter() {
        this.friction = 1;
    }

    public RandomSource getRandomSource() {
        return random;
    }

    @Override
    protected void onTickBegin() {
        if (!isAlive()) {
            return;
        }
        if (previousPosition != null) {
            velocity = transform.position().sub(previousPosition, new Vector3f());
        }
        previousPosition = transform.position();

        if (clearsLightCacheOnTickBegin()) {
            lightCache.clear();
        }
        updateOrigin(); // snapshot render origin once per tick (see FXObject.tick)
    }

    @Override
    public final void updateTick(float dt) {
        super.updateTick(dt);
        if (!isAlive()) {
            return;
        }
        update(dt);
    }

    @Override
    public void setPos(double x, double y, double z) {
        //noinspection ConstantValue
        if (this.transform == null) return;
        transform.position(new Vector3f((float)x, (float)y, (float)z));
    }

    @Override
    public Vector3f getVelocity() {
        return new Vector3f(velocity);
    }

    protected void update(float dt) {
        this.ageF += dt;
        this.age = (int) this.ageF;
        if (this.ageF >= getLifetime() && !isLooping()) {
            this.remove(false);
        }
        if (getLifetime() > 0) {
            if(isLooping())
                t = (this.ageF % getLifetime()) / getLifetime();
            else
                t = Math.clamp(this.ageF / getLifetime(), 0f, 1f);
        }
    }

    protected void updateOrigin() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.oRoll = this.roll;
    }

    @Override
    protected int getLightCoords(float partialTick) {
        BlockPos blockPos = new BlockPos((int) this.x, (int) this.y, (int) this.z);
        var level = getLevel();
        if (level != null && (level.hasChunkAt(blockPos) || level instanceof DummyWorld)) {
            return LevelRenderer.getLightCoords(level, blockPos);
        }
        return 0;
    }

    public float getT(float partialTicks) {
        if (this.lifetime > 0){
            if (!isLooping()) return Math.clamp(t + partialTicks / this.lifetime, 0f, 1f);
            return t + partialTicks / this.lifetime;
        }
        return 0;
    }

    public float getMemRandom(Object object) {
        return getMemRandom(object, RandomSource::nextFloat);
    }

    public float getMemRandom(Object object, Function<RandomSource, Float> randomFunc) {
        var value = memRandom.get(object);
        if (value == null) return memRandom.computeIfAbsent(object, o -> randomFunc.apply(getRandomSource()));
        return value;
    }

    public void reset() {
        super.reset();
        this.memRandom.clear();
        this.previousPosition = null;
        this.velocity.zero();
        this.t = 0;
        this.ageF = 0;
    }

    /** This emitter's own contribution: active AND (still emitting or still showing particles). */
    private boolean isEmitting() {
        return isActive() && (!removed || getParticleAmount() != 0);
    }

    /**
     * Engine retention: an emitter that is still doing/showing something must also be active — a
     * timeline-deactivated emitter is frozen+hidden and survives clip gaps only through the runtime
     * keep-alive (in {@code super.isAlive()}), then dies with the timeline instead of lingering forever.
     */
    @Override
    public boolean isAlive() {
        return isEmitting() || super.isAlive(); // super = runtime keep-alive || live children
    }

    @Override
    public boolean isPlaying() {
        return isEmitting() || super.isPlaying();
    }

    @Override
    public int getLightColor(BlockPos pos) {
        return getLightColor(pos, 0);
    }

    @Override
    public int getLightColor(BlockPos pos, int lastLight) {
        if (parallelLightPhase) {
            // worker thread: read-only on the level. Record the position (on hit AND miss, so the
            // wanted-set tracks live particles) and fall back to the caller's last value on a miss;
            // rebuildLightCache() fills it on the game thread — correct next tick (1-tick latency).
            lightQueryQueue.add(pos);
            var cached = lightCache.get(pos);
            return cached != null ? cached : lastLight;
        }
        return lightCache.computeIfAbsent(pos, this::computeLightColor);
    }

    /** Game-thread only: the actual level/light-engine query. */
    private int computeLightColor(BlockPos pos) {
        var level = getLevel();
        if (level != null && (level.hasChunkAt(pos) || level instanceof DummyWorld)) {
            return LevelRenderer.getLightCoords(level, pos);
        }
        return 0;
    }

    /**
     * Whether the light cache resets at tick begin (default). Emitters that update particles on
     * worker threads must return false — a begin-clear would guarantee a miss on every parallel
     * read — and call {@link #rebuildLightCache()} at tick end instead.
     */
    protected boolean clearsLightCacheOnTickBegin() {
        return true;
    }

    /** Toggled by the owning emitter around its worker-thread particle update phase. */
    protected void setParallelLightPhase(boolean value) {
        this.parallelLightPhase = value;
    }

    /**
     * Game-thread only: recompute every position requested this tick and evict the rest, so light
     * stays fresh (≤1 tick stale) and the cache stays bounded to positions actually in use.
     */
    protected void rebuildLightCache() {
        lightCache.clear();
        for (var pos : lightQueryQueue) {
            lightCache.put(pos, computeLightColor(pos));
        }
        lightQueryQueue.clear();
    }

    /** 26.1: no Particle render-bounding-box hook — the M1 extraction culls against this instead. */
    @Nonnull
    public AABB getRenderBoundingBox(float partialTicks) {
        var cullBox = getCullBox(partialTicks);
        return cullBox == null ? AABB.INFINITE : cullBox;
    }

    public int getAge() {
        return (int) ageF;
    }

    /** Fractional simulation age (ticks), used by dt-aware emission cadence. */
    public float getAgeF() {
        return ageF;
    }

    public void setAge(int age) {
        this.ageF = age;
        this.age = age;
    }

    public boolean isLooping() {
        return false;
    }

    /**
     * Configured start delay in ticks before this emitter begins. Used (with {@link #getLifetime()})
     * to compute a sensible default timeline clip length. Defaults to 0; emitter types with a start
     * delay override this.
     */
    public int getStartDelay() {
        return 0;
    }

    public void setRGBAColor(Vector4f color) {
        this.rCol = color.x;
        this.gCol = color.y;
        this.bCol = color.z;
        this.alpha = color.w;
    }

    public Vector4f getRGBAColor() {
        return new Vector4f(rCol, gCol, bCol, alpha);
    }

    //////////////////////////////////////
    //********    Extraction   *********//
    //////////////////////////////////////

    /** The renderer-setting runtime for this emitter (uniform slot-or-config access for extraction). */
    public abstract RendererSetting.Runtime rendererRuntime();

    /** The primitive mode this emitter's geometry is written in (1.21: quads for tiles/beams,
     *  TRIANGLE_STRIP for trails, TRIANGLES for ara-trails). */
    public VertexFormat.Mode geometryMode() {
        return VertexFormat.Mode.QUADS;
    }

    /** Bakes one geometry group's camera-relative vertices. An emitter may extract several groups
     *  (e.g. particle tiles + their embedded per-particle trails), each with its own renderer
     *  settings and primitive mode. */
    @FunctionalInterface
    protected interface GeometryBaker {
        void bake(VertexConsumer geometry, Camera camera, float partialTicks);
    }

    /**
     * Extraction seam (design D2 = extract-time baking): bake this emitter's live geometry into
     * camera-relative vertices, one batch per material. Runs inside
     * {@code PhotonParticleGroup.extractRenderState} — live state is only read here, the submit
     * callback merely replays baked ops. Empty batches are dropped by {@code endBatch}.
     */
    public void extractBatches(PhotonFXRenderState state,
                               Camera camera, float partialTicks) {
        extractGroup(state, camera, partialTicks, rendererRuntime(), geometryMode(), this::bakeGeometry);
    }

    /** Extract one geometry group: one batch/job per material, plus the editor wireframe overlay. */
    protected final void extractGroup(PhotonFXRenderState state, Camera camera, float partialTicks,
                                      RendererSetting.Runtime renderer, VertexFormat.Mode mode,
                                      GeometryBaker baker) {
        // editor scenes honour the SceneView draw-mode toggles (world rendering always shades).
        // Detect by the CAMERA, not the level: editor FXObjects carry a null level (debug-verified),
        // while scene extraction always hands us LDLib2's SceneCamera.
        var editorScene = camera instanceof SceneCamera;
        var shaded = !editorScene || PhotonEditorRenderState.drawShaded;
        var wireframe = editorScene && PhotonEditorRenderState.drawWireframe;
        // the eye in the space the bakers write: Photon bakes camera-relative, and the editor's
        // SceneCamera sits at the origin while the real viewer is elsewhere
        var sortEye = PhotonCameraUtils.facingEye(camera)
                .sub(new Vector3f((float) camera.position().x, (float) camera.position().y,
                        (float) camera.position().z), new Vector3f());
        var sorting = renderer.getVertexSortingMode().vertexSorting(sortEye);
        if (shaded) {
            for (var materialSetting : renderer.getMaterials()) {
                var renderType = materialSetting.getRenderType(mode);
                if (renderType == null) {
                    continue; // material not ported yet — skip rather than render wrongly
                }
                if (materialSetting.pipelineKey(mode).blend() == null) {
                    // opaque: vanilla solid-features phase only (depth-writing → correct world
                    // occlusion). No bloom participation: the base is drawn by vanilla in LDR, so
                    // a bloom-source replay can only add a halo around an unchanged LDR core —
                    // worse than no bloom. Blooming opaque needs the full HDR-offscreen pipeline.
                    var geometry = state.beginBatch();
                    baker.bake(geometry, camera, partialTicks);
                    state.endBatch(renderType, geometry);
                } else {
                    // translucent/HDR: Photon's own draw slot after vanilla translucent particles
                    // (1.21 semantics + deterministic cross-batch ordering); we own the draw, so
                    // bake straight to MeshData — single materialization
                    bakeWorldPassJob(renderType, mode, camera, partialTicks, editorScene,
                            renderer.getOrderInLayer(), sorting, baker);
                }
            }
        }
        if (wireframe) {
            // overlay always on top of this emitter's own layer
            bakeWorldPassJob(MaterialRenderTypes.wireframe(mode),
                    mode, camera, partialTicks, true, Integer.MAX_VALUE, null, baker);
        }
    }

    // ---- instanced extraction (shared by the emitters' GPU-instanced groups) --------------------

    /**
     * Fill the instance staging (plus the points staging when the variant pulls per-point data, and the
     * two additional-GPU-data records when a material on the pass reads them). Returns the instance count.
     * <p>
     * The records are fetched by {@code gl_InstanceID}, and the attribute tail lives in the instance record
     * itself, so a fill must append them per instance in the same order it writes the instances — exactly
     * where 1.21's {@code uploadInstances} did: {@code uploadAttribs} into {@code instances}, then
     * {@code uploadDataRecord}/{@code uploadCustomRecord} into their own buffers.
     */
    @FunctionalInterface
    protected interface InstanceFiller {
        int fill(FloatBuffer instances, @Nullable FloatBuffer points,
                 @Nullable FloatBuffer data, @Nullable FloatBuffer custom);
    }

    /**
     * The instanced base geometry: the shape one instance expands over. {@code indices} null = the shared
     * sequential-quad indices, which is every Photon base mesh except the ara tube ring — that one shares
     * vertices between adjacent section edges, so it ships its own index buffer rather than duplicating
     * them (a duplicated ring costs ~1.8x the vertex-shader invocations).
     */
    public record BaseMesh(GpuBuffer vertices, int indexCount,
                           @Nullable GpuBuffer indices) {
        public static BaseMesh quads(GpuBuffer vertices, int indexCount) {
            return new BaseMesh(vertices, indexCount, null);
        }
    }

    private transient PhotonInstanceRing[] instanceRings;
    private transient PhotonInstanceRing[] pointRings;
    private transient PhotonInstanceRing[] dataRings;
    private transient PhotonInstanceRing[] customRings;

    private PhotonInstanceRing ringFor(PhotonInstanceRing[] rings, int slot) {
        if (rings[slot] == null) {
            rings[slot] = new PhotonInstanceRing();
            com.lowdragmc.photon.client.AutoCloseCleaner.registerRenderThread(this, rings[slot]);
        }
        return rings[slot];
    }

    private static FloatBuffer instanceStaging;
    private static FloatBuffer pointStaging;
    private static FloatBuffer dataStaging;
    private static FloatBuffer customStaging;

    private static FloatBuffer staging(@Nullable FloatBuffer current, int floats) {
        if (current == null || current.capacity() < floats) {
            current = org.lwjgl.BufferUtils.createFloatBuffer(Math.max(floats,
                    current == null ? 10000 : current.capacity() * 2));
        }
        current.clear();
        return current;
    }

    /**
     * Union of the additional-data channels the pass's shadergraph materials read — fed into
     * {@code AdditionalGPUDataSetting.setMaterialMask} so an instanced pass auto-enables whatever its
     * graphs need (hand-written shader materials toggle channels manually instead). 1.21 parity:
     * {@code PhotonFXRenderPass.shaderGraphChannelMask}.
     */
    private static long shaderGraphChannelMask(List<MaterialSetting> materials) {
        var mask = 0L;
        for (var materialSetting : materials) {
            if (rawMaterial(materialSetting) instanceof ShaderGraphMaterial graph) {
                mask |= graph.getUsedChannelMask();
            }
        }
        return mask;
    }

    /** Whether any shadergraph material on the pass reads user custom data (a {@code CustomDataNode}) —
     *  1.21 parity: {@code PhotonFXRenderPass.shaderGraphUsesCustomData}. */
    private static boolean shaderGraphUsesCustomData(List<MaterialSetting> materials) {
        for (var materialSetting : materials) {
            if (rawMaterial(materialSetting) instanceof ShaderGraphMaterial graph && graph.usesCustomData()) {
                return true;
            }
        }
        return false;
    }

    /** Unwrap the resource-library indirection so the checks above see the real material. */
    private static IMaterial rawMaterial(MaterialSetting materialSetting) {
        var material = materialSetting.getMaterial();
        return material instanceof UIResourceMaterial ui
                ? ui.getRawMaterial() : material;
    }

    /**
     * Whether a staged record buffer holds exactly one record per instance. The shader fetches it by
     * {@code gl_InstanceID * texels + slot}, so ONE missing or extra record silently misaligns every
     * instance after it — a fill that doesn't write a record per instance drops the buffer instead
     * (the accessors then read whatever the unbound sampler gives, which is 0, not garbage geometry).
     */
    private static boolean recordsComplete(@Nullable FloatBuffer records, int instanceCount,
                                           int texelsPerInstance, String name) {
        if (records == null) {
            return false;
        }
        var expected = instanceCount * texelsPerInstance * 4;
        if (records.position() == expected) {
            return true;
        }
        Photon.LOGGER.warn("{} staged {} floats for {} instances (expected {}) — skipping the upload",
                name, records.position(), instanceCount, expected);
        return false;
    }

    /** The bloom twin of a material's pipeline key: same state, depth writes off — the bloom pass
     *  re-draws the same geometry into the encoded source and must not disturb the depth buffer. */
    private static PhotonPipelines.ParticlePipelineKey bloomKey(PhotonPipelines.ParticlePipelineKey key) {
        return new PhotonPipelines.ParticlePipelineKey(
                key.blend(), key.blendEquation(), key.cull(), key.depthTest(), false, key.mode(), key.wireframe());
    }

    /**
     * Extract one geometry group as instanced draws (one {@code InstancedJob} per material, one
     * shared instance/point buffer). Returns false when any material can't take the shared
     * instanced vertex stage — the caller then falls back to the CPU bake so every material
     * renders consistently.
     */
    protected final boolean extractInstancedGroup(Camera camera,
                                                  RendererSetting.Runtime renderer,
                                                  AdditionalGPUDataSetting setting,
                                                  PhotonPipelines.InstancedVariant variant,
                                                  BaseMesh mesh,
                                                  int instanceFloatCapacity, int pointFloatCapacity,
                                                  Vector3f positionOffset,
                                                  InstanceFiller filler) {
        var editorScene = camera instanceof SceneCamera;
        var shaded = !editorScene || PhotonEditorRenderState.drawShaded;
        var wireframe = editorScene && PhotonEditorRenderState.drawWireframe;
        var infos = new ArrayList<PhotonRenderTypes.PhotonDrawInfo>();
        var slices = new ArrayList<GpuBufferSlice>();
        for (var materialSetting : renderer.getMaterials()) {
            var renderType = materialSetting.getRenderType(VertexFormat.Mode.QUADS);
            if (renderType == null) {
                return false;
            }
            var info = PhotonRenderTypes.drawInfo(renderType);
            // Opaque (blend null) is allowed here: instanced draws always run in Photon's slot
            // (the 1.21 semantics — no vanilla-solid-phase routing for instanced geometry), the
            // pipeline variant then uses the default color target + the key's depth write.
            if (info == null || info.hdrPipelineKey() == null) {
                return false;
            }
            infos.add(info);
            var slice = PhotonMaterialUniforms.sliceFor(renderType);
            // custom-shader types carry no material block — bind the default (vertex stage only)
            slices.add(slice != null ? slice
                    : PhotonMaterialUniforms.sliceFor(PhotonMaterialUniforms.Values.DEFAULT));
        }
        // A shadergraph reads whatever channels its nodes ask for, with no emitter toggle declaring them,
        // so the pass auto-enables their union (1.21 PhotonFXRenderPass.drawInstanced does exactly this,
        // right here — after the path is already decided; it is not part of choosing GPU vs CPU).
        var materialMask = shaderGraphChannelMask(renderer.getMaterials());
        setting.setMaterialMask(materialMask);
        setting.setCustomDataMaterialUsed(shaderGraphUsesCustomData(renderer.getMaterials()));
        // The user-toggled channels / custom-data streams ride as a divisor-1 attribute tail inside the
        // instance record (hand-written shaders read them as extra `in` declarations) — 1.21 grew the
        // instance stride the same way. Callers size by the BASE stride, so re-derive the capacity.
        var attribTail = setting.planAttribs(variant.layout.strideFloats());
        var layout = variant.layout.withInstanceTail(attribTail, setting.attribFloats());
        var instanceCapacity = instanceFloatCapacity / variant.layout.strideFloats();
        instanceStaging = staging(instanceStaging, instanceCapacity * layout.strideFloats());
        var points = variant.usesPoints ? (pointStaging = staging(pointStaging, pointFloatCapacity)) : null;
        var data = materialMask != 0L
                ? (dataStaging = staging(dataStaging, instanceCapacity * setting.dataTexels() * 4)) : null;
        var custom = setting.hasCustomRecord() && variant.usesCustomData
                ? (customStaging = staging(customStaging, instanceCapacity * setting.customDataTexels() * 4)) : null;
        var count = filler.fill(instanceStaging, points, data, custom);
        if (count == 0) {
            return true; // nothing alive — matches the CPU path's empty bake
        }
        instanceStaging.flip();
        var slots = PhotonPipelines.InstancedVariant.values().length;
        if (instanceRings == null) instanceRings = new PhotonInstanceRing[slots];
        if (pointRings == null) pointRings = new PhotonInstanceRing[slots];
        if (dataRings == null) dataRings = new PhotonInstanceRing[slots];
        if (customRings == null) customRings = new PhotonInstanceRing[slots];
        // per-variant ring slot: one emitter can extract several instanced groups per frame
        // (e.g. tiles + embedded trails) — texel uniforms bind whole buffers, no sharing
        var ring = ringFor(instanceRings, variant.ordinal());
        var instanceBuffer = ring.write(instanceStaging);
        PhotonWorldRenderState.trackInstanceRing(ring);
        GpuBuffer pointBuffer = null;
        if (points != null) {
            points.flip();
            var pRing = ringFor(pointRings, variant.ordinal());
            pointBuffer = pRing.write(points);
            PhotonWorldRenderState.trackInstanceRing(pRing);
        }
        GpuBuffer dataBuffer = null;
        if (recordsComplete(data, count, setting.dataTexels(), "PhotonData")) {
            data.flip();
            var dRing = ringFor(dataRings, variant.ordinal());
            dataBuffer = dRing.write(data);
            PhotonWorldRenderState.trackInstanceRing(dRing);
        }
        GpuBuffer customBuffer = null;
        if (recordsComplete(custom, count, setting.customDataTexels(), "PhotonCustomData")) {
            custom.flip();
            var cRing = ringFor(customRings, variant.ordinal());
            customBuffer = cRing.write(custom);
            PhotonWorldRenderState.trackInstanceRing(cRing);
        }
        var eye = PhotonCameraUtils.facingEye(camera);
        var distanceSq = transform.position().distanceSquared(eye);
        if (shaded) {
            for (int m = 0; m < infos.size(); m++) {
                var info = infos.get(m);
                var key = info.hdrPipelineKey();
                com.mojang.blaze3d.pipeline.RenderPipeline pipeline;
                com.mojang.blaze3d.pipeline.RenderPipeline bloomPipeline;
                if (info.graph() != null) {
                    // shader graphs compile the same generated GLSL against the instanced format + define
                    var graph = info.graph();
                    pipeline = PhotonPipelines.graphShader(graph.compiled(), variant, key,
                            graph.usedChannelMask(), graph.usesCustomData());
                    bloomPipeline = PhotonPipelines.graphShader(graph.compiled(), variant, bloomKey(key),
                            graph.usedChannelMask(), graph.usesCustomData());
                } else if (info.customShaderKey() != null) {
                    var ck = info.customShaderKey();
                    pipeline = PhotonPipelines.instancedCustomShader(variant, ck);
                    bloomPipeline = PhotonPipelines.instancedCustomShader(variant,
                            new PhotonPipelines.CustomShaderKey(ck.vertexShader(), ck.fragmentShader(),
                                    ck.defines(), ck.samplerNames(), ck.sceneSamplers(), bloomKey(key)));
                } else {
                    pipeline = PhotonPipelines.instancedHdrParticle(variant, info.hdrFragment(), key);
                    bloomPipeline = PhotonPipelines.instancedHdrParticle(variant, info.hdrFragment(), bloomKey(key));
                }
                PhotonWorldRenderState.add(editorScene, new PhotonWorldRenderState.InstancedJob(
                        pipeline, bloomPipeline,
                        info.textures(), slices.get(m),
                        mesh.vertices(), mesh.indexCount(), mesh.indices(),
                        instanceBuffer, count, pointBuffer,
                        dataBuffer, customBuffer, positionOffset,
                        key.blendEquation(), info.sceneSamplers(),
                        info.customUniforms() == null ? null : info.customUniforms().slice(),
                        layout, info.graph(),
                        renderer.getOrderInLayer(), distanceSq));
            }
        }
        if (wireframe) {
            var wfKey = PhotonPipelines.ParticlePipelineKey.wireframe(VertexFormat.Mode.QUADS);
            var wfPipeline = PhotonPipelines.instancedHdrParticle(variant, wfKey);
            PhotonWorldRenderState.add(true, new PhotonWorldRenderState.InstancedJob(wfPipeline, wfPipeline,
                    Map.of("Sampler0", com.lowdragmc.photon.Photon.id("textures/particle/white.png")),
                    PhotonMaterialUniforms.sliceFor(new PhotonMaterialUniforms.Values(0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 0)),
                    mesh.vertices(), mesh.indexCount(), mesh.indices(),
                    instanceBuffer, count, pointBuffer,
                    null, null, positionOffset, // the overlay's own shader reads no additional data
                    PhotonPipelines.BLEND_EQUATION_ADD, List.of(),
                    null,
                    layout, null, // wireframe overlay draws Photon's own shader, never a graph
                    Integer.MAX_VALUE, distanceSq));
        }
        return true;
    }

    private void bakeWorldPassJob(RenderType renderType,
                                  VertexFormat.Mode mode,
                                  Camera camera, float partialTicks,
                                  boolean editorScene, int orderInLayer,
                                  @Nullable com.mojang.blaze3d.vertex.VertexSorting sorting,
                                  GeometryBaker baker) {
        var buffer = new ByteBufferBuilder(64 * 1024);
        var builder = new BufferBuilder(buffer, mode, PhotonPipelines.PARTICLE_FORMAT);
        baker.bake(builder, camera, partialTicks);
        var mesh = builder.build();
        if (mesh == null) {
            buffer.close();
            return;
        }
        if (sorting != null) {
            sortQuads(mesh, sorting);
        }
        var eye = PhotonCameraUtils.facingEye(camera);
        var distanceSq = transform.position().distanceSquared(eye);
        PhotonWorldRenderState.add(editorScene,
                new PhotonWorldRenderState.Job(renderType, mesh, buffer, orderInLayer, distanceSq));
    }

    /**
     * Reorder the baked quads far-to-near, in place. 1.21 sorted by building a sorted INDEX buffer
     * ({@code MeshDataSorter.sortPrimitives}); Photon's 26.1 drain concatenates adjacent same-RenderType
     * jobs into one run over a shared ring buffer and draws them with the engine's sequential quad
     * indices, so a per-job index buffer would have to break that merging. Permuting the vertices instead
     * keeps the merge (and costs one memcpy of the mesh, against an index upload per job per frame).
     * <p>
     * QUADS only — the strip/triangle geometries have no independent primitives to reorder, which is the
     * same restriction {@code MeshDataSorter.getVerticesPerPrimitive} encodes.
     */
    private static void sortQuads(com.mojang.blaze3d.vertex.MeshData mesh,
                                  com.mojang.blaze3d.vertex.VertexSorting sorting) {
        var drawState = mesh.drawState();
        if (drawState.mode() != VertexFormat.Mode.QUADS) {
            return;
        }
        var stride = drawState.format().getVertexSize();
        var quads = drawState.vertexCount() / 4;
        if (quads < 2) {
            return;
        }
        var vertices = mesh.vertexBuffer();
        var centroids = new com.mojang.blaze3d.vertex.CompactVectorArray(quads);
        for (int quad = 0; quad < quads; quad++) {
            var base = quad * 4 * stride;
            float x = 0, y = 0, z = 0;
            for (int v = 0; v < 4; v++) {
                var offset = base + v * stride;
                x += vertices.getFloat(offset);
                y += vertices.getFloat(offset + Float.BYTES);
                z += vertices.getFloat(offset + 2 * Float.BYTES);
            }
            centroids.set(quad, x * 0.25f, y * 0.25f, z * 0.25f);
        }
        var order = sorting.sort(centroids);
        var quadBytes = 4 * stride;
        var scratch = new byte[quads * quadBytes];
        for (int i = 0; i < quads; i++) {
            var source = order[i] * quadBytes;
            for (int b = 0; b < quadBytes; b++) {
                scratch[i * quadBytes + b] = vertices.get(source + b);
            }
        }
        for (int b = 0; b < scratch.length; b++) {
            vertices.put(b, scratch[b]);
        }
    }

    /** Write this emitter's camera-relative vertices for one material batch (the default group). */
    protected abstract void bakeGeometry(VertexConsumer geometry, Camera camera, float partialTicks);
}
