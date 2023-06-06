package com.lowdragmc.photon.gui.editor;


import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.data.IProject;
import com.lowdragmc.lowdraglib.gui.editor.data.Resources;
import com.lowdragmc.lowdraglib.gui.editor.data.resource.ColorsResource;
import com.lowdragmc.lowdraglib.gui.editor.data.resource.Resource;
import com.lowdragmc.lowdraglib.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib.gui.widget.DialogWidget;
import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import lombok.Getter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote ParticleProject
 */
@LDLRegister(name = "fxproj", group = "editor.particle")
public class ParticleProject implements IProject {
    @Getter
    protected Resources resources;
    @Getter
    protected List<IParticleEmitter> emitters = new ArrayList<>();

    private ParticleProject() {
    }

    private ParticleProject(Resources resources) {
        this.resources = resources;
    }

    private Resources createResources() {
        Map<String, Resource<?>> resources = new LinkedHashMap<>();
        // material
        var material = new MaterialsResource();
        material.buildDefault();
        resources.put(material.name(), material);
        // material
        var mesh = new MeshesResource();
        mesh.buildDefault();
        resources.put(mesh.name(), mesh);
        // color
        var color = new ColorsResource();
        color.buildDefault();
        resources.put(ColorsResource.RESOURCE_NAME, color);
        // curve
        var curve = new CurvesResource();
        curve.buildDefault();
        resources.put(curve.name(), curve);
        // gradient
        var gradient = new GradientsResource();
        gradient.buildDefault();
        resources.put(gradient.name(), gradient);
        return new Resources(resources);
    }

    public ParticleProject newEmptyProject() {
        return new ParticleProject(createResources());
    }

    public CompoundTag serializeNBT() {
        var tag = new CompoundTag();
        tag.put("resources", resources.serializeNBT());
        var list = new ListTag();
        for (var emitter : emitters) {
            list.add(emitter.serializeNBT());
        }
        tag.put("emitters", list);
        return tag;
    }

    public void deserializeNBT(CompoundTag tag) {
        this.resources = loadResources(tag.getCompound("resources"));
        this.emitters.clear();
        for (var nbt : tag.getList("emitters", Tag.TAG_COMPOUND)) {
            if (nbt instanceof CompoundTag data) {
                var emitter = IParticleEmitter.deserializeWrapper(data);
                if (emitter != null) {
                    this.emitters.add(emitter);
                }
            }
        }
    }

    @Override
    public Resources loadResources(CompoundTag tag) {
        var resources = createResources();
        resources.deserializeNBT(tag);
        return resources;
    }

    @Override
    public void saveProject(File file) {
        try {
            NbtIo.write(serializeNBT(), file);
        } catch (IOException ignored) { }
    }

    @Nullable
    @Override
    public IProject loadProject(File file) {
        try {
            var tag = NbtIo.read(file);
            if (tag != null) {
                var proj = new ParticleProject();
                proj.deserializeNBT(tag);
                return proj;
            }
        } catch (IOException ignored) {}
        return null;
    }

    @Override
    public void attachMenu(Editor editor, String name, TreeBuilder.Menu menu) {
        if (name.equals("file")) {
            menu.branch("ldlib.gui.editor.menu.export", m -> m.leaf("FX", () -> {
                File path = new File(Editor.INSTANCE.getWorkSpace(), "assets/photon/fx");
                DialogWidget.showFileDialog(editor, "Export FX", path, false,
                        DialogWidget.suffixFilter(".fx"), r -> {
                            if (r != null && !r.isDirectory()) {
                                if (!r.getName().endsWith(".fx")) {
                                    r = new File(r.getParentFile(), r.getName() + ".fx");
                                }
                                try {
                                    var tag = new CompoundTag();
                                    var list = new ListTag();
                                    for (var emitter : emitters) {
                                        list.add(emitter.serializeNBT());
                                    }
                                    tag.put("emitters", list);
                                    NbtIo.writeCompressed(tag, r);
                                } catch (IOException ignored) {}
                            }
                        });
            }));
        }
    }
}
