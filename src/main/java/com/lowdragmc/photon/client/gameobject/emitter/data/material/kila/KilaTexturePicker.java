package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.photon.Photon;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;

/** A texture slot's picker: a click applies at once so the material preview shows it, a double click also closes. */
@OnlyIn(Dist.CLIENT)
public final class KilaTexturePicker {
    private static final int TILE = 40;
    private static final int RECENT_LIMIT = 8;
    private static final Deque<ResourceLocation> RECENT = new ArrayDeque<>();
    public static final List<ResourceLocation> PARTICLES = List.of(
            Photon.id("textures/particle/circle.png"), Photon.id("textures/particle/smoke.png"),
            Photon.id("textures/particle/ring.png"), Photon.id("textures/particle/laser.png"),
            Photon.id("textures/particle/kila_tail.png"), Photon.id("textures/particle/thaumcraft.png"));

    private KilaTexturePicker() {
    }

    static void remember(ResourceLocation location) {
        RECENT.remove(location);
        RECENT.addFirst(location);
        while (RECENT.size() > RECENT_LIMIT) RECENT.removeLast();
    }

    /** A starting scale that shows the noise's character. */
    static float defaultScale(KilaTexture.Noise noise) {
        return switch (noise) {
            case SIMPLE -> 12;
            case GRADIENT -> 5;
            default -> 6;
        };
    }

    public static Dialog open(KilaTextureField field, float x, float y) {
        var texture = field.texture;
        var original = texture.getTexture();
        var originalNoise = texture.noise;
        var originalScale = texture.noiseScale;

        var list = KilaDialogs.list();
        list.addClass("__kila-picker__");
        var dialog = new Dialog().windowMode(x, y, 236, 240);
        dialog.setTitle("kila.picker.title");

        if (field.procedural) {
            var noises = KilaDialogs.section(list, "kila.picker.procedural");
            for (var noise : KilaTexture.Noise.values()) {
                if (noise == KilaTexture.Noise.TEXTURE) continue;
                var candidate = new KilaTexture(KilaTextures.WHITE).noise(noise, defaultScale(noise));
                candidate.noiseMotion = 0;
                noises.addChild(tile(dialog, field, candidate,
                        Component.translatable("kila.texture.noise." + noise.name().toLowerCase()),
                        () -> texture.noise == noise, () -> field.pick(noise)));
            }
        }
        if (!RECENT.isEmpty()) {
            var recent = KilaDialogs.section(list, "kila.picker.recent");
            for (var location : List.copyOf(RECENT)) {
                recent.addChild(textureTile(dialog, field, location));
            }
        }
        var builtin = KilaDialogs.section(list, "kila.picker.builtin");
        for (var location : KilaTextures.ALL) {
            builtin.addChild(textureTile(dialog, field, location));
        }
        var particles = KilaDialogs.section(list, "kila.picker.particles");
        for (var location : PARTICLES) {
            particles.addChild(textureTile(dialog, field, location));
        }

        dialog.addContent(list);
        dialog.addButton(new Button().setText("kila.texture.file").setOnClick(e -> {
            dialog.close();
            field.browseFile();
        }));
        dialog.addButton(new Button().setText("ldlib.gui.tips.confirm").setOnClick(e -> dialog.close())
                .addClass("__confirm-button__"));
        dialog.addButton(new Button().setText("ldlib.gui.tips.cancel").setOnClick(e -> {
            field.restore(original, originalNoise, originalScale);
            dialog.close();
        }).addClass("__cancel-button__"));
        dialog.show(field.getModularUI());
        return dialog;
    }

    private static UIElement textureTile(Dialog dialog, KilaTextureField field, ResourceLocation location) {
        var candidate = new KilaTexture(location);
        var texture = field.texture;
        return tile(dialog, field, candidate, KilaTextureField.displayName(location),
                () -> !texture.isProcedural() && location.equals(texture.getTexture()), () -> field.pick(location));
    }

    private static UIElement tile(Dialog dialog, KilaTextureField field, KilaTexture candidate, Component name,
                                  BooleanSupplier selected, Runnable pick) {
        candidate.channel = field.texture.channel;
        candidate.colorMode = field.texture.colorMode;
        candidate.nearest = field.texture.nearest;
        var cell = KilaDialogs.cell(KilaDialogs.fill(new KilaSlotPreview(candidate, field.kind, false)), name, TILE, selected);
        cell.style(style -> style.tooltips(name));
        cell.addEventListener(UIEvents.MOUSE_DOWN, event -> {
            if (event.button == 0) pick.run();
        });
        cell.addEventListener(UIEvents.DOUBLE_CLICK, event -> dialog.close());
        return cell;
    }
}
