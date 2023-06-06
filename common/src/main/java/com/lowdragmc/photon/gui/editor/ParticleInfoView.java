package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.editor.Icons;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.ui.view.FloatViewWidget;
import com.lowdragmc.lowdraglib.gui.texture.*;
import com.lowdragmc.lowdraglib.gui.widget.*;
import com.lowdragmc.lowdraglib.utils.LocalizationUtils;
import com.lowdragmc.lowdraglib.utils.Vector3;
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
@LDLRegister(name = "particle_info", group = "editor.particle")
public class ParticleInfoView extends FloatViewWidget {

    public ParticleInfoView() {
        super(100, 100, 200, 120, false);
    }

    @Override
    public IGuiTexture getIcon() {
        return Icons.INFORMATION.copy();
    }

    public ParticleEditor getEditor() {
        return (ParticleEditor) editor;
    }

    @Override
    public void initWidget() {
        super.initWidget();
        content.setBackground(new GuiTextureGroup(ColorPattern.T_BLACK.rectTexture().setBottomRadius(5f), ColorPattern.GRAY.borderTexture(-1).setBottomRadius(5f)));
        // actions
        addButton("Restart", () -> getEditor().restartScene());
        // particles
        addInformation("Particles", () -> {
            var list = getEditor().getEmittersList();
            if (list != null) {
                var selected = list.getSelected();
                if (selected != null) {
                    return String.valueOf(selected.getParticleAmount());
                }
            }
            return "0";
        });
        // lifetime
        addInformation("Time", () -> {
            var list = getEditor().getEmittersList();
            if (list != null) {
                var selected = list.getSelected();
                if (selected != null) {
                    return "%.2f (s)".formatted(selected.self().getAge() / 20f);
                }
            }
            return "0 / 0";
        });
        content.addWidget(new ProgressWidget(() -> {
            var list = getEditor().getEmittersList();
            if (list != null) {
                var selected = list.getSelected();
                if (selected != null) {
                    return selected.self().getT(Minecraft.getInstance().getFrameTime());
                }
            }
            return 0d;
        }, 3, content.widgets.size() * 15 + 3, 194, 10, new ProgressTexture(ColorPattern.T_GRAY.rectTexture().setRadius(5).setRadius(5), ColorPattern.GREEN.rectTexture().setRadius(5).setRadius(5))));
        // fps
        addInformation("FPS", () -> MinecraftAccessor.getFps() + " fps");
        // visible
        addToggle("Visible", () -> {
            var list = getEditor().getEmittersList();
            if (list != null) {
                var selected = list.getSelected();
                if (selected != null) {
                    return selected.isVisible();
                }
            }
            return false;
        }, visible -> {
            var list = getEditor().getEmittersList();
            if (list != null) {
                var selected = list.getSelected();
                if (selected != null) {
                    selected.setVisible(visible);
                }
            }
        });
        // draggable
        var group = addToggle("Draggable", () -> getEditor().isDraggable(), draggable -> getEditor().setDraggable(draggable));
        var textWidth = Minecraft.getInstance().font.width(LocalizationUtils.format("Draggable")) + 6;
        group.addWidget(new ButtonWidget(textWidth + (194 - textWidth - 70) / 2, 0, 70, 10,
                new GuiTextureGroup(ColorPattern.T_GRAY.rectTexture().setRadius(5).setRadius(5), new TextTexture("reset pos").setWidth(194)), cd -> {
            var list = getEditor().getEmittersList();
            if (list != null) {
                var selected = list.getSelected();
                if (selected != null) {
                    selected.self().setPos(new Vector3(0.5, 3, 0.5), true);
                }
            }
        }));
        addToggle("Drag All", () -> getEditor().isDragAll(), draggable -> getEditor().setDragAll(draggable));

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
