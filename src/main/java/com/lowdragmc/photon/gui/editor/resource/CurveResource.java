package com.lowdragmc.photon.gui.editor.resource;

import com.lowdragmc.lowdraglib2.configurator.EditAction;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.IResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.Resource;
import com.lowdragmc.lowdraglib2.editor.resource.ResourceProvider;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceProviderContainer;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.util.INBTSerializable;
import org.appliedenergistics.yoga.YogaEdge;
import org.appliedenergistics.yoga.style.StyleSizeLength;
import oshi.util.tuples.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class CurveResource extends Resource<CurveResource.Curves> {
    public static final CurveResource INSTANCE = new CurveResource();

    @Override
    public void buildBuiltin(BuiltinResourceProvider<CurveResource.Curves> provider) {
        provider.addResource("middle", new Curves(new ECBCurves()));
        provider.addResource("linear up", new Curves(new ECBCurves(0, 0, 0.1f, 0.3f, 0.9f, 0.7f, 1, 1)));
        provider.addResource("linear down", new Curves(new ECBCurves(0, 1, 0.1f, 0.7f, 0.9f, 0.3f, 1, 0)));
        provider.addResource("smooth up", new Curves(new ECBCurves(0, 0, 0.1f, 0, 0.9f, 1f, 1, 1)));
        provider.addResource("smooth down", new Curves(new ECBCurves(0, 1, 0.1f, 1, 0.9f, 0f, 1, 0)));
        provider.addResource("concave", new Curves(new ECBCurves(0, 1, 0.1f, 1, 0.4f, 0f, 0.5F, 0, 0.5F, 0, 0.6f, 0, 0.9f, 1f, 1, 1)));
        provider.addResource("convex", new Curves(new ECBCurves(0, 0, 0.1f, 0, 0.4f, 1, 0.5F, 1, 0.5F, 1, 0.6f, 1, 0.9f, 0, 1, 0)));

        provider.addResource("random full", new Curves(new ECBCurves(0, 1, 0.1f, 1, 0.9f, 1, 1, 1), new ECBCurves(0, 0, 0.1f, 0, 0.9f, 0, 1, 0)));
        provider.addResource("random up", new Curves(new ECBCurves(0, 1, 0.1f, 1, 0.9f, 1, 1, 1), new ECBCurves(0, 0, 0.1f, 0, 0.9f, 1f, 1, 1)));
        provider.addResource("random down", new Curves(new ECBCurves(0, 1, 0.1f, 1, 0.9f, 1, 1, 1), new ECBCurves(0, 1, 0.1f, 1, 0.9f, 0f, 1, 0)));
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
    public ResourceProviderContainer<Curves> createResourceProviderContainer(IResourceProvider<Curves> provider) {
        var container = super.createResourceProviderContainer(provider);
        container.setUiSupplier(path -> new UIElement().layout(layout -> {
            layout.setWidthPercent(100);
            layout.setHeightPercent(100);
        }).style(style -> style.backgroundTexture(provider.getResource(path).preview())));
        container.setOnEdit((c, path) -> {
            var curves = provider.getResource(path);
            if (curves == null) return;
            var dialog = new Dialog().width(StyleSizeLength.points(250)).setTitle("editor.edit_curve");
            if (curves.curves1 == null) {
                var curveGraph = new CurveGraph();
                curveGraph.style(style -> style.zIndex(1).backgroundTexture(Sprites.BORDER));
                curveGraph.layout(layout -> {
                    layout.setWidthPercent(100);
                    layout.setHeight(100);
                    layout.setPadding(YogaEdge.ALL, 4);
                });
                curveGraph.setValue(curves.curves0.copy(), false);
                dialog.addContent(curveGraph)
                        .addButton(new Button().setOnClick(e -> {
                            var previousCurves = provider.getResource(path);
                            var newCurves = new Curves(curveGraph.getValue());
                            container.getEditor().historyView.pushHistory(Component.translatable("editor.edit_curve"), EditAction.of(() -> {
                                provider.addResource(path, newCurves);
                                container.reloadSpecificResource(path);
                            }, () -> {
                                provider.addResource(path, previousCurves);
                                container.reloadSpecificResource(path);
                            }));
                            dialog.close();
                        }).setText("ldlib.gui.tips.confirm"));
            } else {
                var curveGraph = new RandomCurveGraph();
                curveGraph.style(style -> style.zIndex(1).backgroundTexture(Sprites.BORDER));
                curveGraph.layout(layout -> {
                    layout.setWidthPercent(100);
                    layout.setHeight(100);
                    layout.setPadding(YogaEdge.ALL, 4);
                });
                curveGraph.setValue(new Pair<>(curves.curves0.copy(), curves.curves1.copy()), false);
                dialog.addContent(curveGraph)
                        .addButton(new Button().setOnClick(e -> {
                            var previousCurves = provider.getResource(path);
                            var newCurves = new Curves(curveGraph.getValue().getA(), curveGraph.getValue().getB());
                            container.getEditor().historyView.pushHistory(Component.translatable("editor.edit_curve"), EditAction.of(() -> {
                                provider.addResource(path, newCurves);
                                container.reloadSpecificResource(path);
                            }, () -> {
                                provider.addResource(path, previousCurves);
                                container.reloadSpecificResource(path);
                            }));
                            dialog.close();
                        }).setText("ldlib.gui.tips.confirm"));
            }
            dialog.addButton(new Button().setOnClick(e -> dialog.close()).setText("ldlib.gui.tips.cancel"))
                    .show(container.getEditor());
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
