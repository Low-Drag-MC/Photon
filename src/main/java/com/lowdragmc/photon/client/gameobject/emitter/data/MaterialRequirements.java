package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * An inspector row listing what the emitter's materials read but the emitter does not provide (a custom-data
 * stream, or particle data a trail or beam only carries when instanced), with a button that provides it.
 */
@OnlyIn(Dist.CLIENT)
public final class MaterialRequirements {
    private MaterialRequirements() {
    }

    /**
     * @param cpuCarriesData whether the emitter's CPU path carries particle data (particle emitters do)
     */
    public static void addRow(ConfiguratorGroup father, Supplier<List<MaterialSetting>> materials,
                              AdditionalGPUDataSetting data, boolean cpuCarriesData,
                              BooleanSupplier instanced, Consumer<Boolean> setInstanced) {
        // the reason goes in a wrapped label below: a long row label pushes the button out of a narrow inspector
        var row = new Configurator("photon.material_requirements.title");
        row.inlineContainer.addChild(new Button().setText("photon.material_requirements.fix").setOnClick(e -> {
            fix(materials.get(), data, cpuCarriesData, instanced, setInstanced);
            row.notifyChanges();
        }));
        var reason = new Label();
        reason.textStyle(style -> style.textWrap(TextWrap.WRAP).adaptiveHeight(true));
        row.addChild(reason);
        father.addConfiguratorAt(row, 0);
        row.setDisplay(false);
        var shown = new Component[1];
        father.addEventListener(UIEvents.TICK, e -> {
            var problem = describe(materials.get(), data, cpuCarriesData, instanced);
            row.setDisplay(problem != null);
            if (problem != null && !problem.equals(shown[0])) {
                shown[0] = problem;
                reason.setText(problem);
            }
        });
    }

    /** Channels each custom-data stream needs, over all the materials (0 = not read). */
    public static int[] neededChannels(List<MaterialSetting> materials) {
        var needed = new int[AdditionalGPUDataSetting.MAX_CUSTOM_DATA];
        for (var setting : materials) {
            var reads = setting.getMaterial().requiredCustomChannels();
            for (int i = 0; i < Math.min(reads.length, needed.length); i++) {
                needed[i] = Math.max(needed[i], reads[i]);
            }
        }
        return needed;
    }

    public static boolean needsInstancing(List<MaterialSetting> materials, boolean cpuCarriesData, boolean instanced) {
        if (cpuCarriesData || instanced) return false;
        for (var setting : materials) {
            var material = setting.getMaterial();
            if (material.getUsedChannelMask() != 0 || material.usesCustomData()) return true;
        }
        return false;
    }

    @Nullable
    public static Component describe(List<MaterialSetting> materials, AdditionalGPUDataSetting data,
                                     boolean cpuCarriesData, BooleanSupplier instanced) {
        MutableComponent text = null;
        var needed = neededChannels(materials);
        var streams = data.customDataStreams();
        for (int i = 0; i < needed.length; i++) {
            if (needed[i] == 0) continue;
            boolean defined = data.isEnable() && i < streams.size()
                    && (streams.get(i).getType() == CustomData.Type.COLOR || streams.get(i).getChannelCount() >= needed[i]);
            if (defined) continue;
            var line = Component.translatable("photon.material_requirements.stream", i, needed[i]);
            text = text == null ? line : text.append(" ").append(line);
        }
        if (needsInstancing(materials, cpuCarriesData, instanced.getAsBoolean())) {
            var line = Component.translatable("photon.material_requirements.instancing");
            text = text == null ? line : text.append(" ").append(line);
        }
        return text;
    }

    public static void fix(List<MaterialSetting> materials, AdditionalGPUDataSetting data, boolean cpuCarriesData,
                           BooleanSupplier instanced, Consumer<Boolean> setInstanced) {
        var needed = neededChannels(materials);
        var streams = data.customDataStreams();
        for (int i = 0; i < needed.length; i++) {
            if (needed[i] == 0) continue;
            data.setEnable(true);
            while (streams.size() <= i) streams.add(new CustomData());
            var stream = streams.get(i);
            if (stream.getType() == CustomData.Type.VECTOR && stream.getChannelCount() < needed[i]) {
                stream.setChannelCount(needed[i]);
            }
        }
        if (needsInstancing(materials, cpuCarriesData, instanced.getAsBoolean())) {
            setInstanced.accept(true);
        }
    }
}
