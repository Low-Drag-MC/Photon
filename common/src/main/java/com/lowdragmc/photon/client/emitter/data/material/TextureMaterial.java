package com.lowdragmc.photon.client.emitter.data.material;

import com.lowdragmc.lowdraglib.client.shader.Shaders;
import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.configurator.WrapperConfigurator;
import com.lowdragmc.lowdraglib.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DialogWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.File;

/**
 * @author KilaBash
 * @date 2023/5/29
 * @implNote TextureMaterial
 */
@Environment(EnvType.CLIENT)
@ParametersAreNonnullByDefault
public class TextureMaterial extends ShaderInstanceMaterial {

    @Configurable
    public ResourceLocation texture = new ResourceLocation("textures/particle/glow.png");

    @Configurable
    @NumberRange(range = {0, 1})
    public float discardThreshold = 0.01f;

    public TextureMaterial() {
    }

    public TextureMaterial(ResourceLocation texture) {
        this.texture = texture;
    }

    @Override
    public CompoundTag serializeNBT(CompoundTag tag) {
        tag.putString("texture", texture.toString());
        tag.putFloat("discardThreshold", discardThreshold);
        return tag;
    }

    @Override
    public IMaterial copy() {
        var mat = new TextureMaterial(texture);
        mat.discardThreshold = discardThreshold;
        return mat;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        texture = new ResourceLocation(nbt.getString("texture"));
        discardThreshold = nbt.getFloat("discardThreshold");
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
    public void begin(BufferBuilder builder, TextureManager textureManager, boolean isInstancing) {
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
