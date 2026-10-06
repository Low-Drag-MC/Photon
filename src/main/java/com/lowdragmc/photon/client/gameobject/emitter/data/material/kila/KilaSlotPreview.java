package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.kilagraph.rendertype.compiler.KGSamplerGl;
import com.lowdragmc.kilagraph.rendertype.runtime.KGSamplerBinder;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.PhotonShaders;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * One texture slot drawn the way the material reads it (channel, colour mode, noise, optionally its uv chain).
 * Screen and world mappings have nothing to map from here, so they show as the mesh would.
 */
@OnlyIn(Dist.CLIENT)
public final class KilaSlotPreview implements IGuiTexture {
    public static final ResourceLocation CHECKER = Photon.id("textures/kila/ui/checker.png");
    private static final int PREVIEW_FLAGS = KilaTexture.FLAG_POLAR | KilaTexture.FLAG_CLIP_U | KilaTexture.FLAG_CLIP_V;

    private final KilaTexture texture;
    private final KilaUI.TextureKind kind;
    private final boolean uvChain;
    private final List<KGSamplerGl.Binding> bindings = new ArrayList<>(1);
    private final float[] scratch = new float[4];

    public KilaSlotPreview(KilaTexture texture, KilaUI.TextureKind kind, boolean uvChain) {
        this.texture = texture;
        this.kind = kind;
        this.uvChain = uvChain;
    }

    @Override
    public void draw(GuiGraphics graphics, float mouseX, float mouseY, float x, float y, float width, float height,
                     float partialTicks) {
        var shader = PhotonShaders.getKilaPreviewShader();
        if (shader == null) return;
        graphics.flush();
        boolean procedural = texture.isProcedural();
        RenderSystem.setShaderTexture(0, procedural ? KilaTextures.WHITE : texture.getTexture());
        if (uvChain) {
            texture.uploadUv(shader, "KilaPreview");
        } else {
            shader.safeGetUniform("KilaPreviewST").set(1f, 1f, 0f, 0f);
            shader.safeGetUniform("KilaPreviewAnim").set(0f, 0f, 0f, 0f);
            shader.safeGetUniform("KilaPreviewPolar").set(0.5f, 0.5f, 1f, 1f);
        }
        int flags = uvChain ? texture.flags() & PREVIEW_FLAGS : 0;
        shader.safeGetUniform("KilaPreviewOpts").set(flags, kind.ordinal(), texture.colorMode.ordinal(), procedural ? 1 : 0);
        texture.channel.weights(scratch);
        shader.safeGetUniform("KilaPreviewChannel").set(scratch[0], scratch[1], scratch[2], scratch[3]);
        shader.safeGetUniform("KilaPreviewNoise").set((float) texture.noise.ordinal(), texture.noiseScale, texture.noiseMotion, 0f);
        bindings.clear();
        bindings.add(new KGSamplerGl.Binding(0, texture.glSampler()));
        KGSamplerBinder.stage(shader, bindings);

        var pose = graphics.pose().last().pose();
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(pose, x, y + height, 0).setUv(0, 1);
        buffer.addVertex(pose, x + width, y + height, 0).setUv(1, 1);
        buffer.addVertex(pose, x + width, y, 0).setUv(1, 0);
        buffer.addVertex(pose, x, y, 0).setUv(0, 0);
        RenderSystem.setShader(() -> shader);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    /** The transparency backdrop: grey squares four pixels across. */
    public static IGuiTexture checker() {
        return (graphics, mouseX, mouseY, x, y, width, height, partialTicks) -> {
            int w = Math.max(1, Math.round(width));
            int h = Math.max(1, Math.round(height));
            graphics.blit(CHECKER, Math.round(x), Math.round(y), w, h, 0, 0, Math.max(1, w / 4), Math.max(1, h / 4), 2, 2);
        };
    }
}
