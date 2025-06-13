package com.lowdragmc.photon.gui.editor_outdated;


import com.lowdragmc.lowdraglib2.LDLib;
import com.lowdragmc.lowdraglib2.gui.editor.Icons;
import com.lowdragmc.lowdraglib2.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib2.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.gui.editor.configurator.WrapperConfigurator;
import com.lowdragmc.lowdraglib2.gui.editor.data.resource.Resource;
import com.lowdragmc.lowdraglib2.gui.editor.ui.ConfigPanel;
import com.lowdragmc.lowdraglib2.gui.editor.ui.ResourcePanel;
import com.lowdragmc.lowdraglib2.gui.editor.ui.resource.ResourceContainer;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib2.gui.widget.GradientColorWidget;
import com.lowdragmc.lowdraglib2.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib2.gui.widget.Widget;
import com.lowdragmc.lowdraglib2.syncdata.ITagSerializable;
import com.lowdragmc.lowdraglib2.utils.GradientColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.GradientColorTexture;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradientColorTexture;
import com.mojang.datafixers.util.Either;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;

import java.io.File;

import static com.lowdragmc.photon.gui.editor_outdated.GradientsResource.RESOURCE_NAME;

/**
 * @author KilaBash
 * @date 2023/5/31
 * @implNote GradientsResource
 */
@LDLRegister(name = RESOURCE_NAME, group = "resource")
public class GradientsResource extends Resource<GradientsResource.Gradients> {
    public final static String RESOURCE_NAME = "gradients";

    public GradientsResource() {
        super(new File(LDLib.getLDLibDir(), "assets/resources/gradients"));
    }

    @Override
    public String name() {
        return RESOURCE_NAME;
    }

    @Override
    public void buildDefault() {
        addBuiltinResource("black white", new Gradients(new GradientColor(0xff000000, 0xffffffff)));
        addBuiltinResource("gradient", new Gradients(new GradientColor(0x00ffffff, 0xffffffff, 0x00ffffff)));
        addBuiltinResource("rainbow", new Gradients(new GradientColor(0xffff0000, 0xffFFA500, 0xffFFFF00, 0xff00ff00, 0xff007FFF, 0xff0000ff, 0xff8B00FF)));

        addBuiltinResource("random", new Gradients(new GradientColor(0xffffffff, 0xffffffff), new GradientColor(0xff000000, 0xff000000)));
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
                return super.getMenu().leaf(Icons.ADD_FILE, "add gradient", () -> {
                    String randomName = genNewFileName();
                    resource.addBuiltinResource(randomName, new Gradients());
                    reBuild();
                }).leaf(Icons.ADD_FILE, "add random gradient", () -> {
                    String randomName = genNewFileName();
                    resource.addBuiltinResource(randomName, new Gradients(new GradientColor(), new GradientColor(0xff000000)));
                    reBuild();
                });
            }
        };
        container.setWidgetSupplier(k -> new ImageWidget(0, 0, 60, 15, getResource(k).isRandomGradient() ? new RandomGradientColorTexture(getResource(k).gradient0, getResource(k).gradient1) : new GradientColorTexture(getResource(k).gradient0)))
                .setDragging(this::getResource, gradients -> gradients.isRandomGradient() ? new RandomGradientColorTexture(gradients.gradient0, gradients.gradient1) : new GradientColorTexture(gradients.gradient0))
                .setOnEdit(k -> openConfigurator(container, k));
        return container;
    }

    private void openConfigurator(ResourceContainer<Gradients, ImageWidget> container, Either<String, File> key) {
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
