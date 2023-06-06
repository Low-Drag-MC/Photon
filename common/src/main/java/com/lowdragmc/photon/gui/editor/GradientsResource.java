package com.lowdragmc.photon.gui.editor;


import com.lowdragmc.lowdraglib.gui.editor.Icons;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.gui.editor.configurator.WrapperConfigurator;
import com.lowdragmc.lowdraglib.gui.editor.data.resource.Resource;
import com.lowdragmc.lowdraglib.gui.editor.ui.ConfigPanel;
import com.lowdragmc.lowdraglib.gui.editor.ui.ResourcePanel;
import com.lowdragmc.lowdraglib.gui.editor.ui.resource.ResourceContainer;
import com.lowdragmc.lowdraglib.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib.gui.widget.GradientColorWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.lowdragmc.lowdraglib.utils.GradientColor;
import com.lowdragmc.photon.client.emitter.data.number.color.GradientColorTexture;
import com.lowdragmc.photon.client.emitter.data.number.color.RandomGradientColorTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;

/**
 * @author KilaBash
 * @date 2023/5/31
 * @implNote GradientsResource
 */
public class GradientsResource extends Resource<GradientsResource.Gradients> {
    @Override
    public String name() {
        return "gradients";
    }

    @Override
    public void buildDefault() {
        data.put("black white", new Gradients(new GradientColor(0xff000000, 0xffffffff)));
        data.put("gradient", new Gradients(new GradientColor(0x00ffffff, 0xffffffff, 0x00ffffff)));
        data.put("rainbow", new Gradients(new GradientColor(0xffff0000, 0xffFFA500, 0xffFFFF00, 0xff00ff00, 0xff007FFF, 0xff0000ff, 0xff8B00FF)));

        data.put("random", new Gradients(new GradientColor(0xffffffff, 0xffffffff), new GradientColor(0xff000000, 0xff000000)));
    }

    @Nullable
    @Override
    public Tag serialize(Gradients gradients) {
        return gradients.serializeNBT();
    }

    @Override
    public Gradients deserialize(Tag tag) {
        var gradients = new Gradients();
        if (tag instanceof CompoundTag compoundTag) {
            gradients.deserializeNBT(compoundTag);
        }
        return gradients;
    }

    @Override
    public ResourceContainer<Gradients, ? extends Widget> createContainer(ResourcePanel panel) {
        ResourceContainer<Gradients, ImageWidget> container = new ResourceContainer<>(this, panel) {
            @Override
            protected TreeBuilder.Menu getMenu() {
                var menu = TreeBuilder.Menu.start();
                if (onEdit != null) {
                    menu.leaf(Icons.EDIT_FILE, "ldlib.gui.editor.menu.edit", this::editResource);
                }
                menu.leaf("ldlib.gui.editor.menu.rename", this::renameResource);
                menu.crossLine();
                menu.leaf(Icons.COPY, "ldlib.gui.editor.menu.copy", this::copy);
                menu.leaf(Icons.PASTE, "ldlib.gui.editor.menu.paste", this::paste);
                menu.leaf(Icons.ADD_FILE, "add gradient", () -> {
                    String randomName = genNewFileName();
                    resource.addResource(randomName, new Gradients());
                    reBuild();
                });
                menu.leaf(Icons.ADD_FILE, "add random gradient", () -> {
                    String randomName = genNewFileName();
                    resource.addResource(randomName, new Gradients(new GradientColor(), new GradientColor(0xff000000)));
                    reBuild();
                });
                menu.leaf(Icons.REMOVE_FILE, "ldlib.gui.editor.menu.remove", this::removeSelectedResource);
                return menu;
            }
        };
        container.setWidgetSupplier(k -> new ImageWidget(0, 0, 60, 15, getResource(k).isRandomGradient() ? new RandomGradientColorTexture(getResource(k).gradient0, getResource(k).gradient1) : new GradientColorTexture(getResource(k).gradient0)))
                .setDragging(this::getResource, gradients -> gradients.isRandomGradient() ? new RandomGradientColorTexture(gradients.gradient0, gradients.gradient1) : new GradientColorTexture(gradients.gradient0))
                .setOnEdit(k -> openConfigurator(container, k));
        return container;
    }

    private void openConfigurator(ResourceContainer<Gradients, ImageWidget> container, String key) {
        container.getPanel().getEditor().getConfigPanel().openConfigurator(ConfigPanel.Tab.RESOURCE, new IConfigurable() {
            @Override
            public void buildConfigurator(ConfiguratorGroup father) {
                var curves = getResource(key);
                if (curves.isRandomGradient()) {
                    father.addConfigurators(
                            new WrapperConfigurator("gradient0", new GradientColorWidget(0, 0, 180, curves.gradient0)),
                            new WrapperConfigurator("gradient1", new GradientColorWidget(0, 0, 180, curves.gradient1)));
                } else {
                    father.addConfigurators(new WrapperConfigurator("gradient", new GradientColorWidget(0, 0, 180, curves.gradient0)));
                }
            }
        });
    }

    public static class Gradients implements ITagSerializable<CompoundTag> {
        @Nonnull
        public GradientColor gradient0;
        @Nullable
        public GradientColor gradient1;

        public Gradients(@Nonnull GradientColor gradient0, @Nullable GradientColor gradient1) {
            this.gradient0 = gradient0;
            this.gradient1 = gradient1;
        }

        public Gradients(@Nonnull GradientColor gradient0) {
            this(gradient0, null);
        }

        public Gradients() {
            this(new GradientColor(), null);
        }

        public boolean isRandomGradient() {
            return gradient1 != null;
        }

        public CompoundTag serializeNBT() {
            var tag = new CompoundTag();
            tag.put("a", gradient0.serializeNBT());
            if (gradient1 != null) {
                tag.put("b", gradient1.serializeNBT());
            }
            return tag;
        }

        @Override
        public void deserializeNBT(CompoundTag nbt) {
            if (nbt.get("a") instanceof CompoundTag tag) {
                gradient0.deserializeNBT(tag);
            }
            if (nbt.get("b") instanceof CompoundTag tag) {
                if (gradient1 == null) {
                    gradient1 = new GradientColor();
                }
                gradient1.deserializeNBT(tag);
            }
        }
    }
}
