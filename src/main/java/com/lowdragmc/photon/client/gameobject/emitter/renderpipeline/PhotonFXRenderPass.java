package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

import com.lowdragmc.photon.client.PhotonShaders;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.gui.editor.view.SceneView;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL30;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collection;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote IPhotonParticleRenderType
 */
@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
public abstract class PhotonFXRenderPass {
    public RendererSetting rendererSetting;
    public MaterialSetting materialSetting;

    public PhotonFXRenderPass(RendererSetting rendererSetting, MaterialSetting materialSetting) {
        this.rendererSetting = rendererSetting;
        this.materialSetting = materialSetting;
    }

    public boolean isParallel() {
        return false;
    }

    /**
     * setup opengl environment, setup shaders, uniforms.
     */
    public void prepareStatus(@Nonnull RenderPassPipeline pipeline) {
        materialSetting.pre();
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
    }

    public abstract BufferBuilder begin(Tesselator tesselator);

    public void drawParticles(RenderPassPipeline pipeline, Collection<IParticle> particles, Camera camera, float partialTicks) {
        var tesselator = Tesselator.getInstance();
        var sorting = getSorting();
        var buffer = begin(tesselator);

        IMaterial material;
        if (pipeline.getDrawMode() == SceneView.DrawMode.WIREFRAME) {
            material = CustomShaderMaterial.INVERSE;
        } else {
            material = materialSetting.getMaterial();
        }
        var shader = material.begin(MaterialContext.NORMAL);

        RenderSystem.setShader(() -> shader);
        for (var particle : particles) {
            particle.render(buffer, camera, partialTicks);
        }

        var data = buffer.build();
        if (data != null) {
            if (sorting != null) {
                data.sortQuads(pipeline.getSortingBuffer(), sorting);
            }
            BufferUploader.drawWithShader(data);
        }

        material.end(MaterialContext.NORMAL);
    }

    public void onEmpty() {

    }

    /**
     * restore opengl environment.
     */
    public void releaseStatus(@Nonnull RenderPassPipeline pipeline) {
        materialSetting.post();
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
