package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinPath;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import java.util.Optional;

@Getter
@Setter
@Accessors(chain = true)
@EqualsAndHashCode
public class MaterialSetting implements IConfigurable, IPersistedSerializable {
    @Nonnull
    @Configurable(name = "material")
    protected IMaterial material;
    @Configurable(name = "MaterialSetting.blendMode", subConfigurable = true)
    protected final BlendMode blendMode = new BlendMode();
    @Configurable
    protected boolean cull = true;
    @Configurable
    protected boolean depthTest = true;
    @Configurable
    protected boolean depthMask = false;

    public MaterialSetting() {
        this(Optional.ofNullable(MaterialResource.INSTANCE.getResourceInstance().getResource(new BuiltinPath("circle"))).orElseGet(TextureMaterial::new));
    }

    public MaterialSetting(@Nonnull IMaterial material) {
        this.material = material;
    }

    // 1.21's pre()/post() applied blend/cull/depth as imperative RenderSystem calls; in 26.1 they
    // are pipeline properties, so this setting contributes the pipeline-variant key instead.
    public PhotonPipelines.ParticlePipelineKey pipelineKey(
            com.mojang.blaze3d.vertex.VertexFormat.Mode mode) {
        var blend = blendMode.toBlendFunction();
        var equation = blend != null && blendMode.getBlendFunc() != null
                ? blendMode.getBlendFunc().op
                : PhotonPipelines.BLEND_EQUATION_ADD;
        return new PhotonPipelines.ParticlePipelineKey(blend, equation, cull, depthTest, depthMask, mode, false);
    }

    /** The RenderType this material slot draws with (material + this setting's pipeline state +
     *  the emitter's primitive mode). */
    @Nullable
    public net.minecraft.client.renderer.rendertype.RenderType getRenderType(
            com.mojang.blaze3d.vertex.VertexFormat.Mode mode) {
        return material.getRenderType(this, mode);
    }

}
