package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.configurator.accessors.Vector2fAccessor;
import com.lowdragmc.lowdraglib2.configurator.accessors.Vector3fAccessor;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.ui.BooleanConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.HDRColorConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.NumberConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Menu;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEventListener;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.utils.LocalizationUtils;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.GradientColorConfigurator;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector2f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** KilaMaterial's inspector rows, from stock configurators; a {@code <key>.tips} lang entry becomes the row's tip. */
@OnlyIn(Dist.CLIENT)
public final class KilaUI {
    private KilaUI() {
    }

    public static <C extends Configurator> C add(ConfiguratorGroup group, C configurator, String key) {
        tips(configurator, key);
        group.addConfigurator(configurator);
        return configurator;
    }

    static void tips(Configurator configurator, String key) {
        if (LocalizationUtils.exist(key + ".tips")) configurator.setTips(key + ".tips");
    }

    public static NumberConfigurator number(ConfiguratorGroup group, String key, Supplier<Float> getter,
                                            Consumer<Float> setter, float defaultValue, float min, float max) {
        return add(group, numberRow(key, getter, setter, defaultValue, min, max), key);
    }

    private static NumberConfigurator numberRow(String key, Supplier<Float> getter, Consumer<Float> setter,
                                                float defaultValue, float min, float max) {
        var configurator = new NumberConfigurator(key, getter::get, v -> setter.accept(v.floatValue()), defaultValue, true);
        configurator.setRange(min, max);
        float span = max - min;
        configurator.setWheel(span <= 2 ? 0.01f : span <= 200 ? 0.1f : 1f);
        return configurator;
    }

    public static NumberConfigurator integer(ConfiguratorGroup group, String key, Supplier<Integer> getter,
                                             Consumer<Integer> setter, int defaultValue, int min, int max) {
        return add(group, integerRow(key, getter, setter, defaultValue, min, max), key);
    }

    private static NumberConfigurator integerRow(String key, Supplier<Integer> getter, Consumer<Integer> setter,
                                                 int defaultValue, int min, int max) {
        var configurator = new NumberConfigurator(key, getter::get, v -> setter.accept(v.intValue()), defaultValue, true);
        configurator.setRange(min, max).setType(ConfigNumber.Type.INTEGER);
        return configurator;
    }

    public static BooleanConfigurator bool(ConfiguratorGroup group, String key, Supplier<Boolean> getter,
                                           Consumer<Boolean> setter) {
        return add(group, new BooleanConfigurator(key, getter, setter, false, true), key);
    }

    /** Entries read {@code <key>.<constant>} in lower case. */
    public static <E extends Enum<E>> SelectorConfigurator<E> choice(ConfiguratorGroup group, String key, E[] values,
                                                                    Supplier<E> getter, Consumer<E> setter) {
        return add(group, new SelectorConfigurator<>(key, getter, setter, values[0], true, Arrays.asList(values),
                e -> key + "." + e.name().toLowerCase()), key);
    }

    public static Configurator vec3(ConfiguratorGroup group, String key, Supplier<Vector3f> getter, Consumer<Vector3f> setter) {
        return add(group, new Vector3fAccessor().create(key, getter, setter, true, null, null), key);
    }

    public static Configurator vec2(ConfiguratorGroup group, String key, Supplier<Vector2f> getter, Consumer<Vector2f> setter) {
        return add(group, new Vector2fAccessor().create(key, getter, setter, true, null, null), key);
    }

    public static HDRColorConfigurator hdr(ConfiguratorGroup group, String key, Supplier<HDRColor> getter,
                                           Consumer<HDRColor> setter, boolean alpha) {
        return add(group, new HDRColorConfigurator(key, getter, setter, HDRColor.white(), true, alpha), key);
    }

    public static Configurator gradient(ConfiguratorGroup group, String key, Supplier<GradientColor> getter,
                                        Consumer<GradientColor> setter) {
        var row = new Configurator(key);
        row.inlineContainer.addChild(new GradientColorConfigurator("", getter, setter, new GradientColor(), true));
        return add(group, row, key);
    }

