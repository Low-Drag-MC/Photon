package com.lowdragmc.photon.gui.editor.view.scene;

import com.lowdragmc.lowdraglib2.client.scene.WorldSceneRenderer;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A Minecraft-style skybox for the FX editor scene, drawn in the scene renderer's
 * {@code beforeWorldRender} hook (right after the clear, before blocks/particles — everything else
 * naturally renders over it). Ports the vanilla {@code LevelRenderer.renderSky} pieces: the sky dome
 * disc (vertex-colored, vanilla geometry), the sun / full moon (vanilla environment textures, additive
 * blend), and the vanilla starfield (same seed and distribution) at night. Fixed at noon / midnight —
 * the {@link SceneView.SceneBackground} preset picks which; the FBO clear color acts as the horizon
 * (fog) color below the dome, exactly like vanilla's fog-colored void.
 *
 * <p>Everything is drawn camera-centered ({@code depthMask off}), so the sky never parallaxes and the
 * platform/world always draws over it. Buffers are tiny, built lazily on the render thread and kept
 * for the session (like vanilla's own sky buffers).</p>
 */
@OnlyIn(Dist.CLIENT)
public final class SceneSkyRenderer {

    private static final ResourceLocation SUN_LOCATION = ResourceLocation.withDefaultNamespace("textures/environment/sun.png");
    private static final ResourceLocation MOON_LOCATION = ResourceLocation.withDefaultNamespace("textures/environment/moon_phases.png");

    /** Celestial tilt off zenith (degrees) — a straight-overhead sun/moon reads flat and unlit. */
    private static final float CELESTIAL_TILT = 30f;
    private static final float STAR_BRIGHTNESS = 0.6f;

    @Nullable
    private static VertexBuffer skyBuffer;
    @Nullable
    private static VertexBuffer starBuffer;

    private SceneSkyRenderer() {}

    /** Draw the skybox for {@code background} — call from the scene's beforeWorldRender (render thread). */
    public static void render(WorldSceneRenderer renderer, SceneView.SceneBackground background) {
        ensureBuffers();
        if (skyBuffer == null || starBuffer == null) return;

        Vector3f eye = renderer.getEyePos();
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        // Sky geometry is authored around the origin; center it on the camera so it never parallaxes.
        Matrix4f skyPose = new Matrix4f(RenderSystem.getModelViewMatrix()).translate(eye.x(), eye.y(), eye.z());
        Matrix4f celestialPose = new Matrix4f(skyPose)
                .rotateY((float) Math.toRadians(-90.0))
                .rotateX((float) Math.toRadians(CELESTIAL_TILT));

        FogRenderer.setupNoFog();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();

        // The sky dome disc, tinted with the preset's zenith color (vanilla renders it the same way).
        int sky = background.skyColor;
        RenderSystem.setShaderColor(
                ((sky >> 16) & 0xFF) / 255f, ((sky >> 8) & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
        skyBuffer.bind();
        skyBuffer.drawWithShader(skyPose, projection, GameRenderer.getPositionShader());
        VertexBuffer.unbind();

        // Sun / moon (+stars) with vanilla's additive celestial blend.
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        // The quad's matrix is world-space only (BufferUploader applies the scene's ModelViewMat itself).
        Matrix4f celestialLocal = new Matrix4f()
                .translate(eye.x(), eye.y(), eye.z())
                .rotateY((float) Math.toRadians(-90.0))
                .rotateX((float) Math.toRadians(CELESTIAL_TILT));
        if (background.day) {
            RenderSystem.setShaderTexture(0, SUN_LOCATION);
            drawCelestialQuad(celestialLocal, 30f, 0f, 0f, 1f, 1f);
        } else {
            RenderSystem.setShaderTexture(0, MOON_LOCATION);
            // full moon = frame (0, 0) of the 4x2 moon_phases sheet
            drawCelestialQuad(celestialLocal, 20f, 0f, 0f, 0.25f, 0.5f);

            RenderSystem.setShaderColor(STAR_BRIGHTNESS, STAR_BRIGHTNESS, STAR_BRIGHTNESS, STAR_BRIGHTNESS);
            starBuffer.bind();
            starBuffer.drawWithShader(celestialPose, projection, GameRenderer.getPositionShader());
            VertexBuffer.unbind();
        }

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
    }

    private static void drawCelestialQuad(Matrix4f pose, float size, float u0, float v0, float u1, float v1) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(pose, -size, 100f, -size).setUv(u0, v0);
        buffer.addVertex(pose, size, 100f, -size).setUv(u1, v0);
        buffer.addVertex(pose, size, 100f, size).setUv(u1, v1);
        buffer.addVertex(pose, -size, 100f, size).setUv(u0, v1);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    private static void ensureBuffers() {
        if (skyBuffer != null && starBuffer != null) return;

        // Vanilla LevelRenderer.buildSkyDisc: a fan at y=16 flaring out to radius 512.
        var tesselator = Tesselator.getInstance();
        var sky = tesselator.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION);
        sky.addVertex(0f, 16f, 0f);
        for (int angle = -180; angle <= 180; angle += 45) {
            sky.addVertex(512f * Mth.cos(angle * Mth.DEG_TO_RAD), 16f, 512f * Mth.sin(angle * Mth.DEG_TO_RAD));
        }
        skyBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        skyBuffer.bind();
        skyBuffer.upload(sky.buildOrThrow());

        // Vanilla LevelRenderer.drawStars: 1500 candidates on the unit sphere (same seed), randomly
        // rotated quads pushed to radius 100.
        var stars = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        RandomSource random = RandomSource.create(10842L);
        for (int i = 0; i < 1500; i++) {
            float x = random.nextFloat() * 2f - 1f;
            float y = random.nextFloat() * 2f - 1f;
            float z = random.nextFloat() * 2f - 1f;
            float size = 0.15f + random.nextFloat() * 0.1f;
            float lengthSq = Mth.lengthSquared(x, y, z);
            if (lengthSq <= 0.010000001f || lengthSq >= 1f) continue;
            Vector3f center = new Vector3f(x, y, z).normalize(100f);
            float roll = (float) (random.nextDouble() * Math.PI * 2.0);
            Quaternionf rotation = new Quaternionf().rotateTo(new Vector3f(0f, 0f, -1f), center).rotateZ(roll);
            stars.addVertex(new Vector3f(size, -size, 0f).rotate(rotation).add(center));
            stars.addVertex(new Vector3f(size, size, 0f).rotate(rotation).add(center));
            stars.addVertex(new Vector3f(-size, size, 0f).rotate(rotation).add(center));
            stars.addVertex(new Vector3f(-size, -size, 0f).rotate(rotation).add(center));
        }
        starBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        starBuffer.bind();
        starBuffer.upload(stars.buildOrThrow());
        VertexBuffer.unbind();
    }
}
