package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.editor.Icons;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.ui.view.FloatViewWidget;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ProgressTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.*;
import com.lowdragmc.lowdraglib.utils.LocalizationUtils;
import com.lowdragmc.photon.core.mixins.accessor.MinecraftAccessor;
import net.minecraft.client.Minecraft;

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
    }

    protected void addButton(String title, Runnable onClick) {
        var offsetY = content.widgets.size() * 15;
        content.addWidget(new ButtonWidget(3, offsetY + 3, 194, 10,
                new GuiTextureGroup(ColorPattern.T_GRAY.rectTexture().setRadius(5).setRadius(5), new TextTexture(title).setWidth(194)), cd -> onClick.run()));
    }

    protected void addInformation(String title, Supplier<String> info) {
        var offsetY = content.widgets.size() * 15;
        var infoGroup = new WidgetGroup(3, offsetY + 3, 194, 10);
        infoGroup.addWidget(new LabelWidget(0, 0, title));
        var textWidth = Minecraft.getInstance().font.width(LocalizationUtils.format(title)) + 6;
        infoGroup.addWidget(new ImageWidget(textWidth, 0, 194 - textWidth, 10, new TextTexture().setWidth(194 - textWidth).setSupplier(info)));
        content.addWidget(infoGroup);
    }

}
