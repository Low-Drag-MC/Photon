package com.lowdragmc.photon.gui.editor_outdated;

import com.lowdragmc.lowdraglib2.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.editor.Icons;
import com.lowdragmc.lowdraglib2.gui.editor.ui.view.FloatViewWidget;
import com.lowdragmc.lowdraglib2.gui.texture.*;
import com.lowdragmc.lowdraglib2.gui.widget.*;
import com.lowdragmc.lowdraglib2.utils.LocalizationUtils;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import org.joml.Vector3f;
import com.lowdragmc.photon.core.mixins.accessor.MinecraftAccessor;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/6/3
 * @implNote ParticleInfoView
 */
public class ParticleInfoView extends FloatViewWidget {
    public final ParticleScenePanel panel;

    public ParticleInfoView(ParticleScenePanel panel) {
        super(100, 100, 200, 135, false);
        this.panel = panel;
    }

    @Override
    public String name() {
        return "particle_info";
    }

    @Override
    public String group() {
        return "editor.fx";
    }

    @Override
    public IGuiTexture getIcon() {
        return Icons.INFORMATION.copy();
    }

    public FXEditor getEditor() {
        return (FXEditor) editor;
    }

    @Override
    public void initWidget() {
        super.initWidget();
        content.setBackground(new GuiTextureGroup(ColorPattern.T_BLACK.rectTexture().setBottomRadius(5f), ColorPattern.GRAY.borderTexture(-1).setBottomRadius(5f)));
        // actions
        addButton("photon.gui.editor.fx_info.restart", panel::restartEmitters);
        // particles
        addInformation("photon.gui.editor.fx_info.particles", () -> {
            var list = panel.getFxObjectsList();
            if (list != null) {
                var selected = list.getSelected();
                if (selected instanceof IParticleEmitter emitter) {
                    return String.valueOf(emitter.getParticleAmount());
                }
            }
            return "0";
        });
        // lifetime
        addInformation("photon.gui.editor.fx_info.time", () -> {
            var list = panel.getFxObjectsList();
            if (list != null) {
                var selected = list.getSelected();
                if (selected instanceof IParticleEmitter emitter) {
                    return "%.2f (s)".formatted(emitter.getAge() / 20f);
                }
            }
            return "0 / 0";
        });
        content.addWidget(new ProgressWidget(() -> {
            var list = panel.getFxObjectsList();
            if (list != null) {
                var selected = list.getSelected();
                if (selected instanceof IParticleEmitter emitter) {
                    return emitter.getT(Minecraft.getInstance().getFrameTime());
                }
            }
            return 0d;
        }, 3, content.widgets.size() * 15 + 3, 194, 10,
                new ProgressTexture(ColorPattern.T_GRAY.rectTexture().setRadius(5).setRadius(5),
                        ColorPattern.GREEN.rectTexture().setRadius(5).setRadius(5))));
        // cpu time
        addInformation("photon.gui.editor.fx_info.cpu_time", () ->  "%d us".formatted(panel.scene.getParticleManager().getCPUTime()));
        // frame time
        addInformation("photon.gui.editor.fx_info.frame_time", () ->  "%d us".formatted(panel.scene.getParticleManager().getFrameTime()));
        // fps
        addInformation("FPS", () -> Minecraft.getInstance().getFps() + " fps");
        // draggable
        var group = addToggle("photon.gui.editor.fx_info.draggable", panel.project::isDraggable, panel.project::setDraggable);
        var textWidth = Minecraft.getInstance().font.width(LocalizationUtils.format("photon.gui.editor.fx_info.draggable")) + 6;
        group.addWidget(new ButtonWidget(textWidth + (194 - textWidth - 70) / 2, 0, 70, 10,
                new GuiTextureGroup(ColorPattern.T_GRAY.rectTexture().setRadius(5).setRadius(5), new TextTexture("photon.gui.editor.fx_info.reset_pos").setWidth(194)),
                cd -> panel.runtime.getRoot().updatePos(new Vector3f(0.5f, 2, 0.5f))));
        addToggle("photon.gui.editor.fx_info.cull_box", panel.project::isRenderCullBox, panel.project::setRenderCullBox);

    }

    protected void addButton(String title, Runnable onClick) {
        var offsetY = content.widgets.size() * 15;
        content.addWidget(new ButtonWidget(3, offsetY + 3, 194, 10,
                new GuiTextureGroup(ColorPattern.T_GRAY.rectTexture().setRadius(5).setRadius(5), new TextTexture(title).setWidth(194)), cd -> onClick.run()));
    }

    protected WidgetGroup addToggle(String title, BooleanSupplier supplier, BooleanConsumer onClick) {
        var offsetY = content.widgets.size() * 15;
        var infoGroup = new WidgetGroup(3, offsetY + 3, 194, 10);
        infoGroup.addWidget(new LabelWidget(0, 0, title));
        var textWidth = Minecraft.getInstance().font.width(LocalizationUtils.format(title)) + 6;
        infoGroup.addWidget(new SwitchWidget(textWidth, -1, 10, 10, (cd, pressed) -> onClick.accept(pressed.booleanValue()))
                .setSupplier(supplier::getAsBoolean).setPressed(supplier.getAsBoolean())
                .setTexture(new ColorBorderTexture(-1, -1).setRadius(5), new GuiTextureGroup(new ColorBorderTexture(-1, -1).setRadius(5), new ColorRectTexture(-1).setRadius(5).scale(0.5f))));
        content.addWidget(infoGroup);
        return infoGroup;
    }

    protected WidgetGroup addInformation(String title, Supplier<String> info) {
        var offsetY = content.widgets.size() * 15;
        var infoGroup = new WidgetGroup(3, offsetY + 3, 194, 10);
        infoGroup.addWidget(new LabelWidget(0, 0, title));
        var textWidth = Minecraft.getInstance().font.width(LocalizationUtils.format(title)) + 6;
        infoGroup.addWidget(new ImageWidget(textWidth, 0, 194 - textWidth, 10, new TextTexture().setWidth(194 - textWidth).setSupplier(info)));
        content.addWidget(infoGroup);
        return infoGroup;
    }

}
