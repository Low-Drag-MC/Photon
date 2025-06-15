package com.lowdragmc.photon.gui.editor.resource;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.Resource;
import com.lowdragmc.lowdraglib2.editor.resource.ResourceProvider;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceProviderContainer;
import com.lowdragmc.lowdraglib2.editor_outdated.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveTexture;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurveTexture;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;

public class CurveResource extends Resource<CurveResource.Curves> {

    public CurveResource() {
        var builtinResource = new BuiltinResourceProvider<>(this);
        builtinResource.addResource("middle", new Curves(new ECBCurves()));
        builtinResource.addResource("linear up", new Curves(new ECBCurves(0, 0, 0.1f, 0.3f, 0.9f, 0.7f, 1, 1)));
        builtinResource.addResource("linear down", new Curves(new ECBCurves(0, 1, 0.1f, 0.7f, 0.9f, 0.3f, 1, 0)));
        builtinResource.addResource("smooth up", new Curves(new ECBCurves(0, 0, 0.1f, 0, 0.9f, 1f, 1, 1)));
        builtinResource.addResource("smooth down", new Curves(new ECBCurves(0, 1, 0.1f, 1, 0.9f, 0f, 1, 0)));
        builtinResource.addResource("concave", new Curves(new ECBCurves(0, 1, 0.1f, 1, 0.4f, 0f, 0.5F, 0, 0.5F, 0, 0.6f, 0, 0.9f, 1f, 1, 1)));
        builtinResource.addResource("convex", new Curves(new ECBCurves(0, 0, 0.1f, 0, 0.4f, 1, 0.5F, 1, 0.5F, 1, 0.6f, 1, 0.9f, 0, 1, 0)));

        builtinResource.addResource("random full", new Curves(new ECBCurves(0, 1, 0.1f, 1, 0.9f, 1, 1, 1), new ECBCurves(0, 0, 0.1f, 0, 0.9f, 0, 1, 0)));
        builtinResource.addResource("random up", new Curves(new ECBCurves(0, 1, 0.1f, 1, 0.9f, 1, 1, 1), new ECBCurves(0, 0, 0.1f, 0, 0.9f, 1f, 1, 1)));
        builtinResource.addResource("random down", new Curves(new ECBCurves(0, 1, 0.1f, 1, 0.9f, 1, 1, 1), new ECBCurves(0, 1, 0.1f, 1, 0.9f, 0f, 1, 0)));
        addResourceProvider(builtinResource);
        setList(true);
        setUiWidth(15);
    }

    @Override
    public void buildDefault() {
        addResourceProvider(createNewFileResourceProvider(new File(LDLib2.getAssetsDir(), "ldlib2/resources")).setName("global"));
    }

    @Override
    public IGuiTexture getIcon() {
        return Icons.CURVE;
    }

    @Override
    public String getName() {
        return "curve";
    }

    @Nullable
    @Override
    public Tag serializeResource(CurveResource.Curves value, HolderLookup.Provider provider) {
        return value.serializeNBT(provider);
    }

    @Override
    public CurveResource.Curves deserializeResource(Tag nbt, HolderLookup.Provider provider) {
        var curves = new Curves();
        if (nbt instanceof CompoundTag tag) {
            curves.deserializeNBT(provider, tag);
        }
        return curves;
    }

    @Override
    public ResourceProviderContainer<CurveResource.Curves> createResourceProviderContainer(ResourceProvider<CurveResource.Curves> provider) {
        var container = super.createResourceProviderContainer(provider);
        container.setUiSupplier(path -> new UIElement().layout(layout -> {
            layout.setWidthPercent(100);
            layout.setHeightPercent(100);
        }).style(style -> style.backgroundTexture(provider.getResource(path).preview())));
        container.setOnEdit((c, path) -> {
            // TODO edit
        });

        if (provider.supportAdd()) {
            container.setOnMenu((c, m) -> m.branch(Icons.ADD_FILE, "ldlib.gui.editor.menu.add_resource", menu -> {
                menu.leaf("curve", () -> {
                    c.addNewResource(new Curves());
                });
                menu.leaf("random curve", () -> {
                    c.addNewResource(new Curves(new ECBCurves(), new ECBCurves(0, 0.2f, 0.1f, 0.2f, 0.9f, 0.2f, 1, 0.2f)));
                });
            }));
        }
        return container;
    }

    public static class Curves implements INBTSerializable<CompoundTag> {
        @Nonnull
        public final ECBCurves curves0;
        @Nullable
        public final ECBCurves curves1;

        public Curves(@Nonnull ECBCurves curves0, @Nullable ECBCurves curves1) {
            this.curves0 = curves0;
            this.curves1 = curves1;
        }

        public Curves(@Nonnull ECBCurves curves0) {
            this(curves0, null);
        }

        public Curves() {
            this(new ECBCurves(), null);
        }

        public boolean isRandomCurve() {
            return curves1 != null;
        }

        public CompoundTag serializeNBT(@Nonnull HolderLookup.Provider provider) {
            var tag = new CompoundTag();
            tag.put("a", curves0.serializeNBT(provider));
            if (curves1 != null) {
                tag.put("b", curves1.serializeNBT(provider));
            }
            return tag;
        }

        @Override
        public void deserializeNBT(@Nonnull HolderLookup.Provider provider, CompoundTag nbt) {
            if (nbt.get("a") instanceof ListTag list) {
                curves0.deserializeNBT(provider, list);
            }
            if (curves1 != null) {
                if (nbt.get("b") instanceof ListTag list) {
                    curves1.deserializeNBT(provider, list);
                }
            }
        }

        public IGuiTexture preview() {
            return isRandomCurve() ? new RandomCurveTexture(curves0, curves1) : new CurveTexture(curves0);
        }
    }
}
