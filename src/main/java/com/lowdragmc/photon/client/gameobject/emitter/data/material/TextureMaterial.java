package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.client.shader.Shaders;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.systems.RenderSystem;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.appliedenergistics.yoga.YogaAlign;
import org.appliedenergistics.yoga.YogaEdge;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.File;

/**
 * @author KilaBash
 * @date 2023/5/29
 * @implNote TextureMaterial
 */
@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "texture", registry = "photon:material")
public class TextureMaterial extends ShaderInstanceMaterial {

    @Configurable
    @Setter
    public ResourceLocation texture = ResourceLocation.parse("textures/particle/glow.png");

    @Configurable
    @ConfigNumber(range = {0, 1})
    public float discardThreshold = 0.01f;

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
        return Shaders.getParticleShader();
    }

    @Override
    public void setupUniform() {
        RenderSystem.setShaderTexture(0, texture);
        Shaders.getParticleShader().safeGetUniform("DiscardThreshold").set(discardThreshold);
    }

    @Override
    public void begin(boolean isInstancing) {
        // TODO better shader pack support
        if (Photon.isUsingShaderPack()) {
            RenderSystem.setShaderTexture(0, texture);
        } else {
            RenderSystem.setShader(this::getShader);
            setupUniform();
        }
    }

    @Override
    public IGuiTexture preview() {
        return DynamicTexture.of(() -> SpriteTexture.of(texture.toString()));
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        var configurator = new Configurator("ldlib.gui.editor.group.base_image");
        father.addConfigurators(configurator
                .addChildren(
                        // raw image preview
                        new UIElement().layout(layout -> {
                                    layout.setAspectRatio(1.0f);
                                    layout.setWidthPercent(80);
                                    layout.setPadding(YogaEdge.ALL, 3);
                                    layout.setAlignSelf(YogaAlign.CENTER);
                                }).style(style -> style.backgroundTexture(Sprites.BORDER1_RT1))
                                .addChild(new UIElement().layout(layout -> {
                                    layout.setWidthPercent(100);
                                    layout.setHeightPercent(100);
                                }).style(style -> style.backgroundTexture(DynamicTexture.of(() -> SpriteTexture.of(texture.toString()))))),
                        // button to select image
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
        super.buildConfigurator(father);
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
