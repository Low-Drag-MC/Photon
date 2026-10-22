package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

import com.lowdragmc.lowdraglib2.gui.ui.rendering.UISurface;
import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.compat.iris.IrisDepthSource;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public final class TerrainDepthCapture {
    private static final Slot WORLD = new Slot();
    private static final Slot EDITOR = new Slot();
    private static int editorDepth;
    private static long copies;

    private TerrainDepthCapture() {}

    public static void captureWorld() {
        if (IrisCompat.isShadowPass()) return;
        WORLD.valid = false;
        if (!WORLD.demand.wanted() || !hasParticles()) return;
        if (IrisCompat.isUsingShaderPack()) {
            var source = IrisCompat.terrainDepthSource();
            if (source == null) return;
            capture(WORLD, source.framebuffer(), source.texture(), source.width(), source.height(), source.version(), true);
        } else {
            capture(WORLD);
        }
    }

    public static void beginEditor() {
        editorDepth++;
        EDITOR.valid = false;
    }

    public static boolean needsEditorCapture() {
        return editorDepth == 1 && EDITOR.demand.wanted() && !EDITOR.valid;
    }

    public static void captureEditor() {
        if (needsEditorCapture()) capture(EDITOR);
    }

    public static void endEditor() {
        EDITOR.valid = false;
        editorDepth = Math.max(0, editorDepth - 1);
    }

    public static int requestTexture() {
        Slot slot = editorDepth > 0 ? EDITOR : WORLD;
        slot.demand.request();
        return texture(slot);
    }

    public static long copies() {
        return copies;
    }

    public static void endFrame() {
        WORLD.endFrame();
        EDITOR.endFrame();
        editorDepth = 0;
    }

    public static void release() {
        for (Slot slot : new Slot[]{WORLD, EDITOR}) {
            if (slot.target != null) slot.target.destroy();
            slot.target = null;
            slot.valid = false;
            slot.demand.reset();
            slot.level = null;
        }
        editorDepth = 0;
    }

    private static int texture(Slot slot) {
        if (!slot.valid || slot.target == null) return 0;
        var source = UISurface.currentTarget();
        if (slot.iris) {
            var pipeline = RenderPassPipeline.getCurrent();
            var iris = pipeline == null ? null : pipeline.getIrisTarget();
            if (iris == null || slot.irisSource == null || !slot.irisSource.matches(iris.sceneDepthTexture(),
                    iris.bufferWidth(), iris.bufferHeight(), iris.depthBufferVersion())) return 0;
        } else if (source.frameBufferId != slot.sourceFramebuffer
                || source.width != slot.target.width || source.height != slot.target.height) return 0;
        if (GlStateManager.Viewport.x() != slot.viewportX || GlStateManager.Viewport.y() != slot.viewportY
                || GlStateManager.Viewport.width() != slot.viewportWidth
                || GlStateManager.Viewport.height() != slot.viewportHeight) return 0;
        if (editorDepth == 0 && (PhotonParticleManager.getRenderingManager() != null
                || Minecraft.getInstance().level != slot.level)) return 0;
        return slot.target.texture;
    }

    private static boolean hasParticles() {
        return ParticleQueueRenderType.TRANSLUCENT_QUEUE.hasQueuedParticles()
                || ParticleQueueRenderType.OPAQUE_QUEUE.hasQueuedParticles();
    }

    private static void capture(Slot slot) {
        var source = UISurface.currentTarget();
        if (GlStateManager.getBoundFramebuffer() != source.frameBufferId) return;
        capture(slot, source.frameBufferId, source.getDepthTextureId(), source.width, source.height, 0, false);
    }

    private static void capture(Slot slot, int sourceFramebuffer, int sourceTexture,
                                int width, int height, int version, boolean iris) {
        if (width <= 0 || height <= 0 || sourceTexture <= 0) return;
        int framebuffer = GlStateManager.getBoundFramebuffer();
        int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int viewportX = GlStateManager.Viewport.x();
        int viewportY = GlStateManager.Viewport.y();
        int viewportWidth = GlStateManager.Viewport.width();
        int viewportHeight = GlStateManager.Viewport.height();
        int texture = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            int format = slot.target == null ? 0 : slot.target.format;
            if (slot.target == null || sourceTexture != slot.sourceTexture || version != slot.sourceVersion
                    || slot.target.width != width || slot.target.height != height || slot.iris != iris) {
                GlStateManager._bindTexture(sourceTexture);
                format = GlStateManager._getTexLevelParameter(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            }
            if (slot.target == null || slot.target.width != width || slot.target.height != height || slot.target.format != format) {
                if (slot.target != null) slot.target.destroy();
                slot.target = null;
                slot.target = new TerrainDepthTarget(width, height, format);
            }
            slot.target.copyFrom(sourceFramebuffer);
            slot.sourceFramebuffer = sourceFramebuffer;
            slot.sourceTexture = sourceTexture;
            slot.sourceVersion = version;
            slot.iris = iris;
            slot.irisSource = iris ? new IrisDepthSource(sourceFramebuffer, sourceTexture, width, height, version) : null;
            slot.viewportX = viewportX;
            slot.viewportY = viewportY;
            slot.viewportWidth = viewportWidth;
            slot.viewportHeight = viewportHeight;
            slot.level = Minecraft.getInstance().level;
            slot.valid = true;
            copies++;
        } finally {
            GlStateManager._bindTexture(texture);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
        }
    }

    private static final class Slot {
        TerrainDepthTarget target;
        Object level;
        int sourceFramebuffer;
        int sourceTexture;
        int sourceVersion;
        boolean iris;
        IrisDepthSource irisSource;
        int viewportX;
        int viewportY;
        int viewportWidth;
        int viewportHeight;
        boolean valid;
        final TerrainDepthDemand demand = new TerrainDepthDemand();

        void endFrame() {
            valid = false;
            demand.endFrame();
        }
    }
}
