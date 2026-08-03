package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinPath;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PremultipliedBlendPlan;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
import com.mojang.blaze3d.vertex.VertexFormat;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.client.renderer.rendertype.RenderType;
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
            VertexFormat.Mode mode) {
        var blend = blendMode.toBlendFunction();
        var equation = blend != null && blendMode.getBlendFunc() != null
                ? blendMode.getBlendFunc().op
                : PhotonPipelines.BLEND_EQUATION_ADD;
        var key = new PhotonPipelines.ParticlePipelineKey(blend, equation, cull, depthTest, depthMask, mode, false);
        // Drawing into a transparent layer rather than onto the scene changes what the alpha half of the
        // blend must do — see PremultipliedBlendPlan. Consulted here, at the one place every material's
        // pipeline is chosen, so no draw path can bypass it.
        return PremultipliedBlendPlan.accumulating()
                ? PremultipliedBlendPlan.premultiply(key) : key;
    }

    /** The RenderType this material slot draws with (material + this setting's pipeline state +
     *  the emitter's primitive mode). */
    @Nullable
    public RenderType getRenderType(
            VertexFormat.Mode mode) {
        return material.getRenderType(this, mode);
    }

}
