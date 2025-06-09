package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.client.shader.Shaders;
import com.lowdragmc.lowdraglib2.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.editor.configurator.WrapperConfigurator;
import com.lowdragmc.lowdraglib2.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib2.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib2.gui.widget.DialogWidget;
import com.lowdragmc.lowdraglib2.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib2.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.systems.RenderSystem;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

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
        if (Photon.isUsingShaderPack() && Editor.INSTANCE == null) {
            RenderSystem.setShaderTexture(0, texture);
        } else {
            RenderSystem.setShader(this::getShader);
            setupUniform();
        }
    }

    @Override
    public IGuiTexture preview() {
        return new ResourceTexture(texture.toString());
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        WidgetGroup widgetGroup = new WidgetGroup(0, 0, 100, 100);
        widgetGroup.addWidget(new ImageWidget(0, 0, 100, 100, () -> new ResourceTexture(texture.toString())).setBorder(2, ColorPattern.T_WHITE.color));
        widgetGroup.addWidget(new ButtonWidget(0, 0, 100, 100, IGuiTexture.EMPTY, cd -> {
            if (Editor.INSTANCE == null) return;
            File path = new File(Editor.INSTANCE.getWorkSpace(), "assets/ldlib/textures");
            DialogWidget.showFileDialog(Editor.INSTANCE, "ldlib.gui.editor.tips.select_image", path, true,
                    DialogWidget.suffixFilter(".png"), r -> {
                        if (r != null && r.isFile()) {
                            texture = new ResourceLocation("ldlib:" + r.getPath().replace(path.getPath(), "textures").replace('\\', '/'));
                        }
                    });
        }));
        WrapperConfigurator base = new WrapperConfigurator("ldlib.gui.editor.group.base_image", widgetGroup);
        base.setTips("ldlib.gui.editor.tips.click_select_image");
        father.addConfigurators(base);
        super.buildConfigurator(father);
    }
}
