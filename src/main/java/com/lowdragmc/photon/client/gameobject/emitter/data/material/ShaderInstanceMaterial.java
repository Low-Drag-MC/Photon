package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.client.shader.management.ShaderManager;
import com.lowdragmc.lowdraglib2.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.core.HolderLookup;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.nbt.CompoundTag;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * @author KilaBash
 * @date 2023/6/1
 * @implNote ShaderInstanceMaterial
 */
@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
public abstract class ShaderInstanceMaterial implements IMaterial {

    public final ShaderTexture preview = new ShaderTexture();

    abstract public ShaderInstance getShader();

    public void setupUniform() {
    }

    @Override
    public void begin(boolean isInstancing) {
        if (Photon.isUsingShaderPack() && Editor.INSTANCE == null) {
            var lastShader = RenderSystem.getShader();

            ShaderManager.getTempTarget().clear(false);
            ShaderManager.getTempTarget().bindWrite(true);
            int lastID = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

            float imageU = 0;
            float imageV = 0;
            float imageWidth = 1;
            float imageHeight = 1;
            Tesselator tessellator = Tesselator.getInstance();
            BufferBuilder bufferbuilder = tessellator.getBuilder();
            RenderSystem.setShader(this::getShader);
            setupUniform();

            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.DISTANCE_TO_ORIGIN);

            var stack = RenderSystem.getModelViewStack();
            stack.pushPose();
            stack.setIdentity();
            RenderSystem.applyModelViewMatrix();

            var lightTexture = Minecraft.getInstance().gameRenderer.lightTexture();
            lightTexture.turnOnLightLayer();
            bufferbuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);



            bufferbuilder.vertex(-1, -1, 0).uv(imageU, imageV).color(-1).uv2(LightTexture.FULL_BRIGHT).endVertex();
            bufferbuilder.vertex(1, -1, 0).uv(imageU + imageWidth, imageV).color(-1).uv2(LightTexture.FULL_BRIGHT).endVertex();
            bufferbuilder.vertex(1, 1, 0).uv(imageU + imageWidth, imageV + imageHeight).color(-1).uv2(LightTexture.FULL_BRIGHT).endVertex();
            bufferbuilder.vertex(-1, 1, 0).uv(imageU, imageV + imageHeight).color(-1).uv2(LightTexture.FULL_BRIGHT).endVertex();

            tessellator.end();
            lightTexture.turnOffLightLayer();
            RenderSystem.restoreProjectionMatrix();

            stack.popPose();
            RenderSystem.applyModelViewMatrix();

            GlStateManager._glBindFramebuffer(36160, lastID);
            if (!ShaderManager.getInstance().hasViewPort()) {
                var mainTarget = Minecraft.getInstance().getMainRenderTarget();
                GlStateManager._viewport(0, 0, mainTarget.viewWidth, mainTarget.viewHeight);
            }

            RenderSystem.setShaderTexture(0, ShaderManager.getTempTarget().getColorTextureId());
            RenderSystem.setShader(() -> lastShader);
        } else {
            RenderSystem.setShader(this::getShader);
            setupUniform();
        }
    }

    @Override
    public void end(boolean isInstancing) {
    }

    @Override
    public final CompoundTag serializeNBT(HolderLookup.Provider provider) {
        return IMaterial.super.serializeNBT(provider);
    }

    @Override
    public IGuiTexture preview() {
        return preview;
    }

    public class ShaderTexture implements IGuiTexture {

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float x, float y, float width, float height, float partialTicks) {
            //sub area is just different width and height
            float imageU = 0;
            float imageV = 0;
            float imageWidth = 1;
            float imageHeight = 1;
            begin(false);
            var lightTexture = Minecraft.getInstance().gameRenderer.lightTexture();
            lightTexture.turnOnLightLayer();
            var mat = graphics.pose().last().pose();
            var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);

            buffer.addVertex(mat, x, y + height, 0).setUv(imageU, imageV + imageHeight).setColor(-1).setLight(LightTexture.FULL_BRIGHT);
            buffer.addVertex(mat, x + width, y + height, 0).setUv(imageU + imageWidth, imageV + imageHeight).setColor(-1).setLight(LightTexture.FULL_BRIGHT);
            buffer.addVertex(mat, x + width, y, 0).setUv(imageU + imageWidth, imageV).setColor(-1).setLight(LightTexture.FULL_BRIGHT);
            buffer.addVertex(mat, x, y, 0).setUv(imageU, imageV).setColor(-1).setLight(LightTexture.FULL_BRIGHT);
            BufferUploader.drawWithShader(buffer.buildOrThrow());
            lightTexture.turnOffLightLayer();
        }
    }

}
