package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

 import com.lowdragmc.lowdraglib2.client.utils.MeshDataSorter;
 import com.lowdragmc.photon.Photon;
 import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
 import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
 import com.lowdragmc.photon.client.gameobject.emitter.data.material.*;
 import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
 import lombok.EqualsAndHashCode;
 import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collection;
 import java.util.List;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote IPhotonParticleRenderType
 */
@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public abstract class PhotonFXRenderPass {
    public final static CustomShaderMaterial INVERSE = new CustomShaderMaterial(Photon.id("inverse"));
    protected static final MaterialSetting WIREFRAME_MATERIAL = new MaterialSetting();
    static {
        WIREFRAME_MATERIAL.setMaterial(INVERSE);
        WIREFRAME_MATERIAL.setCull(false);
        WIREFRAME_MATERIAL.setDepthMask(false);
        WIREFRAME_MATERIAL.setDepthTest(false);
    }

    @EqualsAndHashCode.Include
    public final RendererSetting rendererSetting;
    @EqualsAndHashCode.Include
    public final VertexFormat.Mode mode;
    @EqualsAndHashCode.Include
    public final VertexFormat format;

    public PhotonFXRenderPass(RendererSetting rendererSetting, VertexFormat.Mode mode, VertexFormat format) {
        this.rendererSetting = rendererSetting;
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

    private static IMaterial getRawMaterial(IMaterial material) {
        if (material instanceof UIResourceMaterial uiResourceMaterial) {
            return uiResourceMaterial.getRawMaterial();
        }
        return material;
    }

    protected List<MaterialSetting> getMaterials(RenderPassPipeline pipeline) {
        var materials = rendererSetting.getMaterials();
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
        return rendererSetting.getOrderInLayer();
    }

    /**
     * Retrieves the vertex sorting configuration for the current rendering pass.
     * The vertex sorting defines the order in which vertices are rendered,
     * which can influence visual effects and rendering performance.
     *
     * @return the VertexSorting configuration, or null if no sorting is defined.
     */
    public @Nullable VertexSorting getSorting() {
        return rendererSetting.getVertexSortingMode().getVertexSorting();
    }
}
