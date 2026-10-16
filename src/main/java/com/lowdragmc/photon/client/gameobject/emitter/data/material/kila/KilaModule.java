package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.photon.client.gameobject.emitter.data.ToggleGroup;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BiConsumer;

/** One switchable feature of {@link KilaMaterial}; off, it adds nothing to the program. */
@OnlyIn(Dist.CLIENT)
public abstract class KilaModule extends ToggleGroup {
    public enum Category {
        TEXTURE, COLOR, ALPHA, VERTEX, LIGHTING
    }

    public abstract String id();

    public abstract Category category();

    public String langKey() {
        return "kila.module." + id();
    }

    /** Defines, uniforms, samplers and parameter groups of the program variant — only called when enabled. */
    public abstract void declare(KilaLayout.Builder layout);

    /** Per draw: stage this module's uniforms. Only called when enabled and part of the variant. */
    public abstract void apply(ShaderInstance shader, MaterialContext context, float[] scratch);

    /** The parameters this module lets particle data drive. */
    public void drivers(BiConsumer<KilaParam, KilaDriver> out) {
    }

    /** Every texture this module can sample — which ones share a sampler is part of the variant. */
    public void textures(java.util.function.Consumer<KilaTexture> out) {
    }

    /** What, besides being enabled, changes the program variant; mixed into the material's structure hash. */
    public long structure() {
        return 0;
    }

    public abstract void buildModuleConfigurator(ConfiguratorGroup group);

    /**
     * The header toggle follows {@link #isEnable()}, which the add menu and presets change too. Rows are hand
     * built: the fields are {@code @Persisted}, not {@code @Configurable}.
     */
    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        var toggle = new Toggle().setOn(isEnable(), false).setOnToggleChanged(isOn -> {
            setEnable(isOn);
            father.setCanCollapse(isOn);
            father.setCollapse(!isOn);
            father.notifyChanges();
        });
        toggle.setText("");
        toggle.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 0) e.stopPropagation();
        });
        father.setCanCollapse(isEnable());
        father.lineContainer.addChildAt(toggle, 1);
        father.addEventListener(UIEvents.TICK, e -> {
            if (toggle.getValue() != isEnable()) toggle.setOn(isEnable(), false);
            if (father.isCanCollapse() != isEnable()) {
                father.setCanCollapse(isEnable());
                father.setCollapse(!isEnable());
            }
        });
        buildModuleConfigurator(father);
    }

    /** Settings survive switching the module off and on again, also across a save. */
    @Override
    public boolean skipDisableSerialize() {
        return false;
    }
}
