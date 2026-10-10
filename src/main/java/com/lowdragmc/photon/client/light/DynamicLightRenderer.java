package com.lowdragmc.photon.client.light;

import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.render.PhotonFloatTexelBuffers;
import com.lowdragmc.photon.client.render.PhotonFullscreenPass;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PhotonRenderOutput;
import com.lowdragmc.photon.client.render.PhotonSceneCapture;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Lights the opaque scene, then puts the haze of volumetric lights and fog volumes over the finished frame. The world
 * prepares at {@code FrameGraphSetupEvent}, lights at {@code AfterOpaqueFeatures} ({@code AfterLevel} under a pack)
 * and hazes at {@code AfterLevel}; an editor scene does all three in {@code PhotonParticleManager.afterRender}. Lists
 * are texel buffers ({@link LightBuffer}), scalars std140 blocks.
 */
public final class DynamicLightRenderer {
    public static final int MAX_LIGHTS = 1024;
    /** Ticks a scene may go unlit before its frame targets and voxel atlas are freed. */
    private static final int IDLE_TICKS = 200;
    /** Scattering per block a volumetric strength of 1 stands for: a light haze. */
    private static final float VOLUME_SCALE = 0.03f;
    /** Texels per side of a shadowed light's visibility map, and maps per atlas row. */
    private static final int VISIBILITY_SIZE = 128;
    private static final int VISIBILITY_PER_ROW = 8;
    private static final int VISIBILITY_ROWS = (PhotonConfig.MAX_SHADOWED_LIGHTS + VISIBILITY_PER_ROW - 1) / VISIBILITY_PER_ROW;
    /** Where the volume blur puts sky texels: far, so the haze over the sky blurs among itself. */
    private static final float SKY_DEPTH = 1e4f;
    /** Ticks since lit particles last drew during which every shadowed light keeps a visibility map. */
    private static final int LIT_PARTICLE_TICKS = 20;
    /** Scattering per block of a fog volume's density 1: a light mist. */
    private static final float FOG_SCALE = 0.1f;
    private static final int MAX_FOG = 32;
    /** std140 sizes of the three blocks — LOCKSTEP with the GLSL. */
    private static final int LIGHTS_BLOCK_SIZE = 112;
    private static final int VIEW_BLOCK_SIZE = 320;
    private static final int STEP_BLOCK_SIZE = 16;

    private static final FloatBuffer DATA = MemoryUtil.memAllocFloat(MAX_LIGHTS * 16);
    private static final FloatBuffer VOLUME_DATA = MemoryUtil.memAllocFloat(MAX_LIGHTS * 12);
    private static final FloatBuffer VISIBILITY_LIGHTS = MemoryUtil.memAllocFloat(PhotonConfig.MAX_SHADOWED_LIGHTS * 4);
    private static final FloatBuffer FOG_DATA = MemoryUtil.memAllocFloat(MAX_FOG * 8 * 4);
    private static final LightBuffer LIGHT_DATA = LightBuffer.texels("Photon light list");
    private static final LightBuffer VOLUME_LIGHT_DATA = LightBuffer.texels("Photon volumetric light list");
    private static final LightBuffer VISIBILITY_LIGHT_DATA = LightBuffer.texels("Photon visibility light list");
    private static final LightBuffer FOG_LIST = LightBuffer.texels("Photon fog volume list");
    private static final LightBuffer LIGHTS_BLOCK = LightBuffer.block("PhotonLights");
    private static final LightBuffer NO_LIGHTS_BLOCK = LightBuffer.block("PhotonLights (none)");
    private static final LightBuffer PREPARE_VIEW = LightBuffer.block("PhotonLightView (prepare)");
    private static final LightBuffer APPLY_VIEW = LightBuffer.block("PhotonLightView (apply)");
    private static final LightBuffer FALLBACK_FOG = LightBuffer.block("Photon light fog (none)");
    // one buffer per draw: rewriting a block between draws that read it would stall
    private static final LightBuffer STEP_NONE = LightBuffer.block("PhotonLightStep");
    private static final LightBuffer STEP_VISIBILITY = LightBuffer.block("PhotonLightStep (visibility)");
    private static final LightBuffer STEP_BLUR_X = LightBuffer.block("PhotonLightStep (blur x)");
    private static final LightBuffer STEP_BLUR_Y = LightBuffer.block("PhotonLightStep (blur y)");
    private static final LightBuffer STEP_VOLUME_BLUR_X = LightBuffer.block("PhotonLightStep (volume blur x)");
    private static final LightBuffer STEP_VOLUME_BLUR_Y = LightBuffer.block("PhotonLightStep (volume blur y)");
    private static final LightBuffer STEP_FOG = LightBuffer.block("PhotonLightStep (fog)");
    private static final LightBuffer STEP_HAZE_ADD = LightBuffer.block("PhotonLightStep (haze)");
    private static final LightBuffer STEP_HAZE_ABSORB = LightBuffer.block("PhotonLightStep (absorb)");
    private static final LightBuffer STEP_DEPTH_SCALE = LightBuffer.block("PhotonLightStep (depth scale)");

    private static final List<FogVolume> FRAME_FOG = new ArrayList<>();
    // the frame's fog volumes, nearest first
    private static final List<FogVolume> FOG = new ArrayList<>();
    private static final float[] VIEW = new float[MAX_LIGHTS * 4];
    // per light: view-space spot axis (zero for a point), cos outer, cos inner
    private static final float[] SPOT = new float[MAX_LIGHTS * 5];
    private static final int[] VOLUME_INDEX = new int[MAX_LIGHTS];
    private static final List<DynamicLight> FRAME_LIGHTS = new ArrayList<>();
    private static final List<DynamicLight> SHADOWED = new ArrayList<>();
    // the frame's volumetric lights, shadowed ones first, and where each sits in VIEW
    private static final List<DynamicLight> VOLUMETRIC = new ArrayList<>();
    private static final IntArrayList VOLUME_RANK = new IntArrayList();
    private static final IntArrayList UNSHADOWED_VOLUME_RANK = new IntArrayList();
    private static final IntArrayList SHADOWED_RANK = new IntArrayList();
    // the light, by rank, behind each visibility map: shadowed volumetric lights first, numbered like them
    private static final IntArrayList MAP_RANK = new IntArrayList();
    // scenes holding GPU memory, held strongly so one whose level vanished without an unload is still freed
    private static final Set<LightScene> ACTIVE = new HashSet<>();
    private static final Matrix4f INVERSE_PROJECTION = new Matrix4f();
    private static final Matrix4f INVERSE_VIEW = new Matrix4f();
    private static final Matrix4f TO_LOCAL = new Matrix4f();
    private static final Matrix3f AXES = new Matrix3f();
    private static final Vector3f TMP = new Vector3f();
    private static final Vector3d EXTENT = new Vector3d();
    private static DynamicLight[] candidates = new DynamicLight[64];
    private static float[] importance = new float[64];
    private static int[] order = new int[64];

