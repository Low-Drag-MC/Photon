package com.lowdragmc.photon.client.gameobject.emitter;

import com.lowdragmc.lowdraglib2.utils.virtuallevel.DummyWorld;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.AutoCloseCleaner;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.AdditionalGPUDataSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.PhotonGpuChannels;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialRenderTypes;
import com.lowdragmc.photon.client.gameobject.particle.renderer.CpuParticleRecords;
import com.lowdragmc.photon.client.render.*;
import com.lowdragmc.photon.client.render.PremultipliedBlendPlan;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import lombok.Getter;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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
     * Extraction seam: register this emitter's geometry as deferred work on the view that is
     * collecting it. Runs inside {@code PhotonParticleGroup.extractRenderState}, and only decides
     * that this emitter is visible — the vertices are generated later, in {@link #bakeBatches},
     * once the drain knows which view (and therefore which {@link PhotonViewSettings}) is asking.
     * That mirrors vanilla's extract/prepare split: a view that never drains never pays for baking.
     */
    public final void extractBatches(PhotonFXRenderState state, Camera camera, float partialTicks) {
        state.defer((settings, out) -> bakeBatches(settings, out, camera, partialTicks));
    }

    /**
     * Deferred geometry generation, run once per draining view. Emitters with GPU-instanced paths
     * override this to try {@link #bakeInstancedGroup} first and fall back to the CPU bake.
     */
    protected void bakeBatches(PhotonViewSettings settings, List<PhotonWorldRenderState.DrawJob> out,
                               Camera camera, float partialTicks) {
        bakeGroup(settings, out, camera, partialTicks, rendererRuntime(), geometryMode(), this::bakeGeometry);
    }

    /** Bake one geometry group: one job per material, plus the editor wireframe overlay. */
    protected final void bakeGroup(PhotonViewSettings settings, List<PhotonWorldRenderState.DrawJob> out,
                                   Camera camera, float partialTicks,
                                   RendererSetting.Runtime renderer, VertexFormat.Mode mode,
                                   GeometryBaker baker) {
        bakeGroup(settings, out, camera, partialTicks, renderer, mode, baker, null);
    }

    /** Per-particle records for a CPU group with a fixed vertex count per particle; {@code collect} points the baker
     *  at them, null stops it. */
    public record CpuRecordSource(AdditionalGPUDataSetting setting, Consumer<CpuParticleRecords> collect) {
    }

    /** As above, plus per-particle records for the materials that read particle data (their PHOTON_CPU_DATA
     *  pipeline). */
    protected final void bakeGroup(PhotonViewSettings settings, List<PhotonWorldRenderState.DrawJob> out,
                                   Camera camera, float partialTicks,
                                   RendererSetting.Runtime renderer, VertexFormat.Mode mode,
                                   GeometryBaker baker, @Nullable CpuRecordSource recordSource) {
        // collected once and shared: bakes and sorts are deterministic, so every material sees the same order
        PhotonWorldRenderState.CpuRecords records = null;
        boolean recordsTried = false;
        // the eye in the space the bakers write: Photon bakes camera-relative, and the editor's
        // SceneCamera sits at the origin while the real viewer is elsewhere
        var sortEye = PhotonCameraUtils.facingEye(camera).sub(PhotonCameraUtils.renderOrigin(camera));
        var sortOrigin = renderer.getVertexSortingMode().sortOrigin(sortEye);
        if (settings.shaded()) {
            // the renderer's Layer picks the frame slot Photon opens its own pass at; every
            // material of the group shares it (1.21: the emitter went into one queue as a whole)
            var stage = renderer.effectiveStage();
        // Deferred groups accumulate into a transparent layer, which changes what every material's
        // blend must do to the alpha channel. Set around the geometry this group generates, so a
        // group drawn in place in the same bake is unaffected.
        PremultipliedBlendPlan.setAccumulating(
                PremultipliedBlendPlan.isLayerStage(stage));
            for (var materialSetting : renderer.getMaterials()) {
                var renderType = materialSetting.getRenderType(mode);
                if (renderType == null) {
                    continue; // material not ported yet — skip rather than render wrongly
                }
                // the CustomMask sub-pass alpha-clips against the pass's OWN texture — the one this
                // material bound as Sampler0 (1.21 hunted for a TextureMaterial to the same end)
                var info = PhotonRenderTypes.drawInfo(renderType);
                var mask = PhotonWorldRenderState.MaskWrite.of(renderer,
                        info == null ? null : info.bindings().textures().get("Sampler0"));
                var recordPipeline = recordSource == null || info == null || !readsParticleRecords(materialSetting)
                        ? null : PhotonRenderTypes.cpuDataPipeline(info);
                if (recordPipeline != null && !recordsTried) {
                    recordsTried = true;
                    records = bakeRecords(recordSource, renderer.getMaterials(), mode, camera, partialTicks,
                            sortOrigin, baker);
                }
                bakeJob(out, renderType, mode, camera, partialTicks, stage,
                        renderer.getOrderInLayer(), sortOrigin, baker, mask, materialSetting.isBackFacesFirst(),
                        recordPipeline == null || records == null ? null : records.with(recordPipeline));
            }
        }
        if (settings.wireframe()) {
            // overlay always on top of everything, whatever layer the emitter itself draws in
            bakeJob(out, MaterialRenderTypes.wireframe(mode), mode, camera, partialTicks,
                    PhotonStage.AFTER_TRANSLUCENT_PARTICLES, Integer.MAX_VALUE, null, baker,
                    null, false, null); // an editor overlay is not content — it never writes the mask
        }
    }

    // ---- instanced bake (shared by the emitters' GPU-instanced groups) --------------------------

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
                           @Nullable GpuBuffer indices,
                           @Nullable GpuBuffer vat, @Nullable GpuBufferSlice vatInfo) {
        public static BaseMesh quads(GpuBuffer vertices, int indexCount) {
            return new BaseMesh(vertices, indexCount, null, null, null);
        }

        public BaseMesh(GpuBuffer vertices, int indexCount, @Nullable GpuBuffer indices) {
            this(vertices, indexCount, indices, null, null);
        }

        /** This mesh posed from a baked pose table — {@code MODEL_VAT}/{@code MODEL_VAT_TANGENT}. */
        public BaseMesh withVat(@Nullable GpuBuffer vat, @Nullable GpuBufferSlice vatInfo) {
            return new BaseMesh(vertices, indexCount, indices, vat, vatInfo);
        }
    }

    /** What the {@code PHOTON_VAT} vertex stage fetches to build its per-particle phase — the
     *  per-particle random and the particle's own t, out of {@code PhotonData} slot 0. MIRRORED FROM
     *  the PHOTON_VAT block of {@code particle.glsl}. */
    private static final long VAT_PHASE_CHANNELS = PhotonGpuChannels.maskOf(
            PhotonGpuChannels.Kind.TILE_MODEL,
            List.of("addition_gpu_data.random", "addition_gpu_data.t"));

    private transient PhotonInstanceRing[] instanceRings;
    private transient PhotonInstanceRing[] pointRings;
    private transient PhotonInstanceRing[] dataRings;
    private transient PhotonInstanceRing[] customRings;

    private PhotonInstanceRing ringFor(PhotonInstanceRing[] rings, int slot) {
        if (rings[slot] == null) {
            rings[slot] = new PhotonInstanceRing();
            AutoCloseCleaner.registerRenderThread(this, rings[slot]);
        }
        return rings[slot];
    }

    /**
     * Shared bake scratch, grown on demand and never shrunk. RENDER THREAD ONLY, and the whole set has
     * to become per-worker together (this one, the four staging buffers, and
     * {@link PhotonDistanceSort}'s arrays) if the bake is ever parallelised per emitter.
     */
    private static FloatBuffer instanceStaging;
    private static FloatBuffer pointStaging;
    private static FloatBuffer dataStaging;
    private static FloatBuffer customStaging;
    @Nullable
    private static ByteBuffer quadStaging;

    private static FloatBuffer staging(@Nullable FloatBuffer current, int floats) {
        if (current == null || current.capacity() < floats) {
            current = BufferUtils.createFloatBuffer(Math.max(floats,
                    current == null ? 10000 : current.capacity() * 2));
        }
        current.clear();
        return current;
    }

    /** Direct scratch for {@link #sortQuads}'s permutation (direct so it can be memCopy'd). */
    private static ByteBuffer quadScratch(int bytes) {
        var current = quadStaging;
        if (current == null || current.capacity() < bytes) {
            current = BufferUtils.createByteBuffer(Math.max(bytes,
                    current == null ? 64 * 1024 : current.capacity() * 2));
            quadStaging = current;
        }
        return current;
    }

    /**
     * Union of the additional-data channels the pass's materials read — fed into
     * {@code AdditionalGPUDataSetting.setMaterialMask} so an instanced pass auto-enables whatever they need
     * (hand-written shader materials toggle channels manually instead). Any material may ask
     * ({@link IMaterial#getUsedChannelMask}): a shader graph through its nodes, a KilaMaterial through its drivers.
     * 1.21 parity: {@code PhotonFXRenderPass.materialChannelMask}.
     */
    static long materialChannelMask(List<MaterialSetting> materials) {
        var mask = 0L;
        for (var materialSetting : materials) {
            mask |= materialSetting.getMaterial().getUsedChannelMask();
        }
        return mask;
    }

    /** Whether any material on the pass reads user custom data — 1.21 parity:
     *  {@code PhotonFXRenderPass.materialsUseCustomData}. */
    static boolean materialsUseCustomData(List<MaterialSetting> materials) {
        for (var materialSetting : materials) {
            if (materialSetting.getMaterial().usesCustomData()) {
                return true;
            }
        }
        return false;
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

    /**
     * The far-to-near instance order for {@code SortMode.DISTANCE}, or null when this group draws in
     * emit order. The instanced twin of the CPU path's {@link #sortQuads}: instead of permuting
     * vertices it permutes whole instance records (see {@code PhotonInstanceRing.write(..., order, ...)}),
     * so one instanced draw blends back-to-front internally.
     * <p>
     * Only variants with {@link PhotonPipelines.InstancedVariant#positionAtRecordHead} qualify — the
     * key is read straight out of the staged record's first three floats, which is also why this needs
     * no per-variant unpacking. Cross-emitter ordering stays with the drain's {@code DRAW_ORDER}.
     */
    @Nullable
    private static int[] instanceDrawOrder(PhotonPipelines.InstancedVariant variant,
                                           PhotonInstancedDrawState.Layout layout,
                                           Camera camera, RendererSetting.Runtime renderer,
                                           Vector3f positionOffset, int count) {
        if (count < 2 || !variant.positionAtRecordHead) {
            return null;
        }
        // The eye in the space the records were written in. Tile/model write render-origin-relative
        // with a zero positionOffset; beam writes EYE-relative and carries the eye→origin delta as
        // its offset — so eye = sortEye - positionOffset covers both (0 in the eye-relative case).
        var eye = PhotonCameraUtils.facingEye(camera)
                .sub(PhotonCameraUtils.renderOrigin(camera))
                .sub(positionOffset);
        var sortOrigin = renderer.getVertexSortingMode().sortOrigin(eye);
        if (sortOrigin == null) {
            return null;
        }
        var stride = layout.strideFloats();
        var keys = PhotonDistanceSort.keys(count);
        for (int i = 0; i < count; i++) {
            var base = i * stride;
            keys[i] = sortOrigin.distanceSquared(instanceStaging.get(base),
                    instanceStaging.get(base + 1), instanceStaging.get(base + 2));
        }
        return PhotonDistanceSort.farToNear(count);
    }

    /**
     * Extract one geometry group as instanced draws (one {@code InstancedJob} per material, one
     * shared instance/point buffer). Returns false when any material can't take the shared
     * instanced vertex stage — the caller then falls back to the CPU bake so every material
     * renders consistently.
     */
    protected final boolean bakeInstancedGroup(PhotonViewSettings settings,
                                               List<PhotonWorldRenderState.DrawJob> out,
                                               Camera camera,
                                               RendererSetting.Runtime renderer,
                                               AdditionalGPUDataSetting setting,
                                               PhotonPipelines.InstancedVariant variant,
                                               BaseMesh mesh,
                                               int instanceFloatCapacity, int pointFloatCapacity,
                                               Vector3f positionOffset,
                                               InstanceFiller filler) {
        var shaded = settings.shaded();
        var wireframe = settings.wireframe();
        var infos = new ArrayList<PhotonRenderTypes.PhotonDrawInfo>();
        var slices = new ArrayList<GpuBufferSlice>();
        for (var materialSetting : renderer.getMaterials()) {
            var renderType = materialSetting.getRenderType(VertexFormat.Mode.QUADS);
            if (renderType == null) {
                return false;
            }
            var info = PhotonRenderTypes.drawInfo(renderType);
            if (info == null || info.instanced() == null) {
                return false;
            }
            // a material deriving its own instanced program may fail to compile one: then the group draws on the CPU
            var source = info.instanced().source();
            if (source != null && info.bindings().graph() == null && source.pipeline(variant, false) == null) {
                return false;
            }
            infos.add(info);
            var slice = PhotonMaterialUniforms.sliceFor(renderType);
            // custom-shader types carry no material block — bind the default (vertex stage only)
            slices.add(slice != null ? slice
                    : PhotonMaterialUniforms.sliceFor(PhotonMaterialUniforms.Values.DEFAULT));
        }
        // A material reads whatever channels it asks for, with no emitter toggle declaring them,
        // so the pass auto-enables their union (1.21 PhotonFXRenderPass.drawInstanced does exactly this,
        // right here — after the path is already decided; it is not part of choosing GPU vs CPU).
        // ⚠️ A VAT draw reads the random and t out of the same records whether or not any material asked
        // for them — its pipeline declares PhotonData, and a declared texel buffer must be bound.
        var materialMask = materialChannelMask(renderer.getMaterials())
                | (variant.usesVat() ? VAT_PHASE_CHANNELS : 0L);
        setting.setMaterialMask(materialMask);
        setting.setCustomDataMaterialUsed(materialsUseCustomData(renderer.getMaterials()));
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
        var order = instanceDrawOrder(variant, layout, camera, renderer, positionOffset, count);
        var slots = PhotonPipelines.InstancedVariant.values().length;
        if (instanceRings == null) instanceRings = new PhotonInstanceRing[slots];
        if (pointRings == null) pointRings = new PhotonInstanceRing[slots];
        if (dataRings == null) dataRings = new PhotonInstanceRing[slots];
        if (customRings == null) customRings = new PhotonInstanceRing[slots];
        // per-variant ring slot: one emitter can extract several instanced groups per frame
        // (e.g. tiles + embedded trails) — texel uniforms bind whole buffers, no sharing
        var ring = ringFor(instanceRings, variant.ordinal());
        var instanceBuffer = order == null ? ring.write(instanceStaging)
                : ring.write(instanceStaging, order, count, layout.strideFloats());
        PhotonWorldRenderState.trackInstanceRing(ring);
        GpuBuffer pointBuffer = null;
        if (points != null) {
            // never permuted: instances of a point-using variant index into this shared block
            points.flip();
            var pRing = ringFor(pointRings, variant.ordinal());
            pointBuffer = pRing.write(points);
            PhotonWorldRenderState.trackInstanceRing(pRing);
        }
        GpuBuffer dataBuffer = null;
        if (recordsComplete(data, count, setting.dataTexels(), "PhotonData")) {
            data.flip();
            var dRing = ringFor(dataRings, variant.ordinal());
            // fetched by gl_InstanceID → must follow the instances' permutation exactly
            dataBuffer = order == null ? dRing.write(data)
                    : dRing.write(data, order, count, setting.dataTexels() * 4);
            PhotonWorldRenderState.trackInstanceRing(dRing);
        }
        if (variant.usesVat() && dataBuffer == null) {
            // Not optional for a VAT pipeline: it DECLARES PhotonData (that is where the phase's random
            // and t come from), and 26.1 fails the draw outright on a declared-but-unbound uniform. Give
            // the group back to the CPU path, which poses from the same table via poseBaseFor.
            return false;
        }
        GpuBuffer customBuffer = null;
        if (recordsComplete(custom, count, setting.customDataTexels(), "PhotonCustomData")) {
            custom.flip();
            var cRing = ringFor(customRings, variant.ordinal());
            customBuffer = order == null ? cRing.write(custom)
                    : cRing.write(custom, order, count, setting.customDataTexels() * 4);
            PhotonWorldRenderState.trackInstanceRing(cRing);
        }
        var eye = PhotonCameraUtils.facingEye(camera);
        var distanceSq = transform.position().distanceSquared(eye);
        var stage = renderer.effectiveStage();
        // Deferred groups accumulate into a transparent layer, which changes what every material's
        // blend must do to the alpha channel. Set around the geometry this group generates, so a
        // group drawn in place in the same bake is unaffected.
        PremultipliedBlendPlan.setAccumulating(
                PremultipliedBlendPlan.isLayerStage(stage));
        if (shaded) {
            for (int m = 0; m < infos.size(); m++) {
                var info = infos.get(m);
                // the CustomMask sub-pass alpha-clips against the pass's OWN texture, which is exactly
                // what this material bound as Sampler0 (1.21 hunted for a TextureMaterial to the same end)
                var mask = PhotonWorldRenderState.MaskWrite.of(renderer,
                        info.bindings().textures().get("Sampler0"));
                var recipe = info.instanced();
                var key = recipe.key();
                RenderPipeline pipeline;
                if (info.bindings().graph() != null) {
                    // shader graphs compile the same generated GLSL against the instanced format + define
                    var graph = info.bindings().graph();
                    pipeline = PhotonPipelines.graphShader(graph.compiled(), variant, key,
                            graph.usedChannelMask(), graph.usesCustomData());
                } else if (recipe.source() != null) {
                    pipeline = recipe.source().pipeline(variant, false);
                } else if (recipe.customShaderKey() != null) {
                    pipeline = PhotonPipelines.instancedCustomShader(variant, recipe.customShaderKey());
                } else {
                    pipeline = PhotonPipelines.instancedHdrParticle(variant, recipe.hdrFragment(), key);
                }
                var customUniforms = info.bindings().customUniforms();
                out.add(new PhotonWorldRenderState.InstancedJob(
                        new PhotonRenderTypes.PhotonDrawInfo.Programs(pipeline),
                        new PhotonWorldRenderState.InstancedGeometry(
                                mesh.vertices(), mesh.indexCount(), mesh.indices(),
                                instanceBuffer, count, pointBuffer, dataBuffer, customBuffer, layout,
                                mesh.vat(), mesh.vatInfo()),
                        new PhotonWorldRenderState.DrawBindings(
                                info.bindings().textures(), slices.get(m),
                                customUniforms == null ? null : customUniforms.slice(),
                                info.bindings().sceneSamplers(), info.bindings().graph(),
                                info.bindings().binder()),
                        positionOffset, key.blendEquation(),
                        stage, renderer.getOrderInLayer(), distanceSq, variant, mask,
                        renderer.getMaterials().get(m).isBackFacesFirst()));
            }
        }
        if (wireframe) {
            var wfKey = PhotonPipelines.ParticlePipelineKey.wireframe(VertexFormat.Mode.QUADS);
            var wfPipeline = PhotonPipelines.instancedHdrParticle(variant, wfKey);
            out.add(new PhotonWorldRenderState.InstancedJob(
                    new PhotonRenderTypes.PhotonDrawInfo.Programs(wfPipeline),
                    new PhotonWorldRenderState.InstancedGeometry(
                            mesh.vertices(), mesh.indexCount(), mesh.indices(),
                            instanceBuffer, count, pointBuffer,
                            // the overlay's own shader reads no additional data — but a VAT variant's
                            // vertex stage still poses from the table, so those two ride along
                            dataBuffer, null,
                            layout, mesh.vat(), mesh.vatInfo()),
                    new PhotonWorldRenderState.DrawBindings(
                            Map.of("Sampler0", Photon.id("textures/particle/white.png")),
                            PhotonMaterialUniforms.sliceFor(
                                    new PhotonMaterialUniforms.Values(0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 0)),
                            null, List.of(),
                            null), // wireframe overlay draws Photon's own shader, never a graph
                    positionOffset, PhotonPipelines.BLEND_EQUATION_ADD,
                    PhotonStage.AFTER_TRANSLUCENT_PARTICLES, Integer.MAX_VALUE, distanceSq,
                    variant, null, false)); // an editor overlay is not content — it never writes the mask
        }
        return true;
    }

    private void bakeJob(List<PhotonWorldRenderState.DrawJob> out,
                         RenderType renderType,
                         VertexFormat.Mode mode,
                         Camera camera, float partialTicks,
                         PhotonStage stage, int orderInLayer,
                         @Nullable Vector3fc sortOrigin,
                         GeometryBaker baker,
                         @Nullable PhotonWorldRenderState.MaskWrite mask, boolean backFacesFirst,
                         @Nullable PhotonWorldRenderState.CpuRecords records) {
        var buffer = new ByteBufferBuilder(64 * 1024);
        var builder = new BufferBuilder(buffer, mode, PhotonPipelines.PARTICLE_FORMAT);
        baker.bake(builder, camera, partialTicks);
        var mesh = builder.build();
        if (mesh == null) {
            buffer.close();
            return;
        }
        if (sortOrigin != null) {
            // with records, whole particles move: the records were permuted in the same order (bakeRecords)
            sortQuads(mesh, sortOrigin, records == null ? 1 : records.vertsPerParticle() / 4);
        }
        var eye = PhotonCameraUtils.facingEye(camera);
        var distanceSq = transform.position().distanceSquared(eye);
        out.add(new PhotonWorldRenderState.Job(renderType, mesh, buffer, stage, orderInLayer,
                distanceSq, mask, backFacesFirst, records));
    }

    /** Whether a material reads per-particle data, which the CPU path otherwise feeds it as zeros. */
    private static boolean readsParticleRecords(MaterialSetting materialSetting) {
        var material = materialSetting.getMaterial();
        return material.getUsedChannelMask() != 0L || material.usesCustomData();
    }

    @Nullable
    private transient PhotonInstanceRing cpuDataRing;
    @Nullable
    private transient PhotonInstanceRing cpuCustomRing;
    @Nullable
    private transient CpuParticleRecords cpuRecords;

    /**
     * Bake the group once more just to collect its records in vertex order, sort them with the particles, and
     * upload them. Null when they cannot be trusted to line up with the vertices — a particle that wrote a different
     * number of vertices would shift every record after it — in which case the materials read zeros, as before.
     */
    @Nullable
    private PhotonWorldRenderState.CpuRecords bakeRecords(CpuRecordSource source, List<MaterialSetting> materials,
                                                          VertexFormat.Mode mode, Camera camera, float partialTicks,
                                                          @Nullable Vector3fc sortOrigin, GeometryBaker baker) {
        if (mode != VertexFormat.Mode.QUADS) return null;
        var setting = source.setting();
        setting.setMaterialMask(materialChannelMask(materials));
        setting.setCustomDataMaterialUsed(materialsUseCustomData(materials));
        if (cpuRecords == null) cpuRecords = new CpuParticleRecords();
        var records = cpuRecords;
        records.begin(setting, Math.max(getParticleAmount(), 16));
        var buffer = new ByteBufferBuilder(64 * 1024);
        try {
            source.collect().accept(records);
            var builder = new BufferBuilder(buffer, mode, PhotonPipelines.PARTICLE_FORMAT);
            baker.bake(builder, camera, partialTicks);
            source.collect().accept(null);
            records.end();
            try (var mesh = builder.build()) {
                int particles = records.count();
                int verts = records.vertsPerParticle();
                if (mesh == null || particles == 0 || verts % 4 != 0
                        || mesh.drawState().vertexCount() != particles * verts) {
                    return null;
                }
                if (sortOrigin != null) {
                    var order = sortQuads(mesh, sortOrigin, verts / 4);
                    if (order != null) records.permute(order, particles);
                }
            }
        } finally {
            source.collect().accept(null);
            buffer.close();
        }
        GpuBuffer data = null;
        GpuBuffer custom = null;
        var dataRecords = records.data();
        if (dataRecords != null) {
            if (cpuDataRing == null) {
                cpuDataRing = new PhotonInstanceRing();
                AutoCloseCleaner.registerRenderThread(this, cpuDataRing);
            }
            data = cpuDataRing.write(dataRecords);
            PhotonWorldRenderState.trackInstanceRing(cpuDataRing);
        }
        var customRecords = records.custom();
        if (customRecords != null) {
            if (cpuCustomRing == null) {
                cpuCustomRing = new PhotonInstanceRing();
                AutoCloseCleaner.registerRenderThread(this, cpuCustomRing);
            }
            custom = cpuCustomRing.write(customRecords);
            PhotonWorldRenderState.trackInstanceRing(cpuCustomRing);
        }
        if (data == null && custom == null) return null;
        return new PhotonWorldRenderState.CpuRecords(null, data, custom, records.vertsPerParticle());
    }

    /**
     * Reorder the baked quads far-to-near in place, {@code quadsPerGroup} at a time (a whole particle when CPU records
     * index by vertex; they move by the returned order). Vertices move rather than indices, so adjacent jobs still
     * merge into one draw. QUADS only. Null when nothing was reordered.
     */
    @Nullable
    private static int[] sortQuads(MeshData mesh, Vector3fc sortOrigin, int quadsPerGroup) {
        var drawState = mesh.drawState();
        if (drawState.mode() != VertexFormat.Mode.QUADS || quadsPerGroup < 1) {
            return null;
        }
        var stride = drawState.format().getVertexSize();
        var groupVertices = 4 * quadsPerGroup;
        var groups = drawState.vertexCount() / groupVertices;
        if (groups < 2) {
            return null;
        }
        var vertices = mesh.vertexBuffer();
        var keys = PhotonDistanceSort.keys(groups);
        for (int group = 0; group < groups; group++) {
            var base = group * groupVertices * stride;
            float x = 0, y = 0, z = 0;
            for (int v = 0; v < groupVertices; v++) {
                var offset = base + v * stride;
                x += vertices.getFloat(offset);
                y += vertices.getFloat(offset + Float.BYTES);
                z += vertices.getFloat(offset + 2 * Float.BYTES);
            }
            float inverse = 1f / groupVertices;
            keys[group] = sortOrigin.distanceSquared(x * inverse, y * inverse, z * inverse);
        }
        var order = PhotonDistanceSort.farToNear(groups);
        var groupBytes = groupVertices * stride;
        var totalBytes = groups * groupBytes;
        // memAddress0 = the buffer's base, matching the absolute indexing used for the centroids above
        var source = MemoryUtil.memAddress0(vertices);
        var destination = MemoryUtil.memAddress0(quadScratch(totalBytes));
        for (int i = 0; i < groups; i++) {
            MemoryUtil.memCopy(source + (long) order[i] * groupBytes,
                    destination + (long) i * groupBytes, groupBytes);
        }
        MemoryUtil.memCopy(destination, source, totalBytes);
        return order;
    }

    /** Write this emitter's camera-relative vertices for one material batch (the default group). */
    protected abstract void bakeGeometry(VertexConsumer geometry, Camera camera, float partialTicks);
}
