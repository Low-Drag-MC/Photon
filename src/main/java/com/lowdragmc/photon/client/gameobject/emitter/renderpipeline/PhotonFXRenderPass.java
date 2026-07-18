package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

 import com.lowdragmc.lowdraglib2.client.utils.MeshDataSorter;
 import com.lowdragmc.photon.Photon;
 import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
 import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
 import com.lowdragmc.photon.client.gameobject.emitter.data.material.*;
 import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
 import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote IPhotonParticleRenderType
 */
@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
public abstract class PhotonFXRenderPass {
    public final static CustomShaderMaterial INVERSE = new CustomShaderMaterial(Photon.id("inverse"));
    protected static final MaterialSetting WIREFRAME_MATERIAL = new MaterialSetting();
    /** The mask value (0..1) of the pass currently drawing in the mask sub-pass — staged into the
     *  shared mask shader by {@link #MASK}'s begin() (covers the CPU and every instanced path). */
    private static float CURRENT_MASK_VALUE = 0f;
    /** Alpha-clip cutoff of the current mask draw (0 = flat geometry mask). */
    private static float CURRENT_MASK_CUTOFF = 0f;
    /** The texture the alpha clip samples (the pass's first texture material), null = none. */
    @Nullable
    private static net.minecraft.resources.ResourceLocation CURRENT_MASK_TEXTURE = null;
    /** The mask sub-pass material: flat {@code MaskValue} output over the shared particle vertex
     *  transform ({@code getParticleData()}), variant-selected per render path like INVERSE. */
    public final static CustomShaderMaterial MASK = new CustomShaderMaterial(Photon.id("mask")) {
        @Override
        public net.minecraft.client.renderer.ShaderInstance begin(MaterialContext context) {
            var shader = super.begin(context);
            shader.safeGetUniform("MaskValue").set(CURRENT_MASK_VALUE);
            shader.safeGetUniform("AlphaCutoff").set(CURRENT_MASK_CUTOFF);
            if (CURRENT_MASK_TEXTURE != null) {
                // Sampler0 rides RenderSystem's shader-texture slot: the CPU path pulls it in
                // drawWithShader, the instanced path in setDefaultUniforms — same as TextureMaterial
                RenderSystem.setShaderTexture(0, CURRENT_MASK_TEXTURE);
            }
            return shader;
        }
    };
    protected static final MaterialSetting MASK_MATERIAL = new MaterialSetting();
    static {
        WIREFRAME_MATERIAL.setMaterial(INVERSE);
        WIREFRAME_MATERIAL.setCull(false);
        WIREFRAME_MATERIAL.setDepthMask(false);
        WIREFRAME_MATERIAL.setDepthTest(false);
        // mask draws depth-test against the scene depth pre-copied into MASK_TARGET and WRITE their
        // own depth there (= custom depth) — the main depth buffer is never touched
        MASK_MATERIAL.setMaterial(MASK);
        MASK_MATERIAL.setCull(false);
        MASK_MATERIAL.setDepthMask(true);
        MASK_MATERIAL.setDepthTest(true);
    }

    /** The per-emitter render-override runtime this pass draws with (config.renderer's default runtime for
     *  the shared pass, an emitter's overriding runtime for an override pass). Its <b>effective</b> values
     *  (slot-or-config) are the batching key — see {@link #equals}/{@link #hashCode}. */
    public final RendererSetting.Runtime renderer;
    public final VertexFormat.Mode mode;
    public final VertexFormat format;

    public PhotonFXRenderPass(RendererSetting.Runtime renderer, VertexFormat.Mode mode, VertexFormat format) {
        this.renderer = renderer;
        this.mode = mode;
        this.format = format;
    }

    public void prepareStatus(@Nonnull RenderPassPipeline pipeline) {
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
    }

    public BufferBuilder begin(@Nonnull Tesselator tesselator) {
        return tesselator.begin(mode, format);
    }

