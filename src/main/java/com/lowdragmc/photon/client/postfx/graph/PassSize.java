package com.lowdragmc.photon.client.postfx.graph;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * The authoring-time output-size setting of a pass node — one self-contained option value whose
 * codec only persists the fields the chosen {@link SizeSpec.Mode} actually uses (fields equal to
 * their defaults are omitted). The compiler resolves it into a {@link SizeSpec} (with the
 * {@code inputPort} name translated to a resource index).
 */
public record PassSize(SizeSpec.Mode mode, float scale, String inputPort, int width, int height) {

    public static final PassSize DEFAULT = new PassSize(SizeSpec.Mode.SCREEN_RELATIVE, 1f, "", 256, 256);

    private static final Codec<SizeSpec.Mode> MODE_CODEC = Codec.STRING.xmap(
            name -> {
                try {
                    return SizeSpec.Mode.valueOf(name);
                } catch (IllegalArgumentException e) {
                    return SizeSpec.Mode.SCREEN_RELATIVE;
                }
            },
            Enum::name);

    public static final Codec<PassSize> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            MODE_CODEC.optionalFieldOf("mode", SizeSpec.Mode.SCREEN_RELATIVE).forGetter(PassSize::mode),
            Codec.FLOAT.optionalFieldOf("scale", 1f).forGetter(PassSize::scale),
            Codec.STRING.optionalFieldOf("inputPort", "").forGetter(PassSize::inputPort),
            Codec.INT.optionalFieldOf("width", 256).forGetter(PassSize::width),
            Codec.INT.optionalFieldOf("height", 256).forGetter(PassSize::height)
    ).apply(instance, PassSize::new));

    public PassSize withMode(SizeSpec.Mode mode) {
        return new PassSize(mode, scale, inputPort, width, height);
    }

    public PassSize withScale(float scale) {
        return new PassSize(mode, scale > 0 ? scale : 1f, inputPort, width, height);
    }

    public PassSize withInputPort(String inputPort) {
        return new PassSize(mode, scale, inputPort == null ? "" : inputPort, width, height);
    }

    public PassSize withWidth(int width) {
        return new PassSize(mode, scale, inputPort, Math.max(1, width), height);
    }

    public PassSize withHeight(int height) {
        return new PassSize(mode, scale, inputPort, width, Math.max(1, height));
    }
}