    /** Shows {@code row} only while {@code condition} holds; the group ticks it, as a hidden row does not tick. */
    public static <C extends UIElement> C showWhen(UIElement group, C row, BooleanSupplier condition) {
        row.setDisplay(condition.getAsBoolean());
        group.addEventListener(UIEvents.TICK, e -> {
            boolean shown = condition.getAsBoolean();
            if (row.isDisplayed() != shown) row.setDisplay(shown);
        });
        return row;
    }

    public static ConfiguratorGroup subGroup(ConfiguratorGroup group, String key, boolean collapsed) {
        var sub = new ConfiguratorGroup(key, collapsed);
        tips(sub, key);
        group.addConfigurator(sub);
        return sub;
    }

    /** The editor's icon button: the theme tints a white icon. */
    public static Button iconButton(IGuiTexture icon, String tip, UIEventListener onClick) {
        var button = new Button().noText().addPreIcon(icon);
        button.setOnClick(onClick);
        button.layout(layout -> layout.setAspectRatio(1));
        button.addClass("__white_icon__");
        button.style(style -> style.appendTooltips(Component.translatable(tip)));
        return button;
    }

    /** A number row with a link button that binds it to particle data; the binding shows under it while bound. */
    public static void driver(ConfiguratorGroup group, String key, KilaDriver driver, float min, float max) {
        var row = add(group, numberRow(key, () -> driver.value, v -> driver.value = v, driver.value, min, max), key);
        row.addClass("__kila-driver__");
        var link = iconButton(Icons.LINK, "kila.driver.bind", e -> openMenu(e.currentElement, e.x, e.y, sourceMenu(row, driver)));
        link.addClass("__kila-driver_link__");
        row.lineContainer.addChildAt(link, row.tip.getSiblingIndex());

        var binding = new ConfiguratorGroup().hideTitle().setCollapse(false);
        binding.addClass("__kila-driver_binding__");
        add(binding, new SelectorConfigurator<>("kila.driver.source", () -> driver.source, source -> bind(driver, source),
                KilaDriver.Source.CONSTANT, true, Arrays.asList(KilaDriver.Source.values()), KilaDriver.Source::langKey),
                "kila.driver.source");
        add(binding, numberRow("kila.driver.scale", () -> driver.scale, v -> driver.scale = v, 1, -100, 100), "kila.driver.scale");
        showWhen(binding, add(binding, integerRow("kila.driver.stream", () -> driver.stream, v -> driver.stream = v, 0, 0, 3),
                "kila.driver.stream"), driver::readsCustomData);
        showWhen(binding, add(binding, integerRow("kila.driver.channel", () -> driver.channel, v -> driver.channel = v, 0, 0, 3),
                "kila.driver.channel"), driver::readsCustomData);
        row.addChild(binding);
        showWhen(row, binding, driver::isDriven);
    }

    private static void bind(KilaDriver driver, KilaDriver.Source source) {
        // a scale of 0 would make the binding look broken
        if (driver.source == KilaDriver.Source.CONSTANT && source != KilaDriver.Source.CONSTANT && driver.scale == 0) {
            driver.scale = 1;
        }
        driver.source = source;
    }

    private static TreeBuilder.Menu sourceMenu(Configurator row, KilaDriver driver) {
        var menu = TreeBuilder.Menu.start();
        for (var source : KilaDriver.Source.values()) {
            menu.leaf(source == driver.source ? Icons.CHECK_SPRITE : IGuiTexture.EMPTY, source.langKey(), () -> {
                bind(driver, source);
                row.notifyChanges();
            });
        }
        return menu;
    }

    /** What a slot reads out of its texels: colour, one channel, or red and green as a direction. */
    public enum TextureKind {
        // ⚠️ ordinals MIRRORED IN photon:kila_preview.fsh (KilaPreviewOpts.y)
        COLOR, SCALAR, VECTOR
    }

    public static KilaTextureField texture(ConfiguratorGroup group, String key, KilaTexture texture, TextureKind kind,
                                           boolean uvChain) {
        return texture(group, key, texture, kind, uvChain, false);
    }