    /**
     * Draw this pass's queued particles. Returns whether anything was actually drawn
     * (used by the pipeline to decide whether the scene sampler became stale).
     */
    public final boolean drawParticles(RenderPassPipeline pipeline, Collection<IParticle> particles, Camera camera, float partialTicks) {
        var materials = getMaterials(pipeline);
        if (materials.isEmpty()) return false;
        if (pipeline.isMaskSubPass()) {
            CURRENT_MASK_VALUE = com.lowdragmc.photon.client.postfx.runtime.MaskGroups
                    .idOf(renderer.getMaskGroup()) / 255f;
            var cutoff = renderer.getMaskAlphaCutoff();
            CURRENT_MASK_TEXTURE = cutoff > 0 ? findMaskClipTexture() : null;
            // no clippable texture on the pass -> fall back to the flat geometry mask
            CURRENT_MASK_CUTOFF = CURRENT_MASK_TEXTURE != null ? cutoff : 0f;
        }
        return drawParticlesInternal(materials, pipeline, particles, camera, partialTicks);
    }

    protected final boolean drawParticlesInternal(List<MaterialSetting> materials, RenderPassPipeline pipeline, Collection<IParticle> particles, Camera camera, float partialTicks) {
        if (useInstancing()) {
            return drawInstanced(materials, pipeline, particles, camera, partialTicks);
        }

        // prepare mesh data
        var buffer = begin(Tesselator.getInstance());
        renderQueue(buffer, particles, camera, partialTicks);
        var meshData = buffer.build();
        if (meshData == null) return false;

        // sort quads if necessary
        var sorting = getSorting();
        if (sorting != null) {
            var result = MeshDataSorter.sortPrimitives(meshData, pipeline.getSortingBuffer(), sorting);
            if (result != null) {
                result.applyTo(meshData);
            }
        }

        // upload to vbo
        var vbo = uploadFormatVbo(meshData);

        // render materials
        for (var materialSetting : materials) {
            materialSetting.pre();
            renderWithMaterial(materialSetting.getMaterial(), MaterialContext.NORMAL, vbo);
            materialSetting.post();
        }

        // invalidate cache
        BufferUploader.invalidate();
        return true;
    }

    /**
     * Geometry-emission seam of the CPU path: emit vertices for the queued particles
     * (all of this pass's particle type) into the tesselator buffer. Implementations
     * delegate to their particle-type renderer.
     */
    protected abstract void renderQueue(VertexConsumer buffer, Collection<IParticle> particles, Camera camera, float partialTicks);

    /**
     * Whether this pass draws via GPU instancing this frame. Per-frame decision;
     * MUST NOT participate in equals/hashCode (the batching key stays
     * rendererSetting + mode + format).
     */
    protected boolean useInstancing() {
        return false;
    }

    /**
     * Instanced draw path; only called when {@link #useInstancing()}. Returns whether
     * anything was drawn. Default: no instancing support.
     */
    protected boolean drawInstanced(List<MaterialSetting> materials, RenderPassPipeline pipeline, Collection<IParticle> particles, Camera camera, float partialTicks) {
        return false;
    }

    /**
     * Tear down this pass's instanced GL resources (render mode / model / instance layout
     * changed). No-op for passes without an instanced path.
     */
    public void clearInstance() {
    }

    /**
     * Union of the additional-data channels required by the pass's shadergraph materials —
     * fed into {@code AdditionalGPUDataSetting.setMaterialMask} so instanced passes auto-enable
     * whatever their graphs read (hand-written shader materials toggle channels manually).
     */
    protected static long shaderGraphChannelMask(List<MaterialSetting> materials) {
        long mask = 0;
        for (var materialSetting : materials) {
            if (getRawMaterial(materialSetting.getMaterial()) instanceof ShaderGraphMaterial shaderGraphMaterial) {
                mask |= shaderGraphMaterial.getUsedChannelMask();
            }
        }
        return mask;
    }

