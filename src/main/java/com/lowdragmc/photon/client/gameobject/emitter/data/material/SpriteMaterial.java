package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigHDR;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.render.PhotonMaterialUniforms;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;

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
    protected HDRColor hdr = HDRColor.black();
    @Configurable(name = "TextureMaterial.hdrMode")
    protected TextureMaterial.HDRMode hdrMode = TextureMaterial.HDRMode.ADDITIVE;

    /** 26.1: sprite sets live on {@code ParticleResources} (AT'd public in our accesstransformer.cfg). */
    @Nullable
    private SpriteSet getSpriteSet() {
        if (spriteLocation == null) return null;
        return Minecraft.getInstance().particleEngine.resourceManager.spriteSets.get(spriteLocation);
    }


    // 1.21 bound sprite_hdr_particle with U_SpriteUV from the FIRST frame (sprite.get(0, 1)) — the
    // same static remap now travels in the PhotonMaterial UBO's SpriteUV field.
    @Override
    public RenderType getRenderType(
            MaterialSetting setting,
            VertexFormat.Mode mode) {
        var fragment = Photon.id("core/sprite_hdr_particle");
        var spriteSet = getSpriteSet();
        // premultiplied + opaque: the fsh applies HDR.rgb directly now, so the intensity has to be
        // folded in here and the alpha pinned (it carries no meaning for an emission offset)
        var emission = hdr.toVector4fOpaque();
        if (spriteSet == null) {
            // Values.of leaves U_SpriteUV at the identity window (0,0,1,1)
            return MaterialRenderTypes.hdrParticle(
                    MissingTextureAtlasSprite.getLocation(),
                    fragment, setting.pipelineKey(mode),
                    PhotonMaterialUniforms.Values.of(
                            emission, discardThreshold, hdrMode.mode, 0));
        }
        var sprite = spriteSet.get(0, 1);
        return MaterialRenderTypes.hdrParticle(
                sprite.atlasLocation(), fragment, setting.pipelineKey(mode),
                PhotonMaterialUniforms.Values.ofSprite(
                        emission, discardThreshold, hdrMode.mode,
                        sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1()));
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        super.buildConfigurator(father);
        father.addConfigurator(new SelectorConfigurator<>("SpriteMaterial.spriteLocation",
                () -> this.spriteLocation, s -> this.spriteLocation = s, Identifier.parse(""),
                true, Minecraft.getInstance().particleEngine.resourceManager.spriteSets.keySet().stream().toList(), Identifier::toString));
    }
}
