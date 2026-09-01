package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.client.shader.LDProgramDefineManager;
import com.lowdragmc.lowdraglib2.client.shader.LDShaderInstance;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigHDR;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.PhotonShaders;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import java.util.HashMap;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "sprite", registry = "photon:material")
public class SpriteMaterial extends ShaderInstanceMaterial {
    @Persisted
    public ResourceLocation spriteLocation = ResourceLocation.parse("");
    @Configurable(name = "TextureMaterial.discardThreshold")
    @ConfigNumber(range = {0, 1})
    protected float discardThreshold = 0.1f;
    @Configurable(name = "TextureMaterial.hdr")
    @ConfigHDR
    protected HDRColor hdr = HDRColor.black();
    @Configurable(name = "TextureMaterial.hdrMode")
    protected TextureMaterial.HDRMode hdrMode = TextureMaterial.HDRMode.ADDITIVE;
    private static final Map<String, ShaderInstance> spriteHDRParticleShaders = new HashMap<>();

    @Nullable
    private SpriteSet getSpriteSet() {
        if (spriteLocation == null) return null;
        return Minecraft.getInstance().particleEngine.spriteSets.get(spriteLocation);
    }

    @Override
    public ShaderInstance getShader(@Nonnull MaterialContext context) {
        // The whole define SET matters, not just the path: PHOTON_TANGENT changes the mesh attribute
        // layout, and every material on a pass shares one VAO — compiling this one without it would
        // read brightness out of the tangent slot.
        var defines = context.getShaderDefines();
        if (defines.isEmpty()) {
            return PhotonShaders.getSpriteHDRParticleShader();
        } else {
            return spriteHDRParticleShaders.computeIfAbsent(context.getVariantKey(), key -> {
                // remove cache
                Program.Type.FRAGMENT.getPrograms().remove(PhotonShaders.getSpriteHDRParticleShader().getFragmentProgram().getName());
                Program.Type.VERTEX.getPrograms().remove(PhotonShaders.getSpriteHDRParticleShader().getVertexProgram().getName());
                defines.forEach(LDProgramDefineManager::addProgramDefine);
                try {
                    return LDShaderInstance.create(Photon.id("sprite_hdr_particle"), DefaultVertexFormat.BLOCK);
                } catch (Throwable e) {
                    Photon.LOGGER.error("Failed to create sprite HDR particle shader", e);
                    throw new RuntimeException(e);
                } finally {
                    // finally, not after the try: a throw used to leak the define into every later compile
                    defines.forEach(LDProgramDefineManager::removeProgramDefine);
                }
            });
        }
    }

    @Override
    public void setupUniform(MaterialContext context) {
        var shader = getShader(context);
        var sprite = getSpriteSet();
        if (sprite == null) {
            RenderSystem.setShaderTexture(0, TextureManager.INTENTIONAL_MISSING_TEXTURE);
            shader.safeGetUniform("U_SpriteUV").set(0f, 0f, 1f, 1f);
        } else {
            var spriteTexture = sprite.get(0, 1);
            RenderSystem.setShaderTexture(0, spriteTexture.atlasLocation());
            shader.safeGetUniform("U_SpriteUV").set(spriteTexture.getU0(), spriteTexture.getV0(), spriteTexture.getU1(), spriteTexture.getV1());
        }
        shader.safeGetUniform("DiscardThreshold").set(discardThreshold);
        // the preview thumbnail drops the intensity so a bright emissive material doesn't blow it out
        var color = context.isRenderingPreview() ? hdr.withIntensity(1f).toVector4fOpaque() : hdr.toVector4fOpaque();
        shader.safeGetUniform("HDR").set(color.x, color.y, color.z, color.w);
        shader.safeGetUniform("HDRMode").set(hdrMode.mode);
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        super.buildConfigurator(father);
        father.addConfigurator(new SelectorConfigurator<>("SpriteMaterial.spriteLocation",
                () -> this.spriteLocation, s -> this.spriteLocation = s, ResourceLocation.parse(""),
                true, Minecraft.getInstance().particleEngine.spriteSets.keySet().stream().toList(), ResourceLocation::toString));
    }
}
