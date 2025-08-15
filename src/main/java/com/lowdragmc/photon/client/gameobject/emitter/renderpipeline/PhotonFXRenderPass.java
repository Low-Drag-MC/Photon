package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

 import com.lowdragmc.lowdraglib2.client.utils.MeshDataSorter;
 import com.lowdragmc.photon.Photon;
 import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
 import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
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

    public boolean isParallel() {
        return false;
    }

    public void prepareStatus(@Nonnull RenderPassPipeline pipeline) {
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
    }

    public BufferBuilder begin(@Nonnull Tesselator tesselator) {
        return tesselator.begin(mode, format);
    }

    public final void drawParticles(RenderPassPipeline pipeline, Collection<IParticle> particles, Camera camera, float partialTicks) {
        var materials = getMaterials(pipeline);
        if (materials.isEmpty()) return;
        drawParticlesInternal(materials, pipeline, particles, camera, partialTicks);
    }

    protected void drawParticlesInternal(List<MaterialSetting> materials, RenderPassPipeline pipeline, Collection<IParticle> particles, Camera camera, float partialTicks) {
        // prepare mesh data
        var buffer = begin(Tesselator.getInstance());
        for (var particle : particles) {
            particle.render(buffer, camera, partialTicks);
        }
        var meshData = buffer.build();
        if (meshData == null) return;

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
    }

    protected List<MaterialSetting> getMaterials(RenderPassPipeline pipeline) {
        var materials = rendererSetting.getMaterials();
        if (pipeline.getDrawMode() == SceneView.DrawMode.WIREFRAME) {
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
