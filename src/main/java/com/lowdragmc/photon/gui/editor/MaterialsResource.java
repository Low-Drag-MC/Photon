package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.LDLib;
import com.lowdragmc.lowdraglib2.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib2.gui.editor.data.resource.Resource;
import com.lowdragmc.lowdraglib2.gui.editor.ui.ResourcePanel;
import com.lowdragmc.lowdraglib2.gui.editor.ui.resource.ResourceContainer;
import com.lowdragmc.lowdraglib2.gui.widget.ImageWidget;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlockTextureSheetMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.io.File;

import static com.lowdragmc.photon.gui.editor.MaterialsResource.RESOURCE_NAME;

/**
 * @author KilaBash
 * @date 2023/5/29
 * @implNote MaterialsResource
 */
@LDLRegister(name = RESOURCE_NAME, group = "resource")
public class MaterialsResource extends Resource<IMaterial> {
    public final static String RESOURCE_NAME = "material";

    public MaterialsResource() {
        super(new File(LDLib.getLDLibDir(), "assets/resources/material"));
    }

    @Override
    public String name() {
        return RESOURCE_NAME;
    }

    @Override
    public void buildDefault() {
        addVanillaTextureMaterial("angry");
        addVanillaTextureMaterial("bubble");
        addVanillaTextureMaterial("damage");
        addVanillaTextureMaterial("flame");
        addVanillaTextureMaterial("glow");
        addVanillaTextureMaterial("heart");
        addVanillaTextureMaterial("lava");
        addVanillaTextureMaterial("note");

        addBuiltinTextureMaterial("kila_tail");
        addBuiltinTextureMaterial("laser");
        addBuiltinTextureMaterial("smoke");
        addBuiltinTextureMaterial("thaumcraft");
        addBuiltinTextureMaterial("ring");

        addBuiltinShaderMaterial("circle");
        addBuiltinResource("block_texture_sheet", new BlockTextureSheetMaterial());

    }

    private void addVanillaTextureMaterial(String name) {
        addBuiltinResource(name, new TextureMaterial(new ResourceLocation("textures/particle/%s.png".formatted(name))));
    }

    private void addBuiltinTextureMaterial(String name) {
        addBuiltinResource(name, new TextureMaterial(new ResourceLocation("photon:textures/particle/%s.png".formatted(name))));
    }

    private void addBuiltinShaderMaterial(String name) {
        addBuiltinResource(name, new CustomShaderMaterial(new ResourceLocation("photon:%s".formatted(name))));
    }

    @Override
    public ResourceContainer<IMaterial, ImageWidget> createContainer(ResourcePanel panel) {
        return new MaterialsResourceContainer(this, panel);
    }

    @Override
    public Tag serialize(IMaterial value) {
        return value.serializeNBT();
    }

    @Override
    public IMaterial deserialize(Tag nbt) {
        if (nbt instanceof CompoundTag tag) {
            var type = tag.getString("_type");
            for (var clazz : IMaterial.MATERIALS) {
                if (type.equals(clazz.getSimpleName())) {
                    try {
                        IMaterial mat = clazz.getConstructor().newInstance();
                        mat.deserializeNBT(tag);
                        return mat;
                    } catch (Throwable ignored) {}
                }
            }
        }
        return null;
    }

}
