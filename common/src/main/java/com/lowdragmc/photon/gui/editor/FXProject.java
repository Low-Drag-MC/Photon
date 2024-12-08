package com.lowdragmc.photon.gui.editor;


import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.data.IProject;
import com.lowdragmc.lowdraglib.gui.editor.data.Resources;
import com.lowdragmc.lowdraglib.gui.editor.data.resource.ColorsResource;
import com.lowdragmc.lowdraglib.gui.editor.data.resource.Resource;
import com.lowdragmc.lowdraglib.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib.gui.widget.DialogWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.LocalizationUtils;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXData;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@LDLRegister(name = "fxproj", group = "editor.fx")
@Getter
@NoArgsConstructor
public class FXProject implements IProject {
    public static int VERSION = 1;

    protected Resources resources;
    protected FX fx = new FX();
    // runtime
    @Setter
    private boolean draggable = true;
    @Setter
    private boolean renderCullBox = false;

    private FXProject(Resources resources) {
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

    public FXProject newEmptyProject() {
        return new FXProject(createResources());
    }

    @Override
    public CompoundTag serializeNBT() {
        var tag = new CompoundTag();
        tag.put("resources", resources.serializeNBT());
        tag.put("fx", fx.serializeNBT());
        tag.putInt("_version", VERSION);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        var version = tag.contains("_version") ? tag.getInt("_version") : 0;
        this.resources = loadResources(tag.getCompound("resources"));
        fx.deserializeNBT(tag.getCompound("fx"));
        if (version < 1) {
            var emitters = new CompoundTag();
            emitters.put("fxObjects", tag.getList("emitters", Tag.TAG_COMPOUND));
            fx.getMainFX().deserializeNBT(emitters);
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
                                    tag.put("fx", fx.serializeNBT());
                                    NbtIo.writeCompressed(tag, r);
                                } catch (IOException ignored) {}
                            }
                        });
            }));
        }
    }

    public ParticleScenePanel createParticleScenePanel(FXEditor editor, FXData fxData) {
        return new ParticleScenePanel(editor, this, fxData);
    }

    @Override
    public void onLoad(Editor editor) {
        if (editor instanceof FXEditor fxEditor) {
            IProject.super.onLoad(editor);
            var tabContainer = fxEditor.getTabPages();
            var mainFX = createParticleScenePanel(fxEditor, fx.getMainFX());
            // main fx
            tabContainer.addTab("photon.gui.editor.fx.particle_panel.main", mainFX, mainFX::onPanelSelected, mainFX::onPanelDeselected);

            // sub fx
            for (var entry : fx.getSubFXs().entrySet()) {
                var name = entry.getKey();
                var fxData = entry.getValue();
                var subFX = createParticleScenePanel(fxEditor, fxData);
                tabContainer.addTab(null, LocalizationUtils.format("photon.gui.editor.fx.particle_panel.sub", name),
                        subFX, subFX::onPanelSelected, subFX::onPanelDeselected, () -> fx.getSubFXs().remove(name));
            }

            // button
            tabContainer.addTab("+", new WidgetGroup(), () -> {
                DialogWidget.showStringEditorDialog(editor, "photon.gui.editor.fx.particle_panel.add", "sub_fx_name",
                        s -> !fx.getSubFXs().containsKey(s), s -> {
                            if (s != null) {
                                fx.getSubFXs().put(s, new FXData());
                                var lastIndex = tabContainer.getTabIndex();
                                editor.loadProject(this);
                                tabContainer.switchTabIndex(lastIndex);
                            } else {
                                editor.loadProject(this);
                                tabContainer.switchTabIndex(0);
                            }
                        });
            }, null);
        }
    }

    @Override
    public void onClosed(Editor editor) {
        editor.getFloatView().clearAllWidgets();
    }
}