    /**
     * Whether any shadergraph material on the pass reads user custom data (a {@code CustomDataNode}) —
     * fed into {@code AdditionalGPUDataSetting.setCustomDataMaterialUsed} so instanced passes upload the
     * {@code PhotonCustomData} buffer texture only when needed (custom shaders read custom data through
     * their appended vertex attributes instead).
     */
    protected static boolean shaderGraphUsesCustomData(List<MaterialSetting> materials) {
        for (var materialSetting : materials) {
            if (getRawMaterial(materialSetting.getMaterial()) instanceof ShaderGraphMaterial shaderGraphMaterial
                    && shaderGraphMaterial.usesCustomData()) {
                return true;
            }
        }
        return false;
    }

    /** The texture the mask alpha clip samples: the pass's first texture material's texture. */
    @Nullable
    private net.minecraft.resources.ResourceLocation findMaskClipTexture() {
        for (var materialSetting : renderer.getMaterials()) {
            if (getRawMaterial(materialSetting.getMaterial()) instanceof TextureMaterial textureMaterial) {
                return textureMaterial.getTexture();
            }
        }
        return null;
    }

    private static IMaterial getRawMaterial(IMaterial material) {
        if (material instanceof UIResourceMaterial uiResourceMaterial) {
            return uiResourceMaterial.getRawMaterial();
        }
        return material;
    }

    protected List<MaterialSetting> getMaterials(RenderPassPipeline pipeline) {
        if (pipeline.isMaskSubPass()) {
            // only flagged passes participate in the mask sub-pass (empty = skipped entirely)
            return renderer.isWriteCustomMask() ? List.of(MASK_MATERIAL) : List.of();
        }
        var materials = renderer.getMaterials();
        if (pipeline.isWireframeSubPass()) {
            materials = List.of(WIREFRAME_MATERIAL);
        }
        return materials;
    }

    protected static VertexBuffer uploadFormatVbo(MeshData meshData) {
        var vbo = meshData.drawState().format().getImmediateDrawVertexBuffer();
        vbo.bind();
        vbo.upload(meshData);
        return vbo;
    }

    protected void renderWithMaterial(IMaterial material, MaterialContext context, VertexBuffer vbo) {
        var shader = material.begin(context);
        RenderSystem.setShader(() -> shader);
        vbo.drawWithShader(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), shader);
        material.end(context);
    }

    /**
     * restore opengl environment.
     */
    public void releaseStatus(@Nonnull RenderPassPipeline pipeline) {
    }

    /**
     * Retrieves the rendering layer order associated with this render pass.
     * The layer order is used to determine the rendering sequence of different layers.
     *
     * @return the order of the layer as an integer, where lower values typically indicate earlier rendering.
     */
    public int layerOrder() {
        return renderer.getOrderInLayer();
    }

    /**
     * Retrieves the vertex sorting configuration for the current rendering pass.
     * The vertex sorting defines the order in which vertices are rendered,
     * which can influence visual effects and rendering performance.
     *
     * @return the VertexSorting configuration, or null if no sorting is defined.
     */
    public @Nullable VertexSorting getSorting() {
        return renderer.getVertexSortingMode().getVertexSorting();
    }

    /**
     * The batching key: two passes merge iff same {@code mode} + {@code format} and equal <b>effective</b>
     * renderer values ({@link RendererSetting.Runtime#effectiveEquals}). Hand-written (was lombok over the
     * config {@code RendererSetting}) so the key reflects the runtime's slot-or-config values. Concrete
     * passes further gate on their own type via {@code o instanceof RenderPass && super.equals}.
     */
    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof PhotonFXRenderPass that)) return false;
        return mode == that.mode && format.equals(that.format) && renderer.effectiveEquals(that.renderer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(renderer.effectiveHashCode(), mode, format);
    }
}
