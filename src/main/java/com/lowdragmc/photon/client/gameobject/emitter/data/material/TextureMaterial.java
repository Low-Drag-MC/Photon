package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.configurator.ConfiguratorParser;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigHDR;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.ToggleGroup;
import com.lowdragmc.photon.client.render.MaterialPreviewRenderer;
import com.lowdragmc.photon.client.render.PhotonMaterialUniforms;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.vfyjxf.taffy.style.AlignItems;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.File;

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

    @Configurable(name = "TextureMaterial.texture")
    protected Identifier texture = Photon.id("textures/particle/circle.png");
    @Configurable(name = "TextureMaterial.discardThreshold")
    @ConfigNumber(range = {0, 1})
    protected float discardThreshold = 0.1f;
    @Configurable(name = "TextureMaterial.hdr")
    @ConfigHDR
    protected Vector4f hdr = new Vector4f(0, 0, 0, 1);
    @Configurable(name = "TextureMaterial.hdrMode")
    protected HDRMode hdrMode = HDRMode.ADDITIVE;
    @Configurable(name = "TextureMaterial.pixelArt", subConfigurable = true)
    protected final PixelArt pixelArt = new PixelArt();

    public TextureMaterial() {
    }

    public TextureMaterial(Identifier texture) {
        this.texture = texture;
    }

    @Override
    public IMaterial copy() {
        var mat = new TextureMaterial(texture);
        mat.discardThreshold = discardThreshold;
        return mat;
    }

    @Override
    public RenderType getRenderType(
            MaterialSetting setting,
            VertexFormat.Mode mode) {
        // 1.21 selected the pixel program only when pixel-art was on; the plain program has no Bits use
        var fragment = pixelArt.isEnable() ? Photon.id("core/pixel_hdr_particle") : Photon.id("core/hdr_particle");
        return MaterialRenderTypes.hdrParticle(texture, fragment,
                setting.pipelineKey(mode),
                PhotonMaterialUniforms.Values.of(
                        hdr, discardThreshold, hdrMode.mode, pixelArt.isEnable() ? Math.max(pixelArt.bits, 1) : 0));
    }


    // live off-screen preview: renders the material's actual RenderType (HDR/discard/pixel-art applied)
    @Override
    public IGuiTexture preview() {
        return MaterialPreviewRenderer.previewOf(this);
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
                        }).layout(layout -> layout.alignSelf(AlignItems.CENTER))
                ));
        ConfiguratorParser.createConfigurators(father, this);
    }

    public @Nullable Identifier getTextureFromFile(File filePath) {
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
                return LDLib2.isValidResourceLocation(location) ? Identifier.parse(location) : null;
            }
        }
    }
}
