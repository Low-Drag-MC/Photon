package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigHDR;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "sprite", registry = "photon:material")
public class SpriteMaterial extends ShaderInstanceMaterial {
    @Persisted
    public Identifier spriteLocation = Identifier.parse("");
    @Configurable(name = "TextureMaterial.discardThreshold")
    @ConfigNumber(range = {0, 1})
    protected float discardThreshold = 0.1f;
    @Configurable(name = "TextureMaterial.hdr")
    @ConfigHDR
    protected Vector4f hdr = new Vector4f(0, 0, 0, 1);
    @Configurable(name = "TextureMaterial.hdrMode")
    protected TextureMaterial.HDRMode hdrMode = TextureMaterial.HDRMode.ADDITIVE;

    // TODO(M2): 1.21 getShader/setupUniform bound sprite_hdr_particle (+ #define variants) and set
    // U_SpriteUV/DiscardThreshold/HDR uniforms — rebuilt on the RenderPipeline + std140 path.

    /** 26.1: sprite sets live on {@code ParticleResources} (AT'd public in our accesstransformer.cfg). */
    @Nullable
    private SpriteSet getSpriteSet() {
        if (spriteLocation == null) return null;
        return Minecraft.getInstance().particleEngine.resourceManager.spriteSets.get(spriteLocation);
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        super.buildConfigurator(father);
        father.addConfigurator(new SelectorConfigurator<>("SpriteMaterial.spriteLocation",
                () -> this.spriteLocation, s -> this.spriteLocation = s, Identifier.parse(""),
                true, Minecraft.getInstance().particleEngine.resourceManager.spriteSets.keySet().stream().toList(), Identifier::toString));
    }
}