    /** A frame's haze, waiting for the frame's particles before it goes on. */
    private record Haze(Frame frame, GpuTextureView depth) {
    }

    /**
     * What a frame lights: the world's, or an editor scene's. {@code width x height}: the target it fills (an editor
     * scene has its own PIP texture, so no viewport origin). {@code fog}: vanilla's, null when unfogged.
     */
    private record Frame(LightScene scene, Level level, Vec3 camera, Matrix4f view, Matrix4f projection,
                         @Nullable Frustum frustum, float partialTick, int width, int height, boolean world,
                         @Nullable FogData fog) {
    }

    @Nullable
    private static Frame prepared;
    @Nullable
    private static Haze pendingHaze;
    private static int lightCount;
    private static int shadowedCount;
    private static int volumeCount;
    private static int volumeShadowedCount;
    private static int visibilityMapCount;
    private static int fogCount;
    private static long litParticleTick = Long.MIN_VALUE / 2;
    private static double prepareMillis;
    private static long ticks;
    /** {@link #ensureBindings()} ran. */
    private static boolean bindable;
    @Nullable
    private static GpuBufferSlice noLights;
    @Nullable
    private static GpuBufferSlice stepNone;
    @Nullable
    private static GpuBufferSlice noFog;

    private DynamicLightRenderer() {
    }

    public static int lastLightCount() {
        return lightCount;
    }

    public static int lastShadowedCount() {
        return shadowedCount;
    }

    public static int lastVolumeCount() {
        return volumeCount;
    }

    /** Volumetric lights whose haze their shadows cut, out of {@link #lastVolumeCount()}. */
    public static int lastVolumeShadowedCount() {
        return volumeShadowedCount;
    }

    public static int lastFogCount() {
        return fogCount;
    }

    /** Shadowed lights that got a visibility map, for their haze, fog or lit particles. */
    public static int lastVisibilityMapCount() {
        return visibilityMapCount;
    }

    public static double prepareMillis() {
        return prepareMillis;
    }

    /** The pass on the GPU, smoothed; {@code -1} outside a dev environment. */
    public static double gpuMillis() {
        return LightPassTimer.smoothedMillis();
    }

    /** Every pass pipeline by name, for the variant checks that compile them on the real driver. */
    public static Map<String, RenderPipeline> passPipelines() {
        var passes = new LinkedHashMap<String, RenderPipeline>();
        passes.put("visibility", LightPipelines.VISIBILITY);
        passes.put("pass", LightPipelines.PASS);
        passes.put("blur", LightPipelines.BLUR);
        passes.put("volume", LightPipelines.VOLUME_PASS);
        passes.put("fog", LightPipelines.FOG);
        passes.put("composite", LightPipelines.COMPOSITE);
        passes.put("haze add", LightPipelines.HAZE_ADD);
        passes.put("haze absorb", LightPipelines.HAZE_ABSORB);
        passes.put("depth scale", LightPipelines.DEPTH_SCALE);
        return passes;
    }

    /** True while a frame with at least one light is being drawn. */
    public static boolean hasLights() {
        return prepared != null && lightCount > 0;
    }

    // ---- world hooks --------------------------------------------------------------------------------------------

