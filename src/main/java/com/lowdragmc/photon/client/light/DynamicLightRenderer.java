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
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lights the opaque scene. A frame's lights are culled, ranked and uploaded once, readable by shaders until
 * the frame ends; then the pass lights the colour and depth already drawn. The world runs it at
 * {@code AFTER_BLOCK_ENTITIES} (after the pack's final image under a shader pack), an editor scene before
 * its particles and into its own viewport.
 */
@OnlyIn(Dist.CLIENT)
public final class DynamicLightRenderer {
    public static final int MAX_LIGHTS = 1024;
    private static final int LIGHTS_PER_ROW = 256;
    /** Ticks a scene may go unlit before its frame targets and voxel atlas are freed. */
    private static final int IDLE_TICKS = 200;
    private static final FloatBuffer DATA = MemoryUtil.memAllocFloat(MAX_LIGHTS * 16);
    private static final float[] VIEW = new float[MAX_LIGHTS * 4];
    private static final List<DynamicLight> FRAME_LIGHTS = new ArrayList<>();
    private static final List<DynamicLight> SHADOWED = new ArrayList<>();
    // scenes holding GPU memory, held strongly so one whose level vanished without an unload is still freed
    private static final Set<LightScene> ACTIVE = new HashSet<>();
    private static final Matrix4f INVERSE_PROJECTION = new Matrix4f();
    private static final Matrix4f INVERSE_VIEW = new Matrix4f();
    private static final Vector3f TMP = new Vector3f();
    private static DynamicLight[] candidates = new DynamicLight[64];
    private static float[] importance = new float[64];
    private static int[] order = new int[64];

    /** What a frame lights and where: {@code x, y, width, height} is the viewport inside {@code target}. */
    private record Frame(LightScene scene, Level level, Vec3 camera, Matrix4f view, Matrix4f projection,
                         @Nullable Frustum frustum, float partialTick, RenderTarget target,
                         int x, int y, int width, int height, boolean world) {
    }

    private static int dataTexture = -1;
    @Nullable
    private static Frame prepared;
    private static int lightCount;
    private static int shadowedCount;
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

    private static void prepare(Frame frame) {
        prepared = null;
        lightCount = 0;
        shadowedCount = 0;
        var config = PhotonConfig.INSTANCE;
        var scene = frame.scene();
        if (!config.dynamicLights.get() || frame.width() <= 0 || frame.height() <= 0
                || DynamicLightManager.isEmpty(scene, frame.world())) return;
        long start = System.nanoTime();
        var lights = DynamicLightManager.collect(scene, frame.world(), frame.partialTick(), FRAME_LIGHTS);

        int n = 0;
        var camera = frame.camera();
        for (var light : lights) {
            if (!light.enabled || light.range <= 0 || light.intensity <= 0) continue;
            var p = light.position;
            double r = light.range;
            if (frame.frustum() != null && !frame.frustum().isVisible(new AABB(p.x - r, p.y - r, p.z - r, p.x + r, p.y + r, p.z + r))) {
                continue;
            }
            if (n == candidates.length) {
                candidates = Arrays.copyOf(candidates, n * 2);
                importance = Arrays.copyOf(importance, n * 2);
                order = Arrays.copyOf(order, n * 2);
            }
            var c = light.color;
            float luminance = 0.2126f * c.x + 0.7152f * c.y + 0.0722f * c.z;
            candidates[n] = light;
            importance[n] = (float) (luminance * light.intensity * r * r / Math.max(camera.distanceToSqr(p.x, p.y, p.z), 1.0));
            order[n] = n;
            n++;
        }
        if (n == 0) {
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
        DATA.clear();
        for (int k = 0; k < count; k++) {
            var light = candidates[order[k]];
            boolean shadowed = light.castShadows && SHADOWED.size() < shadowBudget;
            if (shadowed) SHADOWED.add(light);
            pack(light, k, shadowed, frame);
        }
        Arrays.fill(candidates, 0, n, null);

        LightClusters.build(VIEW, count, frame.projection(), frame.width(), frame.height());
        uploadData(count);
        if (shadowMode != ShadowMode.VOXEL) {
            scene.voxels.release();
        } else if (!SHADOWED.isEmpty()) {
            scene.voxels.update(frame.level(), camera, SHADOWED, (long) (config.lightVoxelBudgetMs.get() * 1e6));
        }
        lightCount = count;
        shadowedCount = SHADOWED.size();
        prepared = frame;
        prepareMillis = (System.nanoTime() - start) / 1e6;
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
        float outer = Mth.clamp(light.outerAngle, 0.1f, 179f);
        float inner = Mth.clamp(light.innerAngle, 0f, outer - 0.05f);
        DATA.put(TMP.x).put(TMP.y).put(TMP.z).put((float) Math.cos(Math.toRadians(outer)));
        DATA.put((float) Math.cos(Math.toRadians(inner))).put(shadowed ? 1f : 0f)
                .put(ambientLuma(frame, p.x, p.y, p.z)).put(Math.max(light.sourceRadius, 0f));
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
        if (dataTexture == -1) {
            dataTexture = LightClusters.newDataTexture();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, LIGHTS_PER_ROW * 4, MAX_LIGHTS / LIGHTS_PER_ROW, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
        }
        int rows = (count + LIGHTS_PER_ROW - 1) / LIGHTS_PER_ROW;
        DATA.position(0).limit(rows * LIGHTS_PER_ROW * 16);
        LightClusters.resetUnpack();
        GlStateManager._bindTexture(dataTexture);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, LIGHTS_PER_ROW * 4, rows, GL11.GL_RGBA, GL11.GL_FLOAT, DATA);
        GlStateManager._bindTexture(0);
        DATA.clear();
    }

    /** For shaders that want to be lit: the frame's light list, its clusters and their uniforms. */
    public static void bindLights(ShaderInstance shader) {
        var frame = prepared;
        bindLights(shader, frame == null ? 0 : frame.x(), frame == null ? 0 : frame.y());
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
        if (frame == null || lightCount == 0 || passShader == null || blurShader == null || compositeShader == null) return;
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

        LightPassTimer.begin();
        var snapshot = scene.snapshot = allocate(scene.snapshot, width, height, TargetFormat.RGBA8, true);
        var lightBuffer = scene.lightBuffer = allocate(scene.lightBuffer, lightWidth, lightHeight, TargetFormat.RGBA16F, false);
        copyRegion(frame.target(), frame.x(), frame.y(), snapshot);
        LightPassTimer.mark(LightPassTimer.Stage.COPY);

        var projection = frame.projection();
        INVERSE_PROJECTION.set(projection).invert();
        INVERSE_VIEW.set(frame.view()).invert();
        var camera = frame.camera();
        int blockX = Mth.floor(camera.x), blockY = Mth.floor(camera.y), blockZ = Mth.floor(camera.z);
        var voxels = scene.voxels;

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
        LightPassTimer.mark(LightPassTimer.Stage.LIGHT);

        if (soft && shadowedCount > 0) {
            var blurBuffer = scene.blurBuffer = allocate(scene.blurBuffer, lightWidth, lightHeight, TargetFormat.RGBA16F, false);
            blurBuffer.bindWrite(true);
            blurPass(blurShader, snapshot, lightBuffer, 1f, 0f, width, height, scale);
            lightBuffer.bindWrite(true);
            blurPass(blurShader, snapshot, blurBuffer, 0f, 1f, width, height, scale);
        }
        LightPassTimer.mark(LightPassTimer.Stage.BLUR);

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
        LightPassTimer.mark(LightPassTimer.Stage.COMPOSITE);

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
        LightPassTimer.end((System.nanoTime() - start) / 1e6 + prepareMillis);
    }

    /** The frame's viewport of {@code from}, colour and depth, into the whole of {@code to}. */
    private static void copyRegion(RenderTarget from, int x, int y, FormatTarget to) {
        // a depth blit needs matching formats, and a stencil turns the source's depth into DEPTH32F_STENCIL8
        if (from.isStencilEnabled() && !to.isStencilEnabled()) {
            to.enableStencil();
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
        GlStateManager._glBlitFrameBuffer(x, y, x + to.width, y + to.height, 0, 0, to.width, to.height,
                GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
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

    private static void blurPass(ShaderInstance shader, FormatTarget depth, FormatTarget source, float dx, float dy,
                                 int width, int height, float scale) {
        shader.setSampler("IrradianceSampler", source.getColorTextureId());
        shader.setSampler("SceneDepthSampler", depth.getDepthTextureId());
        shader.safeGetUniform("IProjMat").set(INVERSE_PROJECTION);
        shader.safeGetUniform("ScreenSize").set((float) width, (float) height);
        shader.safeGetUniform("ResolutionScale").set(scale);
        shader.safeGetUniform("BlurDirection").set(dx, dy);
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