    /** A texture slot, what it reads, then its uv chain; a {@code procedural} slot can also be a shader noise. */
    public static KilaTextureField texture(ConfiguratorGroup group, String key, KilaTexture texture, TextureKind kind,
                                           boolean uvChain, boolean procedural) {
        var field = add(group, new KilaTextureField(key, texture, kind, procedural), key);
        if (kind == TextureKind.COLOR) {
            choice(group, "kila.texture.color_mode", KilaTexture.ColorMode.values(), () -> texture.colorMode,
                    v -> texture.colorMode = v);
        } else if (kind == TextureKind.SCALAR) {
            showWhen(group, choice(group, "kila.texture.channel", KilaTexture.Channel.values(), () -> texture.channel,
                    v -> texture.channel = v), () -> !texture.isProcedural());
        }
        if (procedural) {
            showWhen(group, number(group, "kila.texture.noise_scale", () -> texture.noiseScale,
                    v -> texture.noiseScale = v, 8, 0.01f, 256), texture::isProcedural);
            showWhen(group, number(group, "kila.texture.noise_motion", () -> texture.noiseMotion,
                    v -> texture.noiseMotion = v, 1, -64, 64), () -> texture.noise == KilaTexture.Noise.VORONOI);
        }
        if (!uvChain) return field;
        vec2(group, "kila.texture.tiling", () -> texture.tiling, v -> texture.tiling = v);
        vec2(group, "kila.texture.offset", () -> texture.offset, v -> texture.offset = v);
        vec2(group, "kila.texture.scroll", () -> texture.scroll, v -> texture.scroll = v);
        var details = subGroup(group, "kila.texture.details", true);
        choice(details, "kila.texture.uv_source", KilaTexture.UvSource.values(), () -> texture.uvSource,
                v -> texture.uvSource = v);
        number(details, "kila.texture.rotation", () -> texture.rotation, v -> texture.rotation = v, 0, -360, 360);
        number(details, "kila.texture.rotation_speed", () -> texture.rotationSpeed, v -> texture.rotationSpeed = v,
                0, -3600, 3600);
        // kept for a noise too: clip still cuts it off outside the unit square
        choice(details, "kila.texture.wrap_u", KilaTexture.Wrap.values(), () -> texture.wrapU, v -> texture.wrapU = v);
        choice(details, "kila.texture.wrap_v", KilaTexture.Wrap.values(), () -> texture.wrapV, v -> texture.wrapV = v);
        showWhen(details, bool(details, "kila.texture.nearest", () -> texture.nearest, v -> texture.nearest = v),
                () -> !texture.isProcedural());
        bool(details, "kila.texture.polar", () -> texture.polar, v -> texture.polar = v);
        BooleanSupplier polar = () -> texture.polar;
        showWhen(details, vec2(details, "kila.texture.polar_center", () -> texture.polarCenter,
                v -> texture.polarCenter = v), polar);
        showWhen(details, number(details, "kila.texture.polar_radial", () -> texture.polarRadial,
                v -> texture.polarRadial = v, 1, -16, 16), polar);
        showWhen(details, number(details, "kila.texture.polar_angular", () -> texture.polarAngular,
                v -> texture.polarAngular = v, 1, -16, 16), polar);
        return field;
    }

    public static void openMenu(UIElement origin, float x, float y, TreeBuilder.Menu builder) {
        if (builder.isEmpty()) return;
        var mui = origin.getModularUI();
        if (mui == null) return;
        var root = mui.ui.rootElement;
        var menu = new Menu<>(builder.build(), TreeBuilder.Menu::uiProvider);
        menu.layout(layout -> {
            layout.left(x - root.getContentX());
            layout.top(y - root.getContentY());
        });
        root.addChildren(menu);
        menu.setHoverTextureProvider(TreeBuilder.Menu::hoverTextureProvider)
                .setOnNodeClicked(TreeBuilder.Menu::handle)
                .setCloseOnClick(true);
    }

    /** {@code assets/<namespace>/<path>} on disk to the resource location it is. */
    @Nullable
    public static ResourceLocation textureFromFile(@Nullable File file) {
        if (file == null || !file.isFile()) return null;
        var path = file.getPath().replace('\\', '/');
        int assets = path.indexOf("assets/");
        if (assets < 0) return null;
        var relative = path.substring(assets + "assets/".length());
        int slash = relative.indexOf('/');
        if (slash < 0) return null;
        var location = relative.substring(0, slash) + ":" + relative.substring(slash + 1);
        return LDLib2.isValidResourceLocation(location) ? ResourceLocation.parse(location) : null;
    }
}
