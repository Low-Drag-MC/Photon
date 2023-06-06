package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.gui.editor.configurator.WrapperConfigurator;
import com.lowdragmc.lowdraglib.gui.editor.runtime.PersistedParser;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.lowdragmc.photon.client.emitter.data.material.BlendMode;
import com.lowdragmc.photon.client.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.emitter.data.material.TextureMaterial;
import com.mojang.blaze3d.systems.RenderSystem;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nonnull;
import java.util.HashMap;

/**
 * @author KilaBash
 * @date 2023/5/29
 * @implNote Material
 */
@Environment(EnvType.CLIENT)
public class MaterialSetting implements IConfigurable, ITagSerializable<CompoundTag> {

    @Configurable(name = "Blend Mode", subConfigurable = true)
    protected final BlendMode blendMode = new BlendMode();
    @Getter @Setter
    @Configurable
    protected boolean cull = true;
    @Getter @Setter
    @Configurable
    protected boolean depthTest = true;
    @Getter @Setter
    @Configurable
    protected boolean depthMask = false;
    @Getter @Setter
    @Nonnull
    protected IMaterial material = new TextureMaterial();

    public void pre() {
        blendMode.apply();
        if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
        if (depthTest) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        RenderSystem.depthMask(depthMask);
    }

    public void post() {
        if (blendMode.getBlendFunc() != BlendMode.BlendFuc.ADD) {
            RenderSystem.blendEquation(BlendMode.BlendFuc.ADD.op);
        }
        if (!cull) RenderSystem.enableCull();
        if (!depthTest) RenderSystem.enableDepthTest();
        if (!depthMask) RenderSystem.depthMask(true);
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        IConfigurable.super.buildConfigurator(father);
        var setting = new ConfiguratorGroup("Setting");
        material.buildConfigurator(setting);
        ImageWidget preview;
        var wrapper = new WrapperConfigurator("preview", preview = new ImageWidget(0, 0, 50, 50, () -> material.preview()).setBorder(2, ColorPattern.T_WHITE.color));
        preview.setDraggingConsumer(
                o -> o instanceof IMaterial,
                o -> preview.setBorder(2, ColorPattern.GREEN.color),
                o -> preview.setBorder(2, ColorPattern.T_WHITE.color),
                o -> {
                    if (o instanceof IMaterial mat) {
                        this.material = mat.copy();
                        setting.removeAllConfigurators();
                        this.material.buildConfigurator(setting);
                        setting.computeLayout();
                        preview.setBorder(2, ColorPattern.T_WHITE.color);
                    }
                });
        wrapper.setTips("Replace the material by dragging it to preview.");
        father.addConfigurator(0, wrapper);
        father.addConfigurators(setting);
    }

    @Override
    public CompoundTag serializeNBT() {
        var nbt = new CompoundTag();
        PersistedParser.serializeNBT(nbt, this.getClass(), this);
        nbt.put("material", material.serializeNBT());
        return nbt;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        PersistedParser.deserializeNBT(nbt, new HashMap<>(), this.getClass(), this);
        var material = IMaterial.deserializeWrapper(nbt.getCompound("material"));
        this.material = material == null ? new TextureMaterial() : material;
    }
}
