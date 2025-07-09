package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.configurator.ConfiguratorParser;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigHDR;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.gui.ui.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.PhotonShaders;
import com.lowdragmc.photon.client.gameobject.emitter.data.ToggleGroup;
import com.mojang.blaze3d.systems.RenderSystem;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.appliedenergistics.yoga.YogaAlign;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.File;

@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "texture", registry = "photon:material")
@Setter
@Getter
public class TextureMaterial extends ShaderInstanceMaterial {
    public enum HDRMode {
        ADDITIVE(0),
        MULTIPLICATIVE(1);
        public final int mode;

        HDRMode(int mode) {
            this.mode = mode;
        }
    }

    public static class PixelArt extends ToggleGroup {
        @Configurable
        @ConfigNumber(range = {1, Integer.MAX_VALUE})
        public int bits = 8;
    }

    @Configurable
    protected ResourceLocation texture = ResourceLocation.parse("textures/particle/glow.png");
    @Configurable
    @ConfigNumber(range = {0, 1})
    protected float discardThreshold = 0.01f;
    @Configurable
    @ConfigHDR
    protected Vector4f hdr = new Vector4f(0, 0, 0, 1);
    @Configurable
    protected HDRMode hdrMode = HDRMode.ADDITIVE;
    @Configurable(subConfigurable = true)
    protected final PixelArt pixelArt = new PixelArt();

    public TextureMaterial() {
    }

    public TextureMaterial(ResourceLocation texture) {
        this.texture = texture;
    }

    @Override
    public IMaterial copy() {
        var mat = new TextureMaterial(texture);
        mat.discardThreshold = discardThreshold;
        return mat;
    }

    @Override
    public ShaderInstance getShader() {
        return pixelArt.isEnable() ? PhotonShaders.getPixelHDRParticleShader() : PhotonShaders.getHDRParticleShader();
    }

    @Override
    public void setupUniform(MaterialContext context) {
        RenderSystem.setShaderTexture(0, texture);
        var shader = getShader();
        shader.safeGetUniform("DiscardThreshold").set(discardThreshold);
        if (context.isRenderingPreview()) {
            shader.safeGetUniform("HDR").set(hdr.x, hdr.y, hdr.z, 1);
            shader.safeGetUniform("HDRMode").set(hdrMode.mode);
        } else {
            shader.safeGetUniform("HDR").set(hdr.x, hdr.y, hdr.z, hdr.w);
            shader.safeGetUniform("HDRMode").set(hdrMode.mode);
        }
        if (pixelArt.isEnable()) {
            shader.safeGetUniform("Bits").set(pixelArt.bits * 1f);
        }
    }

    @Override
    public void begin(MaterialContext context) {
        // TODO better shader pack support
        if (Photon.isUsingShaderPack()) {
            RenderSystem.setShaderTexture(0, texture);
        } else {
            RenderSystem.setShader(this::getShader);
            setupUniform(context);
        }
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        createPreview(father);

        var configurator = new Configurator();
        father.addConfigurators(configurator
                .addInlineChild(
                        // button to select an image
                        new Button().setText("ldlib.gui.editor.tips.select_image").setOnClick(e -> {
                            var mui = e.currentElement.getModularUI();
                            if (mui == null) return;
                            Dialog.showFileDialog("ldlib.gui.editor.tips.select_image", LDLib2.getAssetsDir(), true, Dialog.suffixFilter(".png"), r -> {
                                if (r != null && r.isFile()) {
                                    var location = getTextureFromFile(r);
                                    if (location == null) return;
                                    texture = location;
                                    configurator.notifyChanges();
                                }
                            }).show(mui.ui.rootElement);
                        }).layout(layout -> layout.setAlignSelf(YogaAlign.CENTER))
                ));
        ConfiguratorParser.createConfigurators(father, this);
    }

    public @Nullable ResourceLocation getTextureFromFile(File filePath) {
        String fullPath = filePath.getPath().replace('\\', '/');
        int assetsIndex = fullPath.indexOf("assets/");
        if (assetsIndex == -1) {
            return null;
        } else {
            String relativePath = fullPath.substring(assetsIndex + "assets/".length());
            int slashIndex = relativePath.indexOf(47);
            if (slashIndex == -1) {
                return null;
            } else {
                String modId = relativePath.substring(0, slashIndex);
                String subPath = relativePath.substring(slashIndex + 1);
                String location = modId + ":" + subPath;
                return LDLib2.isValidResourceLocation(location) ? ResourceLocation.parse(location) : null;
            }
        }
    }
}
