package com.lowdragmc.photon.client.light;

import com.lowdragmc.lowdraglib2.gui.ui.rendering.UISurface;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.PhotonShaders;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.postfx.graph.TargetFormat;
import com.lowdragmc.photon.client.postfx.runtime.FormatTarget;
import com.lowdragmc.photon.client.postfx.runtime.SceneBlit;
import com.lowdragmc.photon.core.mixins.accessor.LightTextureAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lights the opaque scene, and the haze of volumetric lights and fog volumes in front of it. A frame's lights are culled, ranked
 * and uploaded once, readable by shaders until the frame ends; then the pass lights the colour and depth already
 * drawn. The world runs it at {@code AFTER_BLOCK_ENTITIES} (after the pack's final image under a shader pack), an
 * editor scene before its particles and into its own viewport. The haze goes on last, over the particles.
 */
@OnlyIn(Dist.CLIENT)
public final class DynamicLightRenderer {
    public static final int MAX_LIGHTS = 1024;
    private static final int LIGHTS_PER_ROW = 256;
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
    private static final FloatBuffer VISIBILITY_LIGHTS = MemoryUtil.memAllocFloat(PhotonConfig.MAX_SHADOWED_LIGHTS * 4);
    /** Scattering per block of a fog volume's density 1: a light mist. */
    private static final float FOG_SCALE = 0.1f;
    private static final int MAX_FOG = 32;
    private static final FloatBuffer FOG_DATA = MemoryUtil.memAllocFloat(MAX_FOG * 8 * 4);
    private static final List<FogVolume> FRAME_FOG = new ArrayList<>();
    // the frame's fog volumes, nearest first
    private static final List<FogVolume> FOG = new ArrayList<>();
    private static final FloatBuffer DATA = MemoryUtil.memAllocFloat(MAX_LIGHTS * 16);
    private static final FloatBuffer VOLUME_DATA = MemoryUtil.memAllocFloat(MAX_LIGHTS * 12);
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
    private record Haze(Frame frame, Matrix4f inverseProjection, float scale, boolean afterPack) {
    }

    /** What a frame lights and where: {@code x, y, width, height} is the viewport inside {@code target}. */
    private record Frame(LightScene scene, Level level, Vec3 camera, Matrix4f view, Matrix4f projection,
                         @Nullable Frustum frustum, float partialTick, RenderTarget target,
                         int x, int y, int width, int height, boolean world) {
    }

    private static int dataTexture = -1;
    private static int volumeTexture = -1;
    private static int visibilityLightTexture = -1;
    private static int fogTexture = -1;
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

    /** True while a frame with at least one light is being drawn. */
    public static boolean hasLights() {
        return prepared != null && lightCount > 0;
    }

    public static void onStage(RenderLevelStageEvent event) {
        var stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            var mc = Minecraft.getInstance();
            if (mc.level == null) return;
            var main = mc.getMainRenderTarget();
            prepare(new Frame(DynamicLightManager.scene(mc.level), mc.level, event.getCamera().getPosition(),
                    new Matrix4f(event.getModelViewMatrix()), new Matrix4f(event.getProjectionMatrix()), event.getFrustum(),
                    event.getPartialTick().getGameTimeDeltaPartialTick(false), main, 0, 0, main.width, main.height, true));
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            if (!IrisCompat.isUsingShaderPack()) apply(false);
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            if (!IrisCompat.isUsingShaderPack()) renderMarkers(event.getPoseStack().last().pose());
        }
    }

    /**
     * Right after the level render. Under a shader pack this is where the pass runs: the pack's passes are
     * done and Iris no longer redirects draws. It must come before Photon's late FX composite, or particles
     * would be lit as the wall behind them.
     */
    public static void onLevelRendered() {
        if (IrisCompat.isUsingShaderPack() && !IrisCompat.isShadowPass()) {
            apply(true);
            renderMarkers(null);
        }
        prepared = null;
    }

    /**
     * An editor scene, after its blocks and before its particles: lights {@code level} through the scene's
     * camera into the viewport set on the target being drawn into. The lights stay bound for the scene's
     * particles until {@link #endScene()}.
     */
    public static void beginScene(Level level, Matrix4f view, Matrix4f projection, Vec3 camera, float partialTick) {
        var target = UISurface.currentTarget();
        prepare(new Frame(DynamicLightManager.scene(level), level, camera, new Matrix4f(view), new Matrix4f(projection),
                null, partialTick, target, GlStateManager.Viewport.x(), GlStateManager.Viewport.y(),
                GlStateManager.Viewport.width(), GlStateManager.Viewport.height(), false));
        apply(false);
    }

    public static void endScene() {
        compositeHaze();
        prepared = null;
    }

    /** The world's frame is finished, particles and post effects included: its haze goes on top. */
    public static void onFrameComposited() {
        compositeHaze();
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
            scene.volumeBuffer = free(scene.volumeBuffer);
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
        uploadData(count);
        if (shadowMode != ShadowMode.VOXEL) {
            scene.voxels.release();
            scene.visibilityAtlas = free(scene.visibilityAtlas);
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

    /** How much of what lies this far from the camera vanilla's fog leaves visible. */
    private static float vanillaFogLeft(double dx, double dy, double dz) {
        float start = RenderSystem.getShaderFogStart();
        float end = RenderSystem.getShaderFogEnd();
        double distance = RenderSystem.getShaderFogShape() == FogShape.CYLINDER
                ? Math.max(Math.sqrt(dx * dx + dz * dz), Math.abs(dy)) : Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= start) return 1f;
        float t = distance < end ? Mth.clamp((float) (distance - start) / Math.max(end - start, 1e-4f), 0f, 1f) : 1f;
        return 1f - RenderSystem.getShaderFogColor()[3] * t * t * (3f - 2f * t);
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
                .put(ambientLuma(frame, p.x, p.y, p.z)).put(light.sourceRadius > 0f ? light.sourceRadius : 0f);
    }

    /** Vanilla's lighting where the light sits: roughly what already lit the surfaces it is about to light. */
    private static float ambientLuma(Frame frame, double x, double y, double z) {
        if (frame.world() && IrisCompat.isUsingShaderPack()) return 0f; // the composite assumes the pack ambient instead
        var cache = frame.scene().ambient;
        long key = BlockPos.asLong(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        float cached = cache.getOrDefault(key, -1f);
        if (cached >= 0) return cached;
        int packed = LevelRenderer.getLightColor(frame.level(), BlockPos.of(key));
        var rgb = lightmap(LightTexture.block(packed), LightTexture.sky(packed));
        float luma = 0.2126f * rgb.x + 0.7152f * rgb.y + 0.0722f * rgb.z;
        cache.put(key, luma);
        return luma;
    }

    private static Vector3f lightmap(int block, int sky) {
        var pixels = ((LightTextureAccessor) Minecraft.getInstance().gameRenderer.lightTexture()).getLightPixels();
        int abgr = pixels.getPixelRGBA(Mth.clamp(block, 0, 15), Mth.clamp(sky, 0, 15));
        return new Vector3f((abgr & 0xFF) / 255f, (abgr >> 8 & 0xFF) / 255f, (abgr >> 16 & 0xFF) / 255f);
    }

    private static void uploadData(int count) {
        dataTexture = upload(dataTexture, LIGHTS_PER_ROW * 4, MAX_LIGHTS / LIGHTS_PER_ROW,
                (count + LIGHTS_PER_ROW - 1) / LIGHTS_PER_ROW, DATA);
    }

    /** The first {@code rows} rows of {@code data} into an RGBA32F texture, made on first use. */
    private static int upload(int texture, int width, int height, int rows, FloatBuffer data) {
        if (texture == -1) {
            texture = LightClusters.newDataTexture();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, width, height, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
        }
        data.position(0).limit(rows * width * 4);
        LightClusters.resetUnpack();
        GlStateManager._bindTexture(texture);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, rows, GL11.GL_RGBA, GL11.GL_FLOAT, data);
        GlStateManager._bindTexture(0);
        data.clear();
        return texture;
    }

    /**
     * Three texels per volumetric light, laid out in {@code dynamic_light_volume.fsh}. Packed at apply time, when
     * the terrain fog is set: vanilla fog fades a halo by the light's distance, as it fades the surfaces there.
     */
    private static void uploadVolumes(Frame frame, boolean afterPack) {
        float density = (float) (VOLUME_SCALE * PhotonConfig.INSTANCE.volumetricDensity.get());
        boolean fogged = frame.world() && !afterPack;
        var camera = frame.camera();
        VOLUME_DATA.clear();
        for (int v = 0; v < VOLUMETRIC.size(); v++) {
            var light = VOLUMETRIC.get(v);
            int k = VOLUME_RANK.getInt(v);
            var p = light.position;
            float scale = light.volumetric * light.intensity * density;
            if (fogged) scale *= vanillaFogLeft(p.x - camera.x, p.y - camera.y, p.z - camera.z);
            VOLUME_DATA.put(VIEW[k * 4]).put(VIEW[k * 4 + 1]).put(VIEW[k * 4 + 2]).put(VIEW[k * 4 + 3]);
            VOLUME_DATA.put(light.color.x * scale).put(light.color.y * scale).put(light.color.z * scale).put(SPOT[k * 5 + 4]);
            VOLUME_DATA.put(SPOT[k * 5]).put(SPOT[k * 5 + 1]).put(SPOT[k * 5 + 2]).put(SPOT[k * 5 + 3]);
        }
        volumeTexture = upload(volumeTexture, LIGHTS_PER_ROW * 3, MAX_LIGHTS / LIGHTS_PER_ROW,
                (VOLUMETRIC.size() + LIGHTS_PER_ROW - 1) / LIGHTS_PER_ROW, VOLUME_DATA);
    }

    /**
     * Eight texels per fog volume, laid out in {@code dynamic_light_fog.fsh}; true if any of them hides what is behind
     * it. Packed at apply time like the volumetric lights: vanilla fog thins a volume by its distance.
     */
    private static boolean uploadFog(Frame frame, boolean afterPack) {
        float density = (float) (FOG_SCALE * PhotonConfig.INSTANCE.volumetricDensity.get());
        boolean fogged = frame.world() && !afterPack;
        var camera = frame.camera();
        boolean absorbing = false;
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
            absorbing |= absorption > 0;
            float left = 1f;
            if (fogged) {
                var out = outside(fog, camera);
                left = vanillaFogLeft(out.x, out.y, out.z);
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
        fogTexture = upload(fogTexture, MAX_FOG * 8, 1, 1, FOG_DATA);
        return absorbing;
    }

    /** For shaders that want to be lit: the frame's light list, its clusters, visibility maps and their uniforms. */
    public static void bindLights(ShaderInstance shader) {
        var frame = prepared;
        bindLights(shader, frame == null ? 0 : frame.x(), frame == null ? 0 : frame.y());
        if (frame != null) {
            bindVisibility(shader, frame.scene());
            // lit particles shadow from every shadowed light's map: keep drawing them all while they're used
            litParticleTick = ticks;
        }
    }

    private static void bindVisibility(ShaderInstance shader, LightScene scene) {
        var atlas = visibilityMapCount > 0 ? scene.visibilityAtlas : null;
        // the unit needs a texture even when no map is read
        shader.setSampler("PhotonVisibilityAtlas", atlas != null ? atlas.getColorTextureId() : dataTexture);
        shader.safeGetUniform("PhotonVisibilityInfo").set(VISIBILITY_SIZE, VISIBILITY_PER_ROW, atlas != null ? visibilityMapCount : 0, 0);
        shader.safeGetUniform("PhotonViewInverse").set(INVERSE_VIEW);
    }

    /** For each of the first {@code maps} shadowed lights, how far its light gets in every direction. */
    private static void buildVisibilityMaps(Frame frame, int maps) {
        var shader = PhotonShaders.getDynamicLightVisibilityShader();
        maps = Math.min(maps, PhotonConfig.MAX_SHADOWED_LIGHTS);
        if (maps <= 0 || shader == null) return;
        var scene = frame.scene();
        var voxels = scene.voxels;
        int framebuffer = GlStateManager.getBoundFramebuffer();
        int viewportX = GlStateManager.Viewport.x();
        int viewportY = GlStateManager.Viewport.y();
        int viewportWidth = GlStateManager.Viewport.width();
        int viewportHeight = GlStateManager.Viewport.height();

        LightPassTimer.start();
        VISIBILITY_LIGHTS.clear();
        for (int map = 0; map < maps; map++) {
            int k = MAP_RANK.getInt(map);
            VISIBILITY_LIGHTS.put(VIEW[k * 4]).put(VIEW[k * 4 + 1]).put(VIEW[k * 4 + 2]).put(VIEW[k * 4 + 3]);
        }
        visibilityLightTexture = upload(visibilityLightTexture, PhotonConfig.MAX_SHADOWED_LIGHTS, 1, 1, VISIBILITY_LIGHTS);

        // the whole atlas once, drawn only as far as this frame's maps: their count changes often
        var atlas = scene.visibilityAtlas = allocate(scene.visibilityAtlas, VISIBILITY_SIZE * VISIBILITY_PER_ROW,
                VISIBILITY_SIZE * VISIBILITY_ROWS, TargetFormat.R16F, false);
        atlas.bindWrite(false);
        RenderSystem.viewport(0, 0, atlas.width, VISIBILITY_SIZE * ((maps + VISIBILITY_PER_ROW - 1) / VISIBILITY_PER_ROW));
        var camera = frame.camera();
        int blockX = Mth.floor(camera.x), blockY = Mth.floor(camera.y), blockZ = Mth.floor(camera.z);
        INVERSE_VIEW.set(frame.view()).invert();
        shader.setSampler("VisibilityLights", visibilityLightTexture);
        shader.setSampler("VoxelPages", voxels.pageTexture());
        shader.setSampler("VoxelBricks", voxels.brickTexture());
        shader.safeGetUniform("IViewMat").set(INVERSE_VIEW);
        shader.safeGetUniform("CameraFrac").set((float) (camera.x - blockX), (float) (camera.y - blockY), (float) (camera.z - blockZ));
        shader.safeGetUniform("VoxelOrigin").set(voxels.originBlockX() - blockX, voxels.originBlockY() - blockY,
                voxels.originBlockZ() - blockZ);
        shader.safeGetUniform("VisibilityInfo").set(VISIBILITY_SIZE, VISIBILITY_PER_ROW, maps, 0);
        draw(shader, true);
        LightPassTimer.mark(LightPassTimer.Stage.VISIBILITY);
        visibilityMapCount = maps;
        restore(framebuffer, viewportX, viewportY, viewportWidth, viewportHeight);
    }

    private static void bindLights(ShaderInstance shader, int originX, int originY) {
        shader.setSampler("PhotonLightData", dataTexture);
        LightClusters.bind(shader, originX, originY);
    }

    private static void apply(boolean afterPack) {
        var frame = prepared;
        var passShader = PhotonShaders.getDynamicLightPassShader();
        var blurShader = PhotonShaders.getDynamicLightBlurShader();
        var compositeShader = PhotonShaders.getDynamicLightCompositeShader();
        if (frame == null || lightCount == 0 && fogCount == 0 || passShader == null || blurShader == null || compositeShader == null) {
            return;
        }
        long start = System.nanoTime();
        var config = PhotonConfig.INSTANCE;
        var scene = frame.scene();
        var level = frame.level();
        boolean soft = config.lightSoftShadows.get();
        int framebuffer = GlStateManager.getBoundFramebuffer();
        int viewportX = GlStateManager.Viewport.x();
        int viewportY = GlStateManager.Viewport.y();
        int viewportWidth = GlStateManager.Viewport.width();
        int viewportHeight = GlStateManager.Viewport.height();
        int width = frame.width(), height = frame.height();
        float scale = config.lightResolution.get().floatValue();
        int lightWidth = Math.max(1, Math.round(width * scale));
        int lightHeight = Math.max(1, Math.round(height * scale));

        LightPassTimer.start();
        var snapshot = scene.snapshot = allocate(scene.snapshot, width, height, TargetFormat.RGBA8, true);
        var lightBuffer = scene.lightBuffer = allocate(scene.lightBuffer, lightWidth, lightHeight, TargetFormat.RGBA16F, false);
        copyRegion(frame.target(), frame.x(), frame.y(), snapshot, afterPack ? IrisCompat.gbufferRenderScale() : 1f);
        LightPassTimer.mark(LightPassTimer.Stage.COPY);

        var projection = frame.projection();
        INVERSE_PROJECTION.set(projection).invert();
        INVERSE_VIEW.set(frame.view()).invert();
        var camera = frame.camera();
        int blockX = Mth.floor(camera.x), blockY = Mth.floor(camera.y), blockZ = Mth.floor(camera.z);
        var voxels = scene.voxels;

        // fog alone lights no surface: no light pass, no composite
        boolean surfaces = lightCount > 0;
        if (surfaces) {
            lightBuffer.bindWrite(true);
            bindCommon(passShader, width, height, scale, afterPack);
            passShader.safeGetUniform("ProjMat").set(projection);
            passShader.setSampler("SceneDepthSampler", snapshot.getDepthTextureId());
            bindLights(passShader, 0, 0);
            passShader.setSampler("VoxelPages", voxels.pageTexture());
            passShader.setSampler("VoxelBricks", voxels.brickTexture());
            passShader.safeGetUniform("ShadowParams").set((float) config.lightShadowMode.get().ordinal(),
                    (float) config.lightScreenShadowSteps.get(), config.lightScreenShadowThickness.get().floatValue(),
                    config.lightScreenShadowDistance.get().floatValue());
            passShader.safeGetUniform("ContactParams").set((float) config.lightContactShadowSteps.get(),
                    config.lightContactShadowLength.get().floatValue(), soft ? 1f : 0f, 0f);
            passShader.safeGetUniform("CameraFrac").set((float) (camera.x - blockX), (float) (camera.y - blockY), (float) (camera.z - blockZ));
            passShader.safeGetUniform("VoxelOrigin").set(voxels.originBlockX() - blockX, voxels.originBlockY() - blockY,
                    voxels.originBlockZ() - blockZ);
            draw(passShader, true);
        }
        LightPassTimer.mark(LightPassTimer.Stage.LIGHT);

        if (surfaces && soft && shadowedCount > 0) {
            var blurBuffer = scene.blurBuffer = allocate(scene.blurBuffer, lightWidth, lightHeight, TargetFormat.RGBA16F, false);
            blurBuffer.bindWrite(true);
            blurPass(blurShader, snapshot, lightBuffer, 1f, 0f, width, height, scale, -1f);
            lightBuffer.bindWrite(true);
            blurPass(blurShader, snapshot, blurBuffer, 0f, 1f, width, height, scale, -1f);
        }
        LightPassTimer.mark(LightPassTimer.Stage.BLUR);

        var volumeShader = PhotonShaders.getDynamicLightVolumeShader();
        var volumeBuffer = (volumeCount > 0 || fogCount > 0) && volumeShader != null
                ? scene.volumeBuffer = allocate(scene.volumeBuffer, lightWidth, lightHeight, TargetFormat.RGBA16F, false)
                : null;
        boolean absorbing = false;
        if (volumeBuffer != null) {
            int shadowedVolumes = Math.min(volumeShadowedCount, visibilityMapCount);
            if (volumeCount > 0) {
                uploadVolumes(frame, afterPack);
                volumeBuffer.bindWrite(true);
                bindCommon(volumeShader, width, height, scale, afterPack);
                volumeShader.setSampler("SceneDepthSampler", snapshot.getDepthTextureId());
                volumeShader.setSampler("VolumeLightData", volumeTexture);
                bindVisibility(volumeShader, scene);
                LightClusters.bindVolume(volumeShader);
                volumeShader.safeGetUniform("VolumeParams").set(config.volumetricForwardScattering.get().floatValue(),
                        (float) config.volumetricSamples.get(), (float) shadowedVolumes, 0f);
                draw(volumeShader, true);
            } else {
                // no haze yet, and a scene that still shows through completely
                volumeBuffer.setClearColor(0f, 0f, 0f, 1f);
                volumeBuffer.clear(Minecraft.ON_OSX);
            }
            LightPassTimer.mark(LightPassTimer.Stage.VOLUME);
            var fogShader = PhotonShaders.getDynamicLightFogShader();
            if (fogCount > 0 && fogShader != null) {
                absorbing = uploadFog(frame, afterPack);
                volumeBuffer.bindWrite(true);
                bindCommon(fogShader, width, height, scale, afterPack);
                fogShader.setSampler("SceneDepthSampler", snapshot.getDepthTextureId());
                bindLights(fogShader, 0, 0);
                bindVisibility(fogShader, scene);
                fogShader.setSampler("FogData", fogTexture);
                fogShader.safeGetUniform("FogInfo").set(fogCount, config.volumetricSamples.get() * 4, 0, 0);
                fogShader.safeGetUniform("ForwardScattering").set(config.volumetricForwardScattering.get().floatValue());
                // rgb adds to the haze, alpha multiplies into the transmittance
                drawBlended(fogShader, true, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_SRC_ALPHA);
            }
            LightPassTimer.mark(LightPassTimer.Stage.FOG);
            if (shadowedVolumes > 0 || fogCount > 0) {
                // the shadowed haze and the fog are sampled at a different offset per pixel of a 4x4 tile
                var blurBuffer = scene.blurBuffer = allocate(scene.blurBuffer, lightWidth, lightHeight, TargetFormat.RGBA16F, false);
                blurBuffer.bindWrite(true);
                blurPass(blurShader, snapshot, volumeBuffer, 1f, 0f, width, height, scale, SKY_DEPTH);
                volumeBuffer.bindWrite(true);
                blurPass(blurShader, snapshot, blurBuffer, 0f, 1f, width, height, scale, SKY_DEPTH);
            }
            LightPassTimer.mark(LightPassTimer.Stage.VOLUME_BLUR);
        }

        // the volume debug view shows black under the haze without reading the light buffer
        if (surfaces || LightDebug.view == 6) {
            frame.target().bindWrite(false);
            RenderSystem.viewport(frame.x(), frame.y(), width, height);
            bindCommon(compositeShader, width, height, scale, afterPack);
            compositeShader.safeGetUniform("ViewportOrigin").set(frame.x(), frame.y());
            compositeShader.setSampler("SceneColorSampler", snapshot.getColorTextureId());
            compositeShader.setSampler("SceneDepthSampler", snapshot.getDepthTextureId());
            compositeShader.setSampler("IrradianceSampler", lightBuffer.getColorTextureId());
            compositeShader.safeGetUniform("FogStart").set(RenderSystem.getShaderFogStart());
            compositeShader.safeGetUniform("FogEnd").set(RenderSystem.getShaderFogEnd());
            var fog = RenderSystem.getShaderFogColor();
            // only the world's own frame is fogged by vanilla's numbers: a pack fogs its own way, an editor scene not at all
            compositeShader.safeGetUniform("FogColor").set(fog[0], fog[1], fog[2], frame.world() && !afterPack ? fog[3] : 0f);
            compositeShader.safeGetUniform("FogShape").set(RenderSystem.getShaderFogShape().getIndex());
            var floor = lightmap(0, 0);
            compositeShader.safeGetUniform("LightmapFloor").set(floor.x, floor.y, floor.z);
            compositeShader.safeGetUniform("FaceShade").set(level.getShade(Direction.DOWN, true), level.getShade(Direction.UP, true),
                    level.getShade(Direction.NORTH, true), level.getShade(Direction.EAST, true));
            compositeShader.safeGetUniform("AlbedoMax").set(config.lightAlbedoMax.get().floatValue());
            draw(compositeShader, false);
        }
        LightPassTimer.mark(LightPassTimer.Stage.COMPOSITE);
        // the other debug views show the surfaces alone
        if (volumeBuffer != null && (LightDebug.view == 0 || LightDebug.view == 6)) {
            var haze = new Haze(frame, new Matrix4f(INVERSE_PROJECTION), scale, afterPack);
            // what the fog hides goes dark now, before the particles: a flame in front of a fog bank stays bright
            if (absorbing) {
                drawHaze(haze, 1);
                LightPassTimer.mark(LightPassTimer.Stage.HAZE);
            }
            pendingHaze = haze;
        }

        restore(framebuffer, viewportX, viewportY, viewportWidth, viewportHeight);
        LightPassTimer.cpu((System.nanoTime() - start) / 1e6);
    }

    private static void compositeHaze() {
        var haze = pendingHaze;
        pendingHaze = null;
        if (haze == null) return;
        int framebuffer = GlStateManager.getBoundFramebuffer();
        int viewportX = GlStateManager.Viewport.x();
        int viewportY = GlStateManager.Viewport.y();
        int viewportWidth = GlStateManager.Viewport.width();
        int viewportHeight = GlStateManager.Viewport.height();
        LightPassTimer.start();
        drawHaze(haze, 0);
        LightPassTimer.mark(LightPassTimer.Stage.HAZE);
        restore(framebuffer, viewportX, viewportY, viewportWidth, viewportHeight);
    }

    /**
     * The volume buffer over the frame's target. Mode 0 adds the haze as a soft add without reading the frame back,
     * src + dst * (1 - src); mode 1 multiplies the frame by the fog's transmittance.
     */
    private static void drawHaze(Haze haze, int mode) {
        var shader = PhotonShaders.getDynamicLightHazeShader();
        var frame = haze.frame();
        var scene = frame.scene();
        if (shader == null || scene.snapshot == null || scene.volumeBuffer == null) return;
        frame.target().bindWrite(false);
        RenderSystem.viewport(frame.x(), frame.y(), frame.width(), frame.height());
        shader.safeGetUniform("IProjMat").set(haze.inverseProjection());
        shader.safeGetUniform("ScreenSize").set((float) frame.width(), (float) frame.height());
        shader.safeGetUniform("ResolutionScale").set(haze.scale());
        shader.safeGetUniform("PackParams").set(haze.afterPack() ? 1f : 0f, 0f, 0f, 0f);
        shader.safeGetUniform("ViewportOrigin").set(frame.x(), frame.y());
        shader.safeGetUniform("HazeMode").set(mode);
        shader.setSampler("SceneDepthSampler", scene.snapshot.getDepthTextureId());
        shader.setSampler("VolumeSampler", scene.volumeBuffer.getColorTextureId());
        if (mode == 0) {
            drawBlended(shader, false, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_COLOR, GL11.GL_ZERO, GL11.GL_ONE);
        } else {
            drawBlended(shader, false, GL11.GL_ZERO, GL11.GL_SRC_COLOR, GL11.GL_ZERO, GL11.GL_ONE);
        }
    }

    /**
     * Puts back the framebuffer and viewport a pass found, by way of the main target's {@code bindWrite}: only that tells
     * Iris the main target is current again, and only then does it draw with the pack's shaders.
     */
    private static void restore(int framebuffer, int x, int y, int width, int height) {
        var main = Minecraft.getInstance().getMainRenderTarget();
        main.bindWrite(false);
        if (framebuffer != main.frameBufferId) GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        RenderSystem.viewport(x, y, width, height);
    }

    /**
     * The frame's viewport of {@code from}, colour and depth, into the whole of {@code to}. Below 1, {@code depthScale}
     * is the corner of the depth a shader pack drew its world into: only its colour was upscaled to the screen.
     */
    private static void copyRegion(RenderTarget from, int x, int y, FormatTarget to, float depthScale) {
        // a depth blit needs matching formats, and a stencil turns the source's depth into DEPTH32F_STENCIL8
        if (from.isStencilEnabled() && !to.isStencilEnabled()) {
            to.enableStencil();
        }
        var scaler = depthScale < 1f ? PhotonShaders.getDynamicLightDepthScaleShader() : null;
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
        GlStateManager._glBlitFrameBuffer(x, y, x + to.width, y + to.height, 0, 0, to.width, to.height,
                scaler == null ? GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT : GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        if (scaler != null) {
            to.bindWrite(true);
            scaler.setSampler("DepthSampler", from.getDepthTextureId());
            scaler.safeGetUniform("DepthScale").set(depthScale);
            scaler.apply();
            GlStateManager._colorMask(false, false, false, false);
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(GL11.GL_ALWAYS);
            GlStateManager._depthMask(true);
            SceneBlit.drawFullscreenQuad();
            scaler.clear();
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._colorMask(true, true, true, true);
        }
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private static void bindCommon(ShaderInstance shader, int width, int height, float scale, boolean afterPack) {
        shader.safeGetUniform("IProjMat").set(INVERSE_PROJECTION);
        shader.safeGetUniform("IViewMat").set(INVERSE_VIEW);
        shader.safeGetUniform("ScreenSize").set((float) width, (float) height);
        shader.safeGetUniform("ResolutionScale").set(scale);
        shader.safeGetUniform("PackParams").set(afterPack ? 1f : 0f, PhotonConfig.INSTANCE.lightShaderPackAmbient.get().floatValue(), 0f, 0f);
        shader.safeGetUniform("DebugMode").set(LightDebug.view);
    }

    /** {@code skyDepth}: where sky texels stand, or below 0 to leave them alone. */
    private static void blurPass(ShaderInstance shader, FormatTarget depth, FormatTarget source, float dx, float dy,
                                 int width, int height, float scale, float skyDepth) {
        shader.setSampler("IrradianceSampler", source.getColorTextureId());
        shader.setSampler("SceneDepthSampler", depth.getDepthTextureId());
        shader.safeGetUniform("IProjMat").set(INVERSE_PROJECTION);
        shader.safeGetUniform("ScreenSize").set((float) width, (float) height);
        shader.safeGetUniform("ResolutionScale").set(scale);
        shader.safeGetUniform("BlurDirection").set(dx, dy);
        shader.safeGetUniform("SkyDepth").set(skyDepth);
        draw(shader, true);
    }

    private static void draw(ShaderInstance shader, boolean writeAlpha) {
        shader.apply();
        GlStateManager._disableBlend();
        GlStateManager._colorMask(true, true, true, writeAlpha);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        SceneBlit.drawFullscreenQuad();
        shader.clear();
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._depthMask(true);
        GlStateManager._enableDepthTest();
    }

    private static void drawBlended(ShaderInstance shader, boolean writeAlpha, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        shader.apply();
        GlStateManager._enableBlend();
        GlStateManager._blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        GlStateManager._colorMask(true, true, true, writeAlpha);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        SceneBlit.drawFullscreenQuad();
        shader.clear();
        RenderSystem.defaultBlendFunc();
        GlStateManager._disableBlend();
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._depthMask(true);
        GlStateManager._enableDepthTest();
    }

    @Nullable
    private static FormatTarget free(@Nullable FormatTarget target) {
        if (target != null) target.destroyBuffers();
        return null;
    }

    private static FormatTarget allocate(@Nullable FormatTarget target, int width, int height, TargetFormat format, boolean depth) {
        if (target == null) {
            return new FormatTarget(width, height, GL11.GL_NEAREST, format, depth);
        }
        if (target.width != width || target.height != height) {
            target.resize(width, height, Minecraft.ON_OSX);
        }
        return target;
    }

    /** World frames only; {@code pose == null} after the level render, which has popped the camera rotation. */
    private static void renderMarkers(@Nullable Matrix4f pose) {
        var frame = prepared;
        if (frame == null || !frame.world() || !LightDebug.markers || FRAME_LIGHTS.isEmpty()) return;
        if (pose == null) {
            pose = new Matrix4f(frame.view());
            frame.target().bindWrite(false);
        }
        var camera = frame.camera();
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        var consumer = buffers.getBuffer(RenderType.debugQuads());
        for (var light : FRAME_LIGHTS) {
            if (!light.enabled) continue;
            var c = light.color;
            float peak = Math.max(1e-3f, Math.max(c.x, Math.max(c.y, c.z)));
            cube(consumer, pose, (float) (light.position.x - camera.x), (float) (light.position.y - camera.y),
                    (float) (light.position.z - camera.z), 0.08f, c.x / peak, c.y / peak, c.z / peak);
        }
        buffers.endBatch(RenderType.debugQuads());
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
