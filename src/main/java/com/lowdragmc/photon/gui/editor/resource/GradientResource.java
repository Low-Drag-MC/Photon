package com.lowdragmc.photon.gui.editor.resource;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.IResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.Resource;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceProviderContainer;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.GradientColorSelector;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.GradientColorTexture;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradientColorTexture;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;

public class GradientResource extends Resource<GradientResource.Gradients> {
    public static final GradientResource INSTANCE = new GradientResource();

    @Override
    public void buildBuiltin(BuiltinResourceProvider<GradientResource.Gradients> provider) {
        provider.addResource("black white", new Gradients(new GradientColor(0xff000000, 0xffffffff)));
        provider.addResource("gradient", new Gradients(new GradientColor(0x00ffffff, 0xffffffff, 0x00ffffff)));
        provider.addResource("rainbow", new Gradients(new GradientColor(0xffff0000, 0xffFFA500, 0xffFFFF00, 0xff00ff00, 0xff007FFF, 0xff0000ff, 0xff8B00FF)));

        provider.addResource("random", new Gradients(new GradientColor(0xffffffff, 0xffffffff), new GradientColor(0xff000000, 0xff000000)));
    }

    @Override
    public IGuiTexture getIcon() {
        return Icons.GRADIENT;
    }

    @Override
    public String getName() {
        return "gradient";
    }

    @Nullable
    @Override
    public Tag serializeResource(GradientResource.Gradients value, HolderLookup.Provider provider) {
        return value.serializeNBT(provider);
    }

    @Override
    public GradientResource.Gradients deserializeResource(Tag nbt, HolderLookup.Provider provider) {
        var gradients = new GradientResource.Gradients();
        if (nbt instanceof CompoundTag tag) {
            gradients.deserializeNBT(provider, tag);
        }
        return gradients;
    }

    @Override
    public ResourceProviderContainer<GradientResource.Gradients> createResourceProviderContainer(IResourceProvider<GradientResource.Gradients> provider) {
        var container = super.createResourceProviderContainer(provider);
        container.setUiSupplier(path -> new UIElement().layout(layout -> {
            layout.setWidthPercent(100);
            layout.setHeightPercent(100);
        }).style(style -> style.backgroundTexture(provider.getResource(path).preview())));
        container.setOnEdit((c, path) -> {
            var gradients = provider.getResource(path);
            if (gradients == null) return;
            c.getEditor().inspectorView.inspect(gradients, configurator -> c.markResourceDirty(path));
        });
        if (provider.supportAdd()) {
            container.setOnMenu((c, m) -> m.branch(Icons.ADD_FILE, "ldlib.gui.editor.menu.add_resource", menu -> {
                menu.leaf("gradient", () -> {
                    c.addNewResource(new GradientResource.Gradients());
                });
                menu.leaf("random gradient", () -> {
                    c.addNewResource(new GradientResource.Gradients(new GradientColor(), new GradientColor(0xff000000)));
                });
            }));
        }
        return container;
    }

    public static class Gradients implements IConfigurable, INBTSerializable<CompoundTag> {
        @Nonnull
        public final GradientColor gradient0;
        @Nullable
        public final GradientColor gradient1;

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

        public CompoundTag serializeNBT(@Nonnull HolderLookup.Provider provider) {
            var tag = new CompoundTag();
            tag.put("a", gradient0.serializeNBT(provider));
            if (gradient1 != null) {
                tag.put("b", gradient1.serializeNBT(provider));
            }
            return tag;
        }

        @Override
        public void deserializeNBT(@Nonnull HolderLookup.Provider provider, CompoundTag nbt) {
            if (nbt.get("a") instanceof CompoundTag tag) {
                gradient0.deserializeNBT(provider, tag);
            }
            if (gradient1 != null) {
                if (nbt.get("b") instanceof CompoundTag tag) {
                    gradient1.deserializeNBT(provider, tag);
                }
            }
        }

        public IGuiTexture preview() {
            return isRandomGradient() ? new RandomGradientColorTexture(gradient0, gradient1) : new GradientColorTexture(gradient0);
        }

        @Override
        public void buildConfigurator(ConfiguratorGroup father) {
            var container = new Configurator();
            container.addInlineChild(
                    new GradientColorSelector().setValue(gradient0.copy(), false).setOnColorGradientChangeListener(gradientColor -> {
                        gradient0.deserializeNBT(Platform.getFrozenRegistry(), gradientColor.serializeNBT(Platform.getFrozenRegistry()));
                        container.notifyChanges();
                    }).layout(layout -> layout.setWidthPercent(100))
            );
            if (gradient1 != null) {
                container.addInlineChild(
                        new GradientColorSelector().setValue(gradient1.copy(), false).setOnColorGradientChangeListener(gradientColor -> {
                            gradient1.deserializeNBT(Platform.getFrozenRegistry(), gradientColor.serializeNBT(Platform.getFrozenRegistry()));
                            container.notifyChanges();
                        }).layout(layout -> layout.setWidthPercent(100))
                );
            }
            father.addConfigurator(container);
        }
    }
}
