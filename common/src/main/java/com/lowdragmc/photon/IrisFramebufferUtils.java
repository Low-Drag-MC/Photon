package com.lowdragmc.photon;

import com.lowdragmc.lowdraglib.LDLib;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.SodiumTerrainPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static org.lwjgl.opengl.GL30C.*;

public class IrisFramebufferUtils {
    private static boolean renderingGUIScreen = false;
    private static WorldRenderingPipeline lastPipeline = null;
    private static Field cachedField = null;

    private static int cachedSolidFramebufferId = -1, cachedTranslucentFramebufferId = -1;
    private static int cachedDepthTextureId = -1, cachedSolidTextureId = -1, cachedTranslucentTextureId = -1;
    private static boolean depthTextureRefreshed = false, solidTextureRefreshed = false, translucentTextureRefreshed = false;

    public static int getIrisSolidFboId() {
        return getFramebufferID(true);
    }

    public static int getIrisTranslucentFboId() {
        return getFramebufferID(false);
    }

    private static int getFramebufferID(boolean solid) {
        if (renderingGUIScreen) return Minecraft.getInstance().getMainRenderTarget().frameBufferId;

        Optional<WorldRenderingPipeline> optional = Iris.getPipelineManager().getPipeline();
        if (optional.isEmpty()) return Minecraft.getInstance().getMainRenderTarget().frameBufferId;

        WorldRenderingPipeline pipeline = optional.get();
        if (pipeline != lastPipeline) {
            lastPipeline = pipeline;
            cachedField = null;
            cachedSolidFramebufferId = cachedTranslucentFramebufferId = -1;
            depthTextureRefreshed = solidTextureRefreshed = translucentTextureRefreshed = false;
        }

        int cached = solid ? cachedSolidFramebufferId : cachedTranslucentFramebufferId;
        if (cached != -1) return cached;

        try {
            if (pipeline instanceof IrisRenderingPipeline) {
                if (cachedField == null) {
                    cachedField = IrisRenderingPipeline.class.getDeclaredField("sodiumTerrainPipeline");
                    cachedField.setAccessible(true);
                }
                SodiumTerrainPipeline stp = (SodiumTerrainPipeline) cachedField.get(pipeline);
                GlFramebuffer solidBuffer = stp.getTerrainSolidFramebuffer();
                GlFramebuffer translucentBuffer = stp.getTranslucentFramebuffer();
                cachedSolidFramebufferId = solidBuffer.getId();
                cachedTranslucentFramebufferId = translucentBuffer.getId();
                return solid ? cachedSolidFramebufferId : cachedTranslucentFramebufferId;
            }
        } catch (Exception ignored) {
        }
        return Minecraft.getInstance().getMainRenderTarget().frameBufferId;
    }

    public static int getIrisDepthTextureId() {
        return getTextureIdFromFramebuffer(
                getIrisSolidFboId(),
                GL_DEPTH_ATTACHMENT,
                () -> cachedDepthTextureId,
                id -> cachedDepthTextureId = id,
                () -> depthTextureRefreshed,
                refreshed -> depthTextureRefreshed = refreshed,
                () -> cachedSolidFramebufferId
        );
    }

    public static int getIrisSolidTextureId() {
        return getTextureIdFromFramebuffer(
                getIrisSolidFboId(),
                GL_COLOR_ATTACHMENT0,
                () -> cachedSolidTextureId,
                id -> cachedSolidTextureId = id,
                () -> solidTextureRefreshed,
                refreshed -> solidTextureRefreshed = refreshed,
                () -> cachedSolidFramebufferId
        );
    }

    public static int getIrisTranslucentTextureId() {
        return getTextureIdFromFramebuffer(
                getIrisTranslucentFboId(),
                GL_COLOR_ATTACHMENT0,
                () -> cachedTranslucentTextureId,
                id -> cachedTranslucentTextureId = id,
                () -> translucentTextureRefreshed,
                refreshed -> translucentTextureRefreshed = refreshed,
                () -> cachedTranslucentFramebufferId
        );
    }

    private static int getTextureIdFromFramebuffer(
            int framebufferId,
            int attachment,
            IntSupplier cacheGetter,
            Consumer<Integer> cacheSetter,
            BooleanSupplier refreshedGetter,
            Consumer<Boolean> refreshedSetter,
            IntSupplier cachedFramebufferGetter
    ) {
        if (renderingGUIScreen) {
            return attachment == GL_DEPTH_ATTACHMENT
                    ? Minecraft.getInstance().getMainRenderTarget().getDepthTextureId()
                    : Minecraft.getInstance().getMainRenderTarget().getColorTextureId();
        }

        if (cachedFramebufferGetter.getAsInt() != framebufferId || !refreshedGetter.getAsBoolean()) {
            refreshedSetter.accept(true);
            cacheSetter.accept(attachment == GL_DEPTH_ATTACHMENT
                    ? Minecraft.getInstance().getMainRenderTarget().getDepthTextureId()
                    : Minecraft.getInstance().getMainRenderTarget().getColorTextureId());

            RenderSystem.assertOnRenderThreadOrInit();
            GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, framebufferId);

            int[] type = new int[1];
            glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER, attachment, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE, type);
            if (type[0] == GL_TEXTURE) {
                int[] id = new int[1];
                glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER, attachment, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, id);
                cacheSetter.accept(id[0]);
            }
        }

        return cacheGetter.getAsInt();
    }

    public static Field getFboCachedField() {
        if (isUsingShaderPack()) {
            IrisFramebufferUtils.getIrisSolidFboId();
            IrisFramebufferUtils.getIrisTranslucentFboId();
        }
        return cachedField;
    }

    public static boolean isUsingShaderPack() {
        return (LDLib.isModLoaded("iris") || LDLib.isModLoaded("oculus")) && IrisApi.getInstance().isShaderPackInUse();
    }

    public static boolean isRenderingGUIScreen() {
        return renderingGUIScreen;
    }

    public static void setRenderingGUIScreen(boolean renderingGUIScreen) {
        IrisFramebufferUtils.renderingGUIScreen = renderingGUIScreen;
    }
}