package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinPath;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PremultipliedBlendPlan;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
import com.mojang.blaze3d.systems.RenderSystem;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import java.util.Optional;

@OnlyIn(Dist.CLIENT)
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

    /**
     * Apply this material's GL state.
     *
     * <p>The blend half is asked of the pipeline rather than passed in: under a shader pack — and on
     * the {@code FXCompositeMode.LATE} path — Photon draws into a transparent premultiplied
     * accumulator, where the alpha channel has to carry coverage instead of whatever the author
     * wrote (see {@link PremultipliedBlendPlan}). Reading it from
     * {@link RenderPassPipeline#getCurrent()} — the same seam materials already use for scene
     * samplers — means a new renderer cannot forget to opt in and silently punch a hole in the
     * pack's translucent layer.
     */
    public void pre() {
        var pipeline = RenderPassPipeline.getCurrent();
        if (pipeline != null && pipeline.isPremultipliedAccumulation()) {
            PremultipliedBlendPlan.applyPremultiplied(blendMode);
        } else {
            blendMode.apply();
        }
        if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
        if (depthTest) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        RenderSystem.depthMask(depthMask);
    }

    public void post() {
        if (blendMode.getBlendFunc() != BlendMode.BlendFuc.ADD) {
            RenderSystem.blendEquation(BlendMode.BlendFuc.ADD.op);
        }
        blendMode.reset();
        if (!cull) RenderSystem.enableCull();
        if (!depthTest) RenderSystem.enableDepthTest();
        if (!depthMask) RenderSystem.depthMask(true);
    }

}
