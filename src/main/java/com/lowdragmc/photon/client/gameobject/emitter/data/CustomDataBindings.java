package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.client.fx.timeline.property.ColorPropertyType;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigPropertyType;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigValueType;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.RuntimeBinding;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Shared timeline plumbing for an emitter's custom-data <b>value</b> channels. Custom-data streams are a
 * per-config dynamic list (variable stream / channel count), so their animatable properties can't live in
 * the static {@code RUNTIME_BINDINGS} — every kind enumerates them per target instance. This helper holds
 * the one path/label/slot convention so tile/beam/trail/aratrail don't each copy it; a kind only supplies
 * <em>how to reach its {@link CustomDataRuntime}</em> from an {@link FXObject}.
 * <p>
 * Path convention: {@code additionalGPUData.customData.<stream>[.<channel>]} — VECTOR channels carry the
 * trailing {@code .<channel>}; a COLOR stream is a single value and omits it. The bound slot is
 * {@code customDataOf.apply(target).slot(stream, channel)}.
 */
public final class CustomDataBindings {

    private CustomDataBindings() {
    }

    /**
     * Append the dynamic custom-data channel property types of {@code streams} onto a copy of {@code base}
     * (the kind's static animatable list). VECTOR streams contribute one {@link ConfigPropertyType} per
     * channel; a COLOR stream one {@link ColorPropertyType}.
     */
    public static List<AnimatedPropertyType> appendAnimatable(List<AnimatedPropertyType> base,
                                                              List<CustomData> streams,
                                                              Function<FXObject, CustomDataRuntime> customDataOf) {
        var list = new ArrayList<>(base);
        // only the first MAX_CUSTOM_DATA streams are ever uploaded (see customDataCount()); enumerating
        // beyond that would mint bindings whose runtime slot index is out of the fixed slot grid.
        int streamCount = Math.min(streams.size(), AdditionalGPUDataSetting.MAX_CUSTOM_DATA);
        for (int i = 0; i < streamCount; i++) {
            var stream = streams.get(i);
            if (stream.getType() == CustomData.Type.COLOR) {
                list.add(ColorPropertyType.fromBinding(
                        binding(i, 0, ConfigValueType.COLOR, "photon.custom_data.color", customDataOf)));
            } else {
                int channelCount = Math.min(stream.getChannelCount(), CustomData.MAX_CHANNELS);
                for (int c = 0; c < channelCount; c++) {
                    list.add(ConfigPropertyType.fromBinding(
                            binding(i, c, ConfigValueType.NUMBER_FUNCTION, stream.getChannelName(c), customDataOf)));
                }
            }
        }
        return list;
    }

    /**
     * Parse a custom-data channel {@code path} back into its runtime binding (dynamic resolve at apply
     * time), or {@code null} if {@code path} isn't a custom-data channel.
     */
    @Nullable
    public static RuntimeBinding resolve(String path, Function<FXObject, CustomDataRuntime> customDataOf) {
        var parts = path.split("\\.");
        if (parts.length < 3 || !"additionalGPUData".equals(parts[0]) || !"customData".equals(parts[1])) {
            return null;
        }
        try {
            int stream = Integer.parseInt(parts[2]);
            boolean color = parts.length < 4;
            int channel = color ? 0 : Integer.parseInt(parts[3]);
            // reject out-of-grid indices (e.g. a stale keyframe from a since-removed 5th stream) so the
            // bound slot never indexes past the fixed [MAX_CUSTOM_DATA][MAX_CHANNELS] runtime grid
            if (stream < 0 || stream >= AdditionalGPUDataSetting.MAX_CUSTOM_DATA
                    || channel < 0 || channel >= CustomData.MAX_CHANNELS) {
                return null;
            }
            return binding(stream, channel, color ? ConfigValueType.COLOR : ConfigValueType.NUMBER_FUNCTION,
                    path, customDataOf);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** One custom-data channel binding: {@code additionalGPUData.customData.<stream>[.<channel>]} → slot. */
    private static RuntimeBinding binding(int stream, int channel, ConfigValueType type, String label,
                                          Function<FXObject, CustomDataRuntime> customDataOf) {
        var path = "additionalGPUData.customData." + stream + (type == ConfigValueType.COLOR ? "" : "." + channel);
        return new RuntimeBinding(path, label, type, o -> customDataOf.apply(o).slot(stream, channel));
    }
}
