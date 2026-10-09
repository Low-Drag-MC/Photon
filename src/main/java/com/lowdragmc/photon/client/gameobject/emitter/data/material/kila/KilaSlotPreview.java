package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.AutoCloseCleaner;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.render.MaterialPreviewRenderer;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PhotonRenderTypes;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * One texture slot drawn the way the material reads it (channel, colour mode, noise, optionally its uv chain).
 * Screen and world mappings have nothing to map from here, so they show as the mesh would.
 * <p>
 * 26.1 draws the GUI later, from a list, with no room for a program of one's own; so the slot renders off screen
 * through {@link MaterialPreviewRenderer}, as a material thumbnail does, and this shows the result.
 */
public final class KilaSlotPreview implements GuiTexture {
    public static final Identifier CHECKER = Photon.id("textures/kila/ui/checker.png");
    private static final int PREVIEW_FLAGS = KilaTexture.FLAG_POLAR | KilaTexture.FLAG_CLIP_U | KilaTexture.FLAG_CLIP_V;
    private static final String BLOCK = "KilaSlot";
    /** ⚠️ MIRRORED IN {@code photon:kila_preview.fsh}: six vec4. */
    private static final int BLOCK_SIZE = 6 * 16;

    @Nullable
    private static RenderPipeline pipeline;

    private final SlotMaterial material;
    private final GuiTexture drawn;

    /** {@code uvChain}: an inspector field, drawn live; otherwise a picker cell, one of many cached tiles. */
    public KilaSlotPreview(KilaTexture texture, KilaUI.TextureKind kind, boolean uvChain) {
        this.material = new SlotMaterial(texture, kind, uvChain);
        this.drawn = uvChain ? MaterialPreviewRenderer.livePreviewOf(material) : MaterialPreviewRenderer.previewOf(material);
    }

    @Override
    public void draw(GUIContext context, float x, float y, float width, float height) {
        drawn.draw(context, x, y, width, height);
    }

    /** The transparency backdrop: grey squares four pixels across. */
    public static IGuiTexture checker() {
        return GuiTexture.of((context, x, y, width, height) -> context.blit(RenderPipelines.GUI_TEXTURED, CHECKER,
                x, y, 0, 0, width, height, Math.max(1, width / 4), Math.max(1, height / 4), 2, 2, -1));
    }

    private static RenderPipeline pipeline() {
        if (pipeline == null) {
            pipeline = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(Photon.id("pipeline/kila_slot_preview"))
                    .withVertexShader(Photon.id("core/kila_preview"))
                    .withFragmentShader(Photon.id("core/kila_preview"))
                    .withShaderDefine("KILA_SLOT_PREVIEW")
                    .withUniform(BLOCK, UniformType.UNIFORM_BUFFER)
                    .withSampler("Sampler0")
                    .withVertexFormat(PhotonPipelines.PARTICLE_FORMAT, VertexFormat.Mode.QUADS)
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    .withCull(false)
                    .build();
        }
        return pipeline;
    }

    /** What the preview renderer draws: the slot alone, through its own small program. Never registered or saved. */
    private static final class SlotMaterial implements IMaterial {
        private final KilaTexture texture;
        private final KilaUI.TextureKind kind;
        private final boolean uvChain;
        @Nullable
        private SlotState state;

        SlotMaterial(KilaTexture texture, KilaUI.TextureKind kind, boolean uvChain) {
            this.texture = texture;
            this.kind = kind;
            this.uvChain = uvChain;
        }

        @Override
        public IGuiTexture preview() {
            return IGuiTexture.EMPTY;
        }

        @Override
        public RenderType getPreviewRenderType(MaterialSetting setting, VertexFormat.Mode mode) {
            if (state == null) {
                state = new SlotState(texture, kind, uvChain);
                AutoCloseCleaner.registerRenderThread(this, state);
            }
            return state.renderType;
        }

        @Override
        public RenderType getRenderType(MaterialSetting setting, VertexFormat.Mode mode) {
            return getPreviewRenderType(setting, mode);
        }
    }

    /** The block and RenderType of one slot preview; nothing of the material, so its Cleaner can fire. */
    private static final class SlotState implements AutoCloseable, PhotonRenderTypes.MaterialBinder {
        private final KilaTexture texture;
        private final KilaUI.TextureKind kind;
        private final boolean uvChain;
        private final GpuBuffer buffer;
        private final RenderType renderType;
        private final float[] scratch = new float[4];
        @Nullable
        private GpuTextureView view;
        @Nullable
        private GpuSampler sampler;

        SlotState(KilaTexture texture, KilaUI.TextureKind kind, boolean uvChain) {
            this.texture = texture;
            this.kind = kind;
            this.uvChain = uvChain;
            this.buffer = RenderSystem.getDevice().createBuffer(() -> "Kila slot preview",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, BLOCK_SIZE);
            this.renderType = PhotonRenderTypes.createMaterialOwned("photon_kila_slot", new PhotonRenderTypes.PhotonDrawInfo(
                    new PhotonRenderTypes.PhotonDrawInfo.Programs(pipeline()),
                    new PhotonRenderTypes.PhotonDrawInfo.Bindings(Map.of(), List.of(), null, null, this),
                    null), false);
        }

        @Override
        public void prepare() {
            boolean procedural = texture.isProcedural();
            int flags = uvChain ? texture.flags() & PREVIEW_FLAGS : 0;
            texture.channel.weights(scratch);
            try (var stack = MemoryStack.stackPush()) {
                var bytes = stack.malloc(BLOCK_SIZE);
                var block = Std140Builder.intoBuffer(bytes);
                if (uvChain) {
                    block.putVec4(texture.tiling.x, texture.tiling.y, texture.offset.x, texture.offset.y)
                            .putVec4(texture.scroll.x, texture.scroll.y, (float) Math.toRadians(texture.rotation),
                                    (float) Math.toRadians(texture.rotationSpeed))
                            .putVec4(texture.polarCenter.x, texture.polarCenter.y, texture.polarRadial, texture.polarAngular);
                } else {
                    block.putVec4(1, 1, 0, 0).putVec4(0, 0, 0, 0).putVec4(0.5f, 0.5f, 1, 1);
                }
                block.putIVec4(flags, kind.ordinal(), texture.colorMode.ordinal(), procedural ? 1 : 0)
                        .putVec4(scratch[0], scratch[1], scratch[2], scratch[3])
                        .putVec4(texture.noise.ordinal(), texture.noiseScale, texture.noiseMotion, 0);
                bytes.rewind();
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), bytes);
            }
            // the first use of a texture uploads it: here, before the pass opens
            view = Minecraft.getInstance().getTextureManager()
                    .getTexture(procedural ? KilaTextures.WHITE : texture.getTexture()).getTextureView();
            sampler = KilaSamplers.of(texture);
        }

        @Override
        public void bind(RenderPass pass) {
            pass.setUniform(BLOCK, buffer.slice());
            if (view != null) pass.bindTexture("Sampler0", view, sampler);
        }

        @Override
        public void close() {
            PhotonRenderTypes.dropCustomShader(renderType);
            buffer.close();
        }
    }
}
