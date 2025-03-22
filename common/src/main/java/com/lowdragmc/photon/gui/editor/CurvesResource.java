package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.editor.Icons;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.gui.editor.configurator.WrapperConfigurator;
import com.lowdragmc.lowdraglib.gui.editor.data.resource.Resource;
import com.lowdragmc.lowdraglib.gui.editor.ui.ConfigPanel;
import com.lowdragmc.lowdraglib.gui.editor.ui.ResourcePanel;
import com.lowdragmc.lowdraglib.gui.editor.ui.resource.ResourceContainer;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.lowdragmc.lowdraglib.utils.Size;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.*;
import com.mojang.datafixers.util.Either;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;

import java.io.File;

import static com.lowdragmc.photon.gui.editor.CurvesResource.RESOURCE_NAME;


/**
 * @author KilaBash
 * @date 2023/5/29
 * @implNote CurveResource
 */
@LDLRegister(name = RESOURCE_NAME, group = "resource")
public class CurvesResource extends Resource<CurvesResource.Curves> {
    public final static String RESOURCE_NAME = "curves";

    public CurvesResource() {
        super(new File(LDLib.getLDLibDir(), "assets/resources/curves"));
    }

    @Override
    public String name() {
        return RESOURCE_NAME;
    }

    @Override
    public void buildDefault() {
        addBuiltinResource("middle", new Curves(new ECBCurves()));

        addBuiltinResource("linear up", new Curves(new ECBCurves(0, 0, 0.1f, 0.3f, 0.9f, 0.7f, 1, 1)));
        addBuiltinResource("linear down", new Curves(new ECBCurves(0, 1, 0.1f, 0.7f, 0.9f, 0.3f, 1, 0)));
        addBuiltinResource("smooth up", new Curves(new ECBCurves(0, 0, 0.1f, 0, 0.9f, 1f, 1, 1)));
        addBuiltinResource("smooth down", new Curves(new ECBCurves(0, 1, 0.1f, 1, 0.9f, 0f, 1, 0)));
        addBuiltinResource("concave", new Curves(new ECBCurves(0, 1, 0.1f, 1, 0.4f, 0f, 0.5F, 0, 0.5F, 0, 0.6f, 0, 0.9f, 1f, 1, 1)));
        addBuiltinResource("convex", new Curves(new ECBCurves(0, 0, 0.1f, 0, 0.4f, 1, 0.5F, 1, 0.5F, 1, 0.6f, 1, 0.9f, 0, 1, 0)));

        addBuiltinResource("random full", new Curves(new ECBCurves(0, 1, 0.1f, 1, 0.9f, 1, 1, 1), new ECBCurves(0, 0, 0.1f, 0, 0.9f, 0, 1, 0)));
        addBuiltinResource("random up", new Curves(new ECBCurves(0, 1, 0.1f, 1, 0.9f, 1, 1, 1), new ECBCurves(0, 0, 0.1f, 0, 0.9f, 1f, 1, 1)));
        addBuiltinResource("random down", new Curves(new ECBCurves(0, 1, 0.1f, 1, 0.9f, 1, 1, 1), new ECBCurves(0, 1, 0.1f, 1, 0.9f, 0f, 1, 0)));
    }

    @Nullable
    @Override
    public Tag serialize(Curves curves) {
        return curves.serializeNBT();
    }

    @Override
    public Curves deserialize(Tag tag) {
        var curves = new Curves();
        if (tag instanceof CompoundTag compoundTag) {
            curves.deserializeNBT(compoundTag);
        }
        return curves;
    }

    @Override
    public ResourceContainer<Curves, ? extends Widget> createContainer(ResourcePanel panel) {
        ResourceContainer<Curves, ImageWidget> container = new ResourceContainer<>(this, panel) {
            @Override
            protected TreeBuilder.Menu getMenu() {
                return super.getMenu().leaf(Icons.ADD_FILE, "add curve", () -> {
                    String randomName = genNewFileName();
                    resource.addBuiltinResource(randomName, new Curves());
                    reBuild();
                }).leaf(Icons.ADD_FILE, "add random curve", () -> {
                    String randomName = genNewFileName();
                    resource.addBuiltinResource(randomName, new Curves(new ECBCurves(), new ECBCurves(0, 0.2f, 0.1f, 0.2f, 0.9f, 0.2f, 1, 0.2f)));
                    reBuild();
                });
            }
        };
        container.setWidgetSupplier(k -> new ImageWidget(0, 0, 60, 15, new GuiTextureGroup(ColorPattern.T_WHITE.rectTexture(),
                        getResource(k).isRandomCurve() ? new RandomCurveTexture(getResource(k).curves0, getResource(k).curves1) : new CurveTexture(getResource(k).curves0))))
                .setDragging(this::getResource, curves -> new GuiTextureGroup(ColorPattern.T_WHITE.rectTexture(),
                        curves.isRandomCurve() ? new RandomCurveTexture(curves.curves0, curves.curves1) : new CurveTexture(curves.curves0)))
                .setOnEdit(k -> openConfigurator(container, k));
        return container;
    }

    private void openConfigurator(ResourceContainer<Curves, ImageWidget> container, Either<String, File> key) {
        container.getPanel().getEditor().getConfigPanel().openConfigurator(ConfigPanel.Tab.RESOURCE, new IConfigurable() {
            @Override
            public void buildConfigurator(ConfiguratorGroup father) {
                var curves = getResource(key);
                if (curves.isRandomCurve()) {
                    var curveLine = new RandomCurveLineWidget(0, 0, 180, 60, curves.curves0, curves.curves1);
                    curveLine.setGridSize(new Size(6, 2));
                    curveLine.setHoverTips(coord -> Component.literal("x: %f, y:%f".formatted(coord.x, coord.y)));
                    curveLine.setBackground(new GuiTextureGroup(ColorPattern.BLACK.rectTexture(), ColorPattern.T_WHITE.borderTexture(-1)));
                    var configurator = new WrapperConfigurator("color", curveLine);
                    father.addConfigurators(configurator);
                } else {
                    var curveLine = new CurveLineWidget(0, 0, 180, 60, curves.curves0);
                    curveLine.setGridSize(new Size(6, 2));
                    curveLine.setHoverTips(coord -> Component.literal("x: %f, y:%f".formatted(coord.x, coord.y)));
                    curveLine.setBackground(new GuiTextureGroup(ColorPattern.BLACK.rectTexture(), ColorPattern.T_WHITE.borderTexture(-1)));
                    var configurator = new WrapperConfigurator("color", curveLine);
                    father.addConfigurators(configurator);
                }
            }
        });
    }

    public static class Curves implements ITagSerializable<CompoundTag> {
        @Nonnull
        public ECBCurves curves0;
        @Nullable
        public ECBCurves curves1;

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

        public CompoundTag serializeNBT() {
            var tag = new CompoundTag();
            tag.put("a", curves0.serializeNBT());
            if (curves1 != null) {
                tag.put("b", curves1.serializeNBT());
            }
            return tag;
        }

        @Override
        public void deserializeNBT(CompoundTag nbt) {
            if (nbt.get("a") instanceof ListTag list) {
                curves0.deserializeNBT(list);
            }
            if (nbt.get("b") instanceof ListTag list) {
                if (curves1 == null) {
                    curves1 = new ECBCurves();
                }
                curves1.deserializeNBT(list);
            }
        }
    }
}