    /** {@code FrameGraphSetupEvent}: the world's frame begins, before anything is drawn. */
    public static void prepareWorld(Vec3 camera, Matrix4fc view, Matrix4fc projection, @Nullable Frustum frustum,
                                    float partialTick, @Nullable FogData fog) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var main = mc.getMainRenderTarget();
        prepare(new Frame(DynamicLightManager.scene(mc.level), mc.level, camera, new Matrix4f(view),
                new Matrix4f(projection), frustum, partialTick, main.width, main.height, true, fog));
    }

    /** {@code AfterOpaqueFeatures}, after Photon's own opaque draws: the opaque scene is down, nothing translucent is. */
    public static void afterOpaque() {
        if (!IrisCompat.isUsingShaderPack()) apply(false);
    }

    /** {@code AfterTranslucentParticles}, still inside the level pass. */
    public static void afterParticles() {
        if (!IrisCompat.isUsingShaderPack()) renderMarkers();
    }

    /**
     * {@code AfterLevel}, before Photon's late FX composite. Under a shader pack this is where the pass runs: the
     * pack's passes are done and its final image is in the main target. It must come before the late composite, or
     * those particles would be lit as the wall behind them.
     */
    public static void onLevelRendered() {
        if (IrisCompat.isUsingShaderPack() && !IrisCompat.isShadowPass()) {
            apply(true);
            renderMarkers();
        }
    }

    /** {@code AfterLevel}, after Photon's late composite and post effects: the world's haze goes on top. */
    public static void onFrameComposited() {
        compositeHaze();
        prepared = null;
    }

    // ---- editor scenes ------------------------------------------------------------------------------------------

    /**
     * An editor scene, after its blocks and before its particles drain: {@code level}'s lights through the scene's
     * camera, for the target being drawn into. They stay bound for the scene's particles until {@link #endScene()}.
     */
    public static void beginScene(Level level, Matrix4fc view, Matrix4fc projection, Vec3 camera, float partialTick) {
        var output = PhotonRenderOutput.color();
        prepare(new Frame(DynamicLightManager.scene(level), level, camera, new Matrix4f(view), new Matrix4f(projection),
                null, partialTick, output.getWidth(0), output.getHeight(0), false, null));
    }

    /** The scene's {@link #afterOpaque()}: its opaque FX are down, nothing translucent is. */
    public static void lightScene() {
        apply(false);
    }

    public static void endScene() {
        compositeHaze();
        prepared = null;
    }

    /** Client tick: frees what scenes left unlit for a while are holding. */
    public static void tick() {
        ticks++;
        ACTIVE.removeIf(scene -> {
            if (ticks - scene.lastLitTick < IDLE_TICKS) return false;
            scene.release();
            return true;
        });
    }

    /** {@code level} is gone: free its scene now rather than after the idle timeout. */
    public static void release(Level level) {
        var scene = DynamicLightManager.removeScene(level);
        if (scene != null) {
            scene.release();
            ACTIVE.remove(scene);
        }
    }

    // ---- binding for lit shaders --------------------------------------------------------------------------------

    /**
     * For a pipeline marked {@link PhotonPipelines#isLit lit}: the frame's light list, its clusters, visibility
     * maps and their block — or an empty set, which the shaders read as "no lights". Must be bound every draw of a
     * lit pipeline, lights or not (26.1 fails a draw with a declared uniform left unbound), and the draw must run
     * in a {@link PhotonFloatTexelBuffers} scope.
     */
    public static void bindLights(RenderPass pass) {
        if (!bindable) return; // ensureBindings never ran: nothing lit can have been drawn with it
        var frame = prepared;
        boolean lit = frame != null && lightCount > 0;
        pass.setUniform(PhotonPipelines.LIGHT_DATA, LIGHT_DATA.current());
        pass.setUniform(PhotonPipelines.CLUSTER_GRID, LightClusters.clusterGrid());
        pass.setUniform(PhotonPipelines.CLUSTER_INDICES, LightClusters.clusterIndices());
        bindAtlas(pass, frame);
        pass.setUniform(PhotonPipelines.LIGHTS_BLOCK, lit ? LIGHTS_BLOCK.current().slice(0, LIGHTS_BLOCK_SIZE)
                : noLightsBlock());
        if (frame != null) {
            // lit particles shadow from every shadowed light's map: keep drawing them all while they're used
            litParticleTick = ticks;
        }
    }

    /** A preview's light set: none, whatever frame is being drawn around it. */
    public static void bindNoLights(RenderPass pass) {
        if (!bindable) return;
        pass.setUniform(PhotonPipelines.LIGHT_DATA, LIGHT_DATA.current());
        pass.setUniform(PhotonPipelines.CLUSTER_GRID, LightClusters.clusterGrid());
        pass.setUniform(PhotonPipelines.CLUSTER_INDICES, LightClusters.clusterIndices());
        pass.bindTexture(PhotonPipelines.VISIBILITY_ATLAS, VoxelWorld.emptyTexture(), PhotonSceneCapture.sampler());
        pass.setUniform(PhotonPipelines.LIGHTS_BLOCK, noLightsBlock());
    }

    /**
     * Creates everything {@link #bindLights} binds. Creating uploads, which is illegal inside a pass, so call before
     * opening one. Idempotent.
     */
    public static void ensureBindings() {
        if (bindable) return;
        LIGHT_DATA.current();
        LightClusters.clusterGrid();
        LightClusters.clusterIndices();
        LightClusters.volumeGrid();
        LightClusters.volumeIndices();
        noLightsBlock();
        VoxelWorld.emptyTexture();
        VoxelWorld.emptyPages();
        bindable = true;
    }

    private static void bindAtlas(RenderPass pass, @Nullable Frame frame) {
        var atlas = frame != null && visibilityMapCount > 0 ? frame.scene().visibilityAtlas.view() : null;
        pass.bindTexture(PhotonPipelines.VISIBILITY_ATLAS, atlas != null ? atlas : VoxelWorld.emptyTexture(),
                PhotonSceneCapture.sampler());
    }

    /** The block a frame without lights binds: no lights, and dimensions no shader divides by zero with. Constant,
     *  so uploaded once. */
    private static GpuBufferSlice noLightsBlock() {
        if (noLights == null) {
            noLights = NO_LIGHTS_BLOCK.uploadBlock(LIGHTS_BLOCK_SIZE, b -> b
                    .putIVec4(LightClusters.TILE, 1, 1, 1)
                    .putVec4(LightClusters.NEAR, LightClusters.SLICE_SCALE, 0f, 0f)
                    .putIVec4(VISIBILITY_SIZE, VISIBILITY_PER_ROW, 0, 0)
                    .putMat4f(new Matrix4f()));
        }
        return noLights;
    }

    /** The step block of a draw that takes no step parameters. Constant, so uploaded once. */
    private static GpuBufferSlice stepNone() {
        if (stepNone == null) stepNone = STEP_NONE.uploadBlock(STEP_BLOCK_SIZE, b -> b.putVec4(0f, 0f, 0f, 0f));
        return stepNone;
    }

    /** The fog block of a frame with no fog. Constant, so uploaded once. */
    private static GpuBufferSlice noFog() {
        if (noFog == null) noFog = FALLBACK_FOG.uploadBlock(FogRenderer.FOG_UBO_SIZE, b -> b.putVec4(0f, 0f, 0f, 0f));
        return noFog;
    }

    // ---- prepare ------------------------------------------------------------------------------------------------

    private static void prepare(Frame frame) {
        LightPassTimer.frame();
        prepared = null;
        pendingHaze = null;
        lightCount = 0;
        shadowedCount = 0;
        volumeCount = 0;
        volumeShadowedCount = 0;
        visibilityMapCount = 0;
        fogCount = 0;
        FOG.clear();
        VOLUMETRIC.clear();
        VOLUME_RANK.clear();
        UNSHADOWED_VOLUME_RANK.clear();
        var config = PhotonConfig.INSTANCE;
        var scene = frame.scene();
        if (!config.dynamicLights.get() || frame.width() <= 0 || frame.height() <= 0
                || DynamicLightManager.isEmpty(scene, frame.world())) return;
        long start = System.nanoTime();
        var lights = DynamicLightManager.collect(scene, frame.world(), frame.partialTick(), FRAME_LIGHTS);

        int n = 0;
        var camera = frame.camera();
        for (var light : lights) {
            var p = light.position;
            var c = light.color;
            // negated, so NaN fails too: one bad light would turn its whole cluster NaN
            if (!light.enabled || !(light.range > 0) || !(light.intensity > 0) || !Double.isFinite(p.x + p.y + p.z)
                    || !Float.isFinite(c.x + c.y + c.z + light.intensity + light.range)) continue;
            double r = light.range;
            if (frame.frustum() != null && !frame.frustum().isVisible(new AABB(p.x - r, p.y - r, p.z - r, p.x + r, p.y + r, p.z + r))) {
                continue;
            }
            if (n == candidates.length) {
                candidates = Arrays.copyOf(candidates, n * 2);
                importance = Arrays.copyOf(importance, n * 2);
                order = Arrays.copyOf(order, n * 2);
            }
            float luminance = 0.2126f * c.x + 0.7152f * c.y + 0.0722f * c.z;
            candidates[n] = light;
            importance[n] = (float) (luminance * light.intensity * r * r / Math.max(camera.distanceToSqr(p.x, p.y, p.z), 1.0));
            order[n] = n;
            n++;
        }
        boolean volumetric = volumetricEnabled(frame.world());
        if (volumetric) {
            collectFog(frame);
        } else {
            scene.volumeBuffer.release();
        }
        if (n == 0 && FOG.isEmpty()) {
            prepareMillis = (System.nanoTime() - start) / 1e6;
            return;
        }
        scene.lastLitTick = ticks;
        ACTIVE.add(scene);
        var rank = importance;
        IntArrays.quickSort(order, 0, n, (a, b) -> Float.compare(rank[b], rank[a]));
        int count = Math.min(n, MAX_LIGHTS);

        if (++scene.ambientAge > 20) {
            scene.ambient.clear();
            scene.ambientAge = 0;
        }
        var shadowMode = config.lightShadowMode.get();
        int shadowBudget = shadowMode == ShadowMode.OFF ? 0 : config.lightShadowedLights.get();
        SHADOWED.clear();
        SHADOWED_RANK.clear();
        DATA.clear();
        for (int k = 0; k < count; k++) {
            var light = candidates[order[k]];
            boolean shadowed = light.castShadows && SHADOWED.size() < shadowBudget;
            if (shadowed) {
                SHADOWED.add(light);
                SHADOWED_RANK.add(k);
            }
            pack(light, k, shadowed, frame);
            VOLUME_INDEX[k] = -1;
            if (volumetric && light.volumetric > 0) {
                // the haze is shadowed from the voxels only
                (shadowed && shadowMode == ShadowMode.VOXEL ? VOLUME_RANK : UNSHADOWED_VOLUME_RANK).add(k);
            }
        }
        // shadowed first: the volume pass tells them apart by index
        volumeShadowedCount = VOLUME_RANK.size();
        VOLUME_RANK.addAll(UNSHADOWED_VOLUME_RANK);
        for (int v = 0; v < VOLUME_RANK.size(); v++) {
            int k = VOLUME_RANK.getInt(v);
            VOLUME_INDEX[k] = v;
            VOLUMETRIC.add(candidates[order[k]]);
        }
        Arrays.fill(candidates, 0, n, null);

        MAP_RANK.clear();
        if (shadowMode == ShadowMode.VOXEL) {
            for (int v = 0; v < volumeShadowedCount; v++) MAP_RANK.add(VOLUME_RANK.getInt(v));
            for (int i = 0; i < SHADOWED_RANK.size(); i++) {
                int k = SHADOWED_RANK.getInt(i);
                if (VOLUME_INDEX[k] < 0) MAP_RANK.add(k);
            }
            // shadowed: the light's map + 1, whether or not the map is drawn this frame
            for (int map = 0; map < MAP_RANK.size(); map++) DATA.put(MAP_RANK.getInt(map) * 16 + 13, map + 1f);
        }
        LightClusters.build(VIEW, count, frame.projection(), frame.width(), frame.height());
        if (!VOLUMETRIC.isEmpty()) LightClusters.buildVolume(VOLUME_INDEX, count);
        LIGHT_DATA.upload(DATA.position(0).limit(count * 16));
        DATA.clear();
        INVERSE_VIEW.set(frame.view()).invert();
        if (shadowMode != ShadowMode.VOXEL) {
            scene.voxels.release();
            scene.visibilityAtlas.release();
        } else if (!SHADOWED.isEmpty()) {
            scene.voxels.update(frame.level(), camera, SHADOWED, (long) (config.lightVoxelBudgetMs.get() * 1e6));
            // before anything is drawn, so lit particles have them even when the pass runs after them
            boolean everyLight = ticks - litParticleTick < LIT_PARTICLE_TICKS || !FOG.isEmpty();
            buildVisibilityMaps(frame, everyLight ? MAP_RANK.size() : volumeShadowedCount);
        }
        lightCount = count;
        shadowedCount = SHADOWED.size();
        volumeCount = VOLUMETRIC.size();
        fogCount = FOG.size();
        LIGHTS_BLOCK.uploadBlock(LIGHTS_BLOCK_SIZE, b -> b
                .putIVec4(LightClusters.TILE, LightClusters.tilesX(), LightClusters.tilesY(), LightClusters.SLICES)
                .putVec4(LightClusters.NEAR, LightClusters.SLICE_SCALE, count > 0 ? 1f : 0f, 0f)
                .putIVec4(VISIBILITY_SIZE, VISIBILITY_PER_ROW, visibilityMapCount, 0)
                .putMat4f(INVERSE_VIEW));
        ensureBindings();
        prepared = frame;
        prepareMillis = (System.nanoTime() - start) / 1e6;
        LightPassTimer.cpu(prepareMillis);
    }

    /** The visible fog volumes worth drawing, nearest first, at most {@link #MAX_FOG}. */
    private static void collectFog(Frame frame) {
        var camera = frame.camera();
        for (var fog : DynamicLightManager.collectFog(frame.scene(), frame.world(), frame.partialTick(), FRAME_FOG)) {
            var p = fog.position;
            // negated, so NaN fails too
            if (!fog.enabled || !(fog.density > 0) || !(Math.abs(fog.axes.determinant()) >= 1e-6f)
                    || !Double.isFinite(p.x + p.y + p.z)) continue;
            var e = extent(fog);
            if (frame.frustum() != null && !frame.frustum().isVisible(new AABB(p.x - e.x, p.y - e.y, p.z - e.z, p.x + e.x, p.y + e.y, p.z + e.z))) {
                continue;
            }
            FOG.add(fog);
        }
        // by the distance to the box, so a large volume the camera stands in is never the one dropped
        FOG.sort(Comparator.comparingDouble(fog -> outside(fog, camera).lengthSquared()));
        while (FOG.size() > MAX_FOG) FOG.removeLast();
    }

    /** Half the size of the box around a fog volume's shape: half the absolute axes, or the longest for a sphere. */
    private static Vector3d extent(FogVolume fog) {
        var a = fog.axes;
        if (fog.shape == FogVolume.Shape.SPHERE) {
            return EXTENT.set(0.5 * Math.max(a.getColumn(0, TMP).length(), Math.max(a.getColumn(1, TMP).length(), a.getColumn(2, TMP).length())));
        }
        return EXTENT.set(0.5 * (Math.abs(a.m00) + Math.abs(a.m10) + Math.abs(a.m20)),
                0.5 * (Math.abs(a.m01) + Math.abs(a.m11) + Math.abs(a.m21)),
                0.5 * (Math.abs(a.m02) + Math.abs(a.m12) + Math.abs(a.m22)));
    }

    /** How far {@code point} lies outside a fog volume's box on each axis, 0 inside. */
    private static Vector3d outside(FogVolume fog, Vec3 point) {
        var e = extent(fog);
        var p = fog.position;
        return e.set(Math.max(Math.abs(point.x - p.x) - e.x, 0), Math.max(Math.abs(point.y - p.y) - e.y, 0),
                Math.max(Math.abs(point.z - p.z) - e.z, 0));
    }

    /**
     * How much of what lies this far from the camera vanilla's fog leaves visible — 26.1's fog model: a spherical
     * environmental ramp against a cylindrical render-distance one, whichever is denser (MIRRORS
     * {@code minecraft:fog.glsl}'s {@code total_fog_value}).
     */
    private static float vanillaFogLeft(FogData fog, double dx, double dy, double dz) {
        double spherical = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double cylindrical = Math.max(Math.sqrt(dx * dx + dz * dz), Math.abs(dy));
        float value = Math.max(linearFog(spherical, fog.environmentalStart, fog.environmentalEnd),
                linearFog(cylindrical, fog.renderDistanceStart, fog.renderDistanceEnd));
        return 1f - fog.color.w * value;
    }

    private static float linearFog(double distance, float start, float end) {
        if (distance <= start) return 0f;
        if (distance >= end) return 1f;
        return (float) ((distance - start) / (end - start));
    }

    /** Whether halos, beams and fog volumes are drawn: in the world's frame, or an editor scene's. */
    static boolean volumetricEnabled(boolean world) {
        var config = PhotonConfig.INSTANCE;
        return config.volumetricLights.get() && config.volumetricDensity.get() > 0
                && (!world || !IrisCompat.isUsingShaderPack() || config.volumetricWithShaderPacks.get());
    }

    /** Four texels per light; the layout is documented in {@code dynamic_light.glsl}. */
    private static void pack(DynamicLight light, int slot, boolean shadowed, Frame frame) {
        var camera = frame.camera();
        var p = light.position;
        frame.view().transformPosition((float) (p.x - camera.x), (float) (p.y - camera.y), (float) (p.z - camera.z), TMP);
        VIEW[slot * 4] = TMP.x;
        VIEW[slot * 4 + 1] = TMP.y;
        VIEW[slot * 4 + 2] = TMP.z;
        VIEW[slot * 4 + 3] = light.range;
        DATA.put(TMP.x).put(TMP.y).put(TMP.z).put(light.range);
        frame.view().transformDirection(light.direction, TMP);
        float length = TMP.length();
        // a spot with no direction (zero scale, say) would be NaN all the way down: light it as a point
        boolean spot = light.type == DynamicLight.Type.SPOT && length > 1e-6f;
        if (spot) TMP.div(length); else TMP.zero();
        DATA.put(light.color.x * light.intensity).put(light.color.y * light.intensity)
                .put(light.color.z * light.intensity).put(spot ? 1f : 0f);
        // written so NaN angles land on the bounds
        float outer = light.outerAngle >= 0.1f ? Math.min(light.outerAngle, 179f) : 0.1f;
        float inner = light.innerAngle > 0f ? Math.min(light.innerAngle, outer - 0.05f) : 0f;
        float cosOuter = (float) Math.cos(Math.toRadians(outer));
        float cosInner = (float) Math.cos(Math.toRadians(inner));
        SPOT[slot * 5] = TMP.x;
        SPOT[slot * 5 + 1] = TMP.y;
        SPOT[slot * 5 + 2] = TMP.z;
        SPOT[slot * 5 + 3] = cosOuter;
        SPOT[slot * 5 + 4] = spot ? cosInner : -2f;
        DATA.put(TMP.x).put(TMP.y).put(TMP.z).put(cosOuter);
        DATA.put(cosInner).put(shadowed ? 1f : 0f)
                .put(packedLight(frame, p.x, p.y, p.z)).put(light.sourceRadius > 0f ? light.sourceRadius : 0f);
    }

    /**
     * Vanilla's light level where the light sits, {@code block + 16 * sky}: roughly what already lit the surfaces it
     * is about to light. The pass reads its colour out of the lightmap — 26.1 renders that on the GPU and keeps no
     * copy to read here. Below 0 under a shader pack, where the composite assumes the pack's ambient instead.
     */
    private static float packedLight(Frame frame, double x, double y, double z) {
        if (frame.world() && IrisCompat.isUsingShaderPack()) return -1f;
        var cache = frame.scene().ambient;
        long key = BlockPos.asLong(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        float cached = cache.getOrDefault(key, -1f);
        if (cached >= 0) return cached;
        int packed = LevelRenderer.getLightCoords(frame.level(), BlockPos.of(key));
        float value = Mth.clamp(LightCoordsUtil.block(packed), 0, 15) + 16f * Mth.clamp(LightCoordsUtil.sky(packed), 0, 15);
        cache.put(key, value);
        return value;
    }

    /** For each of the first {@code maps} shadowed lights, how far its light gets in every direction. */
    private static void buildVisibilityMaps(Frame frame, int maps) {
        maps = Math.min(maps, PhotonConfig.MAX_SHADOWED_LIGHTS);
        if (maps <= 0) return;
        var scene = frame.scene();
        var voxels = scene.voxels;
        // the whole atlas once, drawn only as far as this frame's maps: their count changes often
        var atlas = scene.visibilityAtlas.ensure(VISIBILITY_SIZE * VISIBILITY_PER_ROW, VISIBILITY_SIZE * VISIBILITY_ROWS);
        if (atlas == null) return;
        LightPassTimer.start();
        VISIBILITY_LIGHTS.clear();
        for (int map = 0; map < maps; map++) {
            int k = MAP_RANK.getInt(map);
            VISIBILITY_LIGHTS.put(VIEW[k * 4]).put(VIEW[k * 4 + 1]).put(VIEW[k * 4 + 2]).put(VIEW[k * 4 + 3]);
        }
        var lights = VISIBILITY_LIGHT_DATA.upload(VISIBILITY_LIGHTS.flip());
        VISIBILITY_LIGHTS.clear();
        var view = writeView(PREPARE_VIEW, frame, 1f, false);
        int drawn = maps;
        var step = STEP_VISIBILITY.uploadBlock(STEP_BLOCK_SIZE,
                b -> b.putVec4(VISIBILITY_SIZE, VISIBILITY_PER_ROW, drawn, 0f));
        int rows = (maps + VISIBILITY_PER_ROW - 1) / VISIBILITY_PER_ROW;
        // resolved before the pass opens: a first use uploads
        var pages = voxels.pages();
        var bricks = voxels.bricks();
        draw("Photon light visibility", LightPipelines.VISIBILITY, atlas, null, view, step, pass -> {
            // only as far as this frame's maps; the shader addresses the atlas absolutely
            pass.enableScissor(0, 0, atlas.getWidth(0), VISIBILITY_SIZE * rows);
            pass.setUniform(LightPipelines.VOXEL_PAGES, pages);
            pass.bindTexture(LightPipelines.VOXEL_BRICKS, bricks, PhotonSceneCapture.sampler());
            pass.setUniform(LightPipelines.VISIBILITY_LIGHTS, lights);
        });
        LightPassTimer.mark(LightPassTimer.Stage.VISIBILITY);
        LightPassTimer.stop();
        visibilityMapCount = maps;
    }

    // ---- apply --------------------------------------------------------------------------------------------------

    private static void apply(boolean afterPack) {
        var frame = prepared;
        if (frame == null || lightCount == 0 && fogCount == 0) {
            return;
        }
        long start = System.nanoTime();
        var config = PhotonConfig.INSTANCE;
        var scene = frame.scene();
        boolean soft = config.lightSoftShadows.get();
        var output = PhotonRenderOutput.color();
        var outputDepth = PhotonRenderOutput.depth();
        int width = output.getWidth(0), height = output.getHeight(0);
        if (outputDepth == null || width != frame.width() || height != frame.height()) {
            return; // not the target this frame's clusters were built for
        }
        float scale = config.lightResolution.get().floatValue();
        int lightWidth = Math.max(1, Math.round(width * scale));
        int lightHeight = Math.max(1, Math.round(height * scale));

        LightPassTimer.start();
        var view = writeView(APPLY_VIEW, frame, scale, afterPack);
        var none = stepNone();
        var snapshot = scene.snapshotColor.copyOf(output);
        var depth = snapshotDepth(scene, outputDepth, afterPack ? IrisCompat.gbufferRenderScale() : 1f, view);
        var lightBuffer = scene.lightBuffer.ensure(lightWidth, lightHeight);
        if (snapshot == null || depth == null || lightBuffer == null) {
            LightPassTimer.stop();
            return;
        }
        LightPassTimer.mark(LightPassTimer.Stage.COPY);

        ensureBindings();
        // resolved before any pass opens: a first use uploads
        var voxelPages = scene.voxels.pages();
        var voxelBricks = scene.voxels.bricks();
        var lightmap = Minecraft.getInstance().gameRenderer.lightmap();
        var nearest = PhotonSceneCapture.sampler();
        var lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

        // fog alone lights no surface: no light pass, no composite
        boolean surfaces = lightCount > 0;
        if (surfaces) {
            draw("Photon light pass", LightPipelines.PASS, lightBuffer, null, view, none, pass -> {
                pass.bindTexture(LightPipelines.SCENE_DEPTH, depth, nearest);
                pass.bindTexture(LightPipelines.LIGHTMAP, lightmap, lightmapSampler);
                bindLightList(pass);
                pass.setUniform(LightPipelines.VOXEL_PAGES, voxelPages);
                pass.bindTexture(LightPipelines.VOXEL_BRICKS, voxelBricks, nearest);
            });
        }
        LightPassTimer.mark(LightPassTimer.Stage.LIGHT);

        if (surfaces && soft && shadowedCount > 0) {
            var blurBuffer = scene.blurBuffer.ensure(lightWidth, lightHeight);
            if (blurBuffer != null) {
                blur(STEP_BLUR_X, view, depth, lightBuffer, blurBuffer, 1f, 0f, -1f);
                blur(STEP_BLUR_Y, view, depth, blurBuffer, lightBuffer, 0f, 1f, -1f);
            }
        }
        LightPassTimer.mark(LightPassTimer.Stage.BLUR);

        var volumeBuffer = volumeCount > 0 || fogCount > 0 ? scene.volumeBuffer.ensure(lightWidth, lightHeight) : null;
        boolean absorbing = false;
        if (volumeBuffer != null) {
            int shadowedVolumes = Math.min(volumeShadowedCount, visibilityMapCount);
            if (volumeCount > 0) {
                var volumeLights = uploadVolumes(frame, afterPack);
                var volumeGrid = LightClusters.volumeGrid();
                var volumeIndices = LightClusters.volumeIndices();
                draw("Photon light volume", LightPipelines.VOLUME_PASS, volumeBuffer, null, view, none, pass -> {
                    pass.setUniform(PhotonPipelines.LIGHTS_BLOCK, lightsBlock());
                    pass.bindTexture(LightPipelines.SCENE_DEPTH, depth, nearest);
                    pass.setUniform(LightPipelines.VOLUME_LIGHT_DATA, volumeLights);
                    pass.setUniform(LightPipelines.VOLUME_TILE_GRID, volumeGrid);
                    pass.setUniform(LightPipelines.VOLUME_TILE_INDICES, volumeIndices);
                    bindAtlas(pass, frame);
                });
            } else {
                // no haze yet, and a scene that still shows through completely
                scene.volumeBuffer.clear(0xFF000000);
            }
            LightPassTimer.mark(LightPassTimer.Stage.VOLUME);
            if (fogCount > 0) {
                absorbing = uploadFog(frame, afterPack);
                var fogList = FOG_LIST.current();
                int fogs = fogCount;
                int steps = config.volumetricSamples.get() * 4;
                var step = STEP_FOG.uploadBlock(STEP_BLOCK_SIZE, b -> b.putVec4(fogs, steps, 0f, 0f));
                draw("Photon light fog", LightPipelines.FOG, volumeBuffer, null, view, step, pass -> {
                    bindLightList(pass);
                    bindAtlas(pass, frame);
                    pass.bindTexture(LightPipelines.SCENE_DEPTH, depth, nearest);
                    pass.setUniform(LightPipelines.FOG_DATA, fogList);
                });
            }
            LightPassTimer.mark(LightPassTimer.Stage.FOG);
            if (shadowedVolumes > 0 || fogCount > 0) {
                // the shadowed haze and the fog are sampled at a different offset per pixel of a 4x4 tile
                var blurBuffer = scene.blurBuffer.ensure(lightWidth, lightHeight);
                if (blurBuffer != null) {
                    blur(STEP_VOLUME_BLUR_X, view, depth, volumeBuffer, blurBuffer, 1f, 0f, SKY_DEPTH);
                    blur(STEP_VOLUME_BLUR_Y, view, depth, blurBuffer, volumeBuffer, 0f, 1f, SKY_DEPTH);
                }
            }
            LightPassTimer.mark(LightPassTimer.Stage.VOLUME_BLUR);
        }

        // the volume debug view shows black under the haze without reading the light buffer
        if (surfaces || LightDebug.view == 6) {
            var fog = RenderSystem.getShaderFog();
            var fogBlock = fog != null ? fog : noFog();
            draw("Photon light composite", LightPipelines.COMPOSITE, output, null, view, none, pass -> {
                applyScissor(pass);
                pass.setUniform("Fog", fogBlock);
                pass.bindTexture(LightPipelines.SCENE_COLOR, snapshot, nearest);
                pass.bindTexture(LightPipelines.SCENE_DEPTH, depth, nearest);
                pass.bindTexture(LightPipelines.IRRADIANCE, lightBuffer, nearest);
                pass.bindTexture(LightPipelines.LIGHTMAP, lightmap, lightmapSampler);
            });
        }
        LightPassTimer.mark(LightPassTimer.Stage.COMPOSITE);
        // the other debug views show the surfaces alone
        if (volumeBuffer != null && (LightDebug.view == 0 || LightDebug.view == 6)) {
            var haze = new Haze(frame, depth);
            // what the fog hides goes dark now, before the particles: a flame in front of a fog bank stays bright
            if (absorbing) {
                drawHaze(haze, true);
                LightPassTimer.mark(LightPassTimer.Stage.HAZE);
            }
            pendingHaze = haze;
        }
        LightPassTimer.stop();
        LightPassTimer.cpu((System.nanoTime() - start) / 1e6);
    }

    /** The frame's depth, or — over a pack that draws its world into the lower-left {@code renderScale} of the
     *  screen and upscales only the colour — a full-screen depth rebuilt from that corner. */
    @Nullable
    private static GpuTextureView snapshotDepth(LightScene scene, GpuTextureView source, float renderScale,
                                                GpuBufferSlice view) {
        // a copy rather than the frame's own depth: that one need not be sampleable
        var depth = scene.snapshotDepth.copyOf(source);
        if (renderScale >= 1f || depth == null) {
            scene.scaledDepth.release();
            return depth;
        }
        // rebuilt into a second target of the same format, which the pass overwrites whole
        var scaled = scene.scaledDepth.ensureLike(source);
        var color = scene.snapshotColor.view();
        if (scaled == null || color == null) return depth;
        var step = STEP_DEPTH_SCALE.uploadBlock(STEP_BLOCK_SIZE, b -> b.putVec4(renderScale, 0f, 0f, 0f));
        draw("Photon light depth scale", LightPipelines.DEPTH_SCALE, color, scaled, view, step,
                pass -> pass.bindTexture(LightPipelines.DEPTH, depth, PhotonSceneCapture.sampler()));
        return scaled;
    }

    private static void blur(LightBuffer stepBuffer, GpuBufferSlice view, GpuTextureView depth, GpuTextureView source,
                             GpuTextureView target, float dx, float dy, float skyDepth) {
        var step = stepBuffer.uploadBlock(STEP_BLOCK_SIZE, b -> b.putVec4(dx, dy, skyDepth, 0f));
        draw("Photon light blur", LightPipelines.BLUR, target, null, view, step, pass -> {
            pass.bindTexture(LightPipelines.IRRADIANCE, source, PhotonSceneCapture.sampler());
            pass.bindTexture(LightPipelines.SCENE_DEPTH, depth, PhotonSceneCapture.sampler());
        });
    }

    private static void bindLightList(RenderPass pass) {
        pass.setUniform(PhotonPipelines.LIGHT_DATA, LIGHT_DATA.current());
        pass.setUniform(PhotonPipelines.CLUSTER_GRID, LightClusters.clusterGrid());
        pass.setUniform(PhotonPipelines.CLUSTER_INDICES, LightClusters.clusterIndices());
        pass.setUniform(PhotonPipelines.LIGHTS_BLOCK, lightsBlock());
    }

    private static GpuBufferSlice lightsBlock() {
        return lightCount > 0 ? LIGHTS_BLOCK.current().slice(0, LIGHTS_BLOCK_SIZE) : noLightsBlock();
    }

    /** A fullscreen light draw: the frame's view block and this draw's step block bound, float lists readable. */
    private static void draw(String label, RenderPipeline pipeline, GpuTextureView target,
                             @Nullable GpuTextureView depth, GpuBufferSlice view, GpuBufferSlice step,
                             Consumer<RenderPass> bind) {
        PhotonFloatTexelBuffers.run(() -> PhotonFullscreenPass.draw(label, pipeline, target, depth, pass -> {
            pass.setUniform(LightPipelines.VIEW_BLOCK, view);
            pass.setUniform(LightPipelines.STEP_BLOCK, step);
            bind.accept(pass);
        }));
    }

    /** Clipped like Photon's own draws into the same target (the editor's scene panel). */
    private static void applyScissor(RenderPass pass) {
        var scissor = RenderSystem.getScissorStateForRenderTypeDraws();
        if (scissor.enabled()) {
            pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
        }
    }

    /** The {@code PhotonLightView} block of {@code frame}; LOCKSTEP with {@code dynamic_light_frame.glsl}. */
    private static GpuBufferSlice writeView(LightBuffer buffer, Frame frame, float scale, boolean afterPack) {
        var config = PhotonConfig.INSTANCE;
        INVERSE_PROJECTION.set(frame.projection()).invert();
        INVERSE_VIEW.set(frame.view()).invert();
        var camera = frame.camera();
        int blockX = Mth.floor(camera.x), blockY = Mth.floor(camera.y), blockZ = Mth.floor(camera.z);
        var voxels = frame.scene().voxels;
        var shade = frame.level() instanceof BlockAndTintGetter tint ? tint.cardinalLighting() : CardinalLighting.DEFAULT;
        // only the world's own frame is fogged by vanilla's numbers: a pack fogs its own way, an editor scene not at all
        boolean fogged = frame.world() && !afterPack;
        return buffer.uploadBlock(VIEW_BLOCK_SIZE, b -> b
                .putMat4f(INVERSE_PROJECTION)
                .putMat4f(INVERSE_VIEW)
                .putMat4f(frame.projection())
                .putVec4(frame.width(), frame.height(), scale, LightDebug.view)
                .putVec4(afterPack ? 1f : 0f, config.lightShaderPackAmbient.get().floatValue(), fogged ? 1f : 0f, 0f)
                .putVec4(config.lightShadowMode.get().ordinal(), config.lightScreenShadowSteps.get(),
                        config.lightScreenShadowThickness.get().floatValue(), config.lightScreenShadowDistance.get().floatValue())
                .putVec4(config.lightContactShadowSteps.get(), config.lightContactShadowLength.get().floatValue(),
                        config.lightSoftShadows.get() ? 1f : 0f, 0f)
                .putVec4((float) (camera.x - blockX), (float) (camera.y - blockY), (float) (camera.z - blockZ), 0f)
                .putIVec4(voxels.originBlockX() - blockX, voxels.originBlockY() - blockY, voxels.originBlockZ() - blockZ, 0)
                .putVec4(shade.down(), shade.up(), shade.north(), shade.east())
                .putVec4(config.lightAlbedoMax.get().floatValue(), config.volumetricForwardScattering.get().floatValue(),
                        config.volumetricSamples.get(), Math.min(volumeShadowedCount, visibilityMapCount)));
    }

    /**
     * Three texels per volumetric light, laid out in {@code dynamic_light_volume.fsh}. Packed at apply time: vanilla
     * fog fades a halo by the light's distance, as it fades the surfaces there.
     */
    private static GpuBuffer uploadVolumes(Frame frame, boolean afterPack) {
        float density = (float) (VOLUME_SCALE * PhotonConfig.INSTANCE.volumetricDensity.get());
        var fog = frame.world() && !afterPack ? frame.fog() : null;
        var camera = frame.camera();
        VOLUME_DATA.clear();
        for (int v = 0; v < VOLUMETRIC.size(); v++) {
            var light = VOLUMETRIC.get(v);
            int k = VOLUME_RANK.getInt(v);
            var p = light.position;
            float scale = light.volumetric * light.intensity * density;
            if (fog != null) scale *= vanillaFogLeft(fog, p.x - camera.x, p.y - camera.y, p.z - camera.z);
            VOLUME_DATA.put(VIEW[k * 4]).put(VIEW[k * 4 + 1]).put(VIEW[k * 4 + 2]).put(VIEW[k * 4 + 3]);
            VOLUME_DATA.put(light.color.x * scale).put(light.color.y * scale).put(light.color.z * scale).put(SPOT[k * 5 + 4]);
            VOLUME_DATA.put(SPOT[k * 5]).put(SPOT[k * 5 + 1]).put(SPOT[k * 5 + 2]).put(SPOT[k * 5 + 3]);
        }
        var buffer = VOLUME_LIGHT_DATA.upload(VOLUME_DATA.flip());
        VOLUME_DATA.clear();
        return buffer;
    }

    /**
     * Eight texels per fog volume into {@link #FOG_LIST}, laid out in {@code dynamic_light_fog.fsh}. Packed at apply
     * time like the volumetric lights: vanilla fog thins a volume by its distance. Whether a volume hides what is
     * behind it.
     */
    private static boolean uploadFog(Frame frame, boolean afterPack) {
        float density = (float) (FOG_SCALE * PhotonConfig.INSTANCE.volumetricDensity.get());
        var vanilla = frame.world() && !afterPack ? frame.fog() : null;
        var camera = frame.camera();
        boolean absorbs = false;
        FOG_DATA.clear();
        for (var fog : FOG) {
            // view space -> the camera-relative world -> the volume's unit shape
            TO_LOCAL.set(AXES.set(fog.axes).invert())
                    .translate((float) (camera.x - fog.position.x), (float) (camera.y - fog.position.y), (float) (camera.z - fog.position.z))
                    .mul(INVERSE_VIEW);
            for (int row = 0; row < 3; row++) {
                FOG_DATA.put(TO_LOCAL.get(0, row)).put(TO_LOCAL.get(1, row)).put(TO_LOCAL.get(2, row)).put(TO_LOCAL.get(3, row));
            }
            float absorption = Mth.clamp(fog.absorption, 0f, 1f);
            absorbs |= absorption > 0;
            float left = 1f;
            if (vanilla != null) {
                var out = outside(fog, camera);
                left = vanillaFogLeft(vanilla, out.x, out.y, out.z);
            }
            FOG_DATA.put(fog.shape == FogVolume.Shape.SPHERE ? 1f : 0f).put(fog.density * density * left)
                    .put(Mth.clamp(fog.edgeFalloff, 0f, 1f)).put(absorption);
            FOG_DATA.put(Math.max(fog.color.x, 0f)).put(Math.max(fog.color.y, 0f)).put(Math.max(fog.color.z, 0f))
                    .put(Mth.clamp(fog.noise, 0f, 1f));
            FOG_DATA.put(Math.max(fog.emission.x, 0f)).put(Math.max(fog.emission.y, 0f)).put(Math.max(fog.emission.z, 0f))
                    .put(fog.noiseScale >= 0.05f ? fog.noiseScale : 0.05f);
            // the drift along each of the volume's own axes, where the shader samples the noise
            var a = fog.axes;
            float length0 = a.getColumn(0, TMP).length();
            float drift0 = TMP.dot(fog.noiseOffset) / length0;
            float length1 = a.getColumn(1, TMP).length();
            float drift1 = TMP.dot(fog.noiseOffset) / length1;
            float length2 = a.getColumn(2, TMP).length();
            float drift2 = TMP.dot(fog.noiseOffset) / length2;
            FOG_DATA.put(drift0).put(drift1).put(drift2).put(0f);
            FOG_DATA.put(length0).put(length1).put(length2).put(0f);
        }
        FOG_LIST.upload(FOG_DATA.flip());
        FOG_DATA.clear();
        return absorbs;
    }

    private static void compositeHaze() {
        var haze = pendingHaze;
        pendingHaze = null;
        if (haze == null) return;
        LightPassTimer.start();
        drawHaze(haze, false);
        LightPassTimer.mark(LightPassTimer.Stage.HAZE);
        LightPassTimer.stop();
    }

    /**
     * The volume buffer over the frame's target. The add is a soft add without reading the frame back,
     * {@code src + dst * (1 - src)}; the absorb multiplies the frame by the fog's transmittance.
     */
    private static void drawHaze(Haze haze, boolean absorb) {
        var scene = haze.frame().scene();
        var depth = haze.depth();
        var volume = scene.volumeBuffer.view();
        var output = PhotonRenderOutput.color();
        if (depth == null || volume == null || output.getWidth(0) != haze.frame().width()
                || output.getHeight(0) != haze.frame().height()) return;
        var view = APPLY_VIEW.current().slice(0, VIEW_BLOCK_SIZE);
        var step = (absorb ? STEP_HAZE_ABSORB : STEP_HAZE_ADD).uploadBlock(STEP_BLOCK_SIZE,
                b -> b.putVec4(absorb ? 1f : 0f, 0f, 0f, 0f));
        draw("Photon light haze", absorb ? LightPipelines.HAZE_ABSORB : LightPipelines.HAZE_ADD, output, null, view,
                step, pass -> {
                    applyScissor(pass);
                    pass.bindTexture(LightPipelines.SCENE_DEPTH, depth, PhotonSceneCapture.sampler());
                    pass.bindTexture(LightPipelines.VOLUME, volume, PhotonSceneCapture.sampler());
                });
    }

    // ---- dev markers ---------------------------------------------------------------------------------------------

    /** A small unlit cube at every light of the world's frame, for {@code /photonlight markers}. */
    private static void renderMarkers() {
        var frame = prepared;
        if (frame == null || !frame.world() || !LightDebug.markers || FRAME_LIGHTS.isEmpty()) return;
        var camera = frame.camera();
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        var consumer = buffers.getBuffer(RenderTypes.debugQuads());
        var matrix = new Matrix4f(frame.view());
        for (var light : FRAME_LIGHTS) {
            if (!light.enabled) continue;
            var c = light.color;
            float peak = Math.max(1e-3f, Math.max(c.x, Math.max(c.y, c.z)));
            cube(consumer, matrix, (float) (light.position.x - camera.x), (float) (light.position.y - camera.y),
                    (float) (light.position.z - camera.z), 0.08f, c.x / peak, c.y / peak, c.z / peak);
        }
        // the view is in the vertices: inside the level pass RenderSystem's model-view holds it too
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix().identity();
        try {
            buffers.endBatch(RenderTypes.debugQuads());
        } finally {
            modelView.popMatrix();
        }
    }

    private static void cube(VertexConsumer consumer, Matrix4f pose, float x, float y, float z, float s,
                             float r, float g, float b) {
        float x0 = x - s, x1 = x + s, y0 = y - s, y1 = y + s, z0 = z - s, z1 = z + s;
        float[][] faces = {
                {x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1},
                {x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0},
                {x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0},
                {x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1},
                {x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0},
                {x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1}};
        for (var f : faces) {
            for (int i = 0; i < 12; i += 3) {
                consumer.addVertex(pose, f[i], f[i + 1], f[i + 2]).setColor(r, g, b, 1f);
            }
        }
    }
}
