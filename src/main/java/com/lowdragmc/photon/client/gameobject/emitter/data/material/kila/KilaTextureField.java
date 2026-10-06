package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.configurator.ui.ValueConfigurator;
import com.lowdragmc.lowdraglib2.editor.ui.browser.AssetBrowser;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SupplierDataSource;
import com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.gui.ui.utils.ModularUITooltipComponent;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.io.File;
import java.util.List;

/** A texture slot's row, its thumbnail drawn the way the slot reads it; click to pick, or drop a .png on it. */
@OnlyIn(Dist.CLIENT)
public class KilaTextureField extends ValueConfigurator<ResourceLocation> {
    private static final float HEIGHT = 28;

    final KilaTexture texture;
    final KilaUI.TextureKind kind;
    final boolean procedural;
    public final UIElement preview = new UIElement();
    @Nullable
    private ModularUITooltipComponent bigPreview;

    public KilaTextureField(String key, KilaTexture texture, KilaUI.TextureKind kind, boolean procedural) {
        super(key, texture::getTexture, location -> assign(texture, location), texture.getTexture(), true);
        this.texture = texture;
        this.kind = kind;
        this.procedural = procedural;
        addClass("__kila-texture-field__");

        var name = new Label().bindDataSource(SupplierDataSource.of(this::describe));
        name.textStyle(style -> style.textAlignVertical(Vertical.CENTER).textWrap(TextWrap.HOVER_ROLL));
        name.layout(layout -> {
            layout.heightPercent(100);
            layout.flex(1);
        });
        name.setOverflowVisible(false);
        var thumbnail = new UIElement().layout(layout -> {
            layout.heightPercent(100);
            layout.setAspectRatio(1);
        }).style(style -> style.backgroundTexture(new KilaSlotPreview(texture, kind, true)));

        preview.layout(layout -> {
            layout.height(HEIGHT);
            layout.paddingAll(2);
            layout.flexDirection(FlexDirection.ROW);
            layout.gapAll(2);
        }).style(style -> style.backgroundTexture(Sprites.RECT_RD_SOLID).overlayTexture(DynamicTexture.of(() ->
                preview.isSelfOrChildHover() ? Sprites.RECT_RD_T_SOLID : IGuiTexture.EMPTY)));
        preview.addClasses("configurator_preview_bg", "__kila-texture-field_preview__").moveInlineAsDefault();
        preview.addChildren(name, thumbnail);
        preview.addEventListener(UIEvents.HOVER_TOOLTIPS, this::onHoverTooltips);
        preview.addEventListener(UIEvents.MOUSE_DOWN, event -> {
            if (event.button == 0) KilaTexturePicker.open(this, event.x, event.y);
        });
        inlineContainer.addChild(preview);

        setCanDropPredicate(object -> locationOf(object) != null);
    }

    private static void assign(KilaTexture texture, @Nullable ResourceLocation location) {
        if (location == null) return;
        texture.texture = location;
        texture.noise = KilaTexture.Noise.TEXTURE;
        KilaTexturePicker.remember(location);
    }

    public KilaTexture texture() {
        return texture;
    }

    private Component describe() {
        if (texture.isProcedural()) {
            return Component.translatable("kila.texture.noise." + texture.noise.name().toLowerCase());
        }
        return displayName(texture.getTexture());
    }

    /** A built-in texture by its translated name, anything else by its file name. */
    static Component displayName(ResourceLocation location) {
        if (KilaTextures.ALL.contains(location)) {
            return Component.translatable("kila.texture.builtin." + KilaTextures.nameOf(location));
        }
        var path = location.getPath();
        var name = path.substring(path.lastIndexOf('/') + 1);
        return Component.literal(name.endsWith(".png") ? name.substring(0, name.length() - 4) : name);
    }

    private void onHoverTooltips(UIEvent event) {
        if (bigPreview == null) {
            bigPreview = new ModularUITooltipComponent(new UIElement().layout(layout -> {
                layout.width(100);
                layout.height(100);
            }).style(style -> style.backgroundTexture(new KilaSlotPreview(texture, kind, true))));
        }
        var path = texture.isProcedural() ? describe() : Component.literal(texture.getTexture().toString());
        event.hoverTooltips = new HoverTooltips(List.of(path, Component.translatable("kila.texture.click_to_pick")),
                bigPreview, null, null);
    }

    @Override
    protected void onDropObject(@Nullable Object object) {
        var location = locationOf(object);
        if (location != null) pick(location);
    }

    void pick(ResourceLocation location) {
        updateValueActively(location);
    }

    void pick(KilaTexture.Noise noise) {
        if (!texture.isProcedural()) texture.noiseScale = KilaTexturePicker.defaultScale(noise);
        texture.noise = noise;
        notifyChanges();
    }

    void restore(ResourceLocation location, KilaTexture.Noise noise, float scale) {
        texture.texture = location;
        texture.noise = noise;
        texture.noiseScale = scale;
        onValueUpdatePassively(location);
        notifyChanges();
    }

    void browseFile() {
        var mui = getModularUI();
        if (mui == null) return;
        Dialog.showFileDialog("ldlib.gui.editor.tips.select_image", LDLib2.getAssetsDir(), true,
                Dialog.suffixFilter(".png"), file -> {
                    var location = KilaUI.textureFromFile(file);
                    if (location != null) pick(location);
                }).show(mui.ui.rootElement);
    }

    private void reset() {
        if (defaultValue != null) pick(defaultValue);
    }

    /** What a drop onto the slot turns into: a .png under some {@code assets/<namespace>/}, or a sprite's image. */
    @Nullable
    public static ResourceLocation locationOf(@Nullable Object dropped) {
        if (dropped instanceof ResourceLocation location) return location;
        if (dropped instanceof SpriteTexture sprite) return sprite.getImageLocation();
        if (dropped instanceof File file) return KilaUI.textureFromFile(file);
        if (dropped instanceof AssetBrowser.DraggedAssets assets) {
            for (var file : assets.files()) {
                if (file.getName().toLowerCase().endsWith(".png")) {
                    var location = KilaUI.textureFromFile(file);
                    if (location != null) return location;
                }
            }
        }
        return null;
    }

    @Override
    protected TreeBuilder.Menu createMenu() {
        var menu = super.createMenu();
        menu.leaf(Icons.PICTURE, "kila.texture.pick", () -> KilaTexturePicker.open(this, preview.getPositionX(), preview.getPositionY()));
        menu.leaf(Icons.OPEN_FILE, "kila.texture.file", this::browseFile);
        menu.leaf(Icons.REPLAY, "kila.texture.reset", this::reset);
        return menu;
    }
}
