package com.lowdragmc.photon.client.emitter.data.material;

import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.List;

/**
 * @author KilaBash
 * @date 2023/5/29
 * @implNote Material
 */
@Environment(EnvType.CLIENT)
@ParametersAreNonnullByDefault
public interface IMaterial extends IConfigurable, ITagSerializable<CompoundTag> {

    List<Class<? extends IMaterial>> MATERIALS = new ArrayList<>(List.of(
            TextureMaterial.class, CustomShaderMaterial.class
    ));

    static IMaterial deserializeWrapper(CompoundTag tag) {
        var type = tag.getString("_type");
        for (var clazz : MATERIALS) {
            if (clazz.getSimpleName().equals(type)) {
                try {
                    IMaterial shape = clazz.getConstructor().newInstance();
                    shape.deserializeNBT(tag);
                    return shape;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    void begin(boolean isInstancing);

    void end(boolean isInstancing);

    IGuiTexture preview();

    @Override
    default CompoundTag serializeNBT() {
        var tag = new CompoundTag();
        tag.putString("_type", getClass().getSimpleName());
        return serializeNBT(tag);
    }

    CompoundTag serializeNBT(CompoundTag tag);

    default IMaterial copy() {
        return deserializeWrapper(serializeNBT());
    }
}
