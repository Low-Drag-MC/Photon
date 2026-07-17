package com.lowdragmc.photon.client.postfx.graph;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * What a pass node dispatches — one self-contained option value:
 * <ul>
 *   <li>{@link Type#GRAPH} — a fullscreen shader graph resource ({@code graph} = the
 *       {@code IResourcePath} in its {@code type(path)} string form);</li>
 *   <li>{@link Type#CUSTOM_SHADER} — a hand-written core shader ({@code shader} = the shader
 *       json's resource location). Reserved: the compiler rejects it until the custom-shader
 *       phase lands, but the data model and UI slot exist so adding it isn't a breaking change.</li>
 * </ul>
 */
public record PassSource(Type type, String graph, String shader) {

    public enum Type { GRAPH, CUSTOM_SHADER }

    public static final PassSource DEFAULT = new PassSource(Type.GRAPH, "", "");

    private static final Codec<Type> TYPE_CODEC = Codec.STRING.xmap(
            name -> {
                try {
                    return Type.valueOf(name);
                } catch (IllegalArgumentException e) {
                    return Type.GRAPH;
                }
            },
            Enum::name);

    public static final Codec<PassSource> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            TYPE_CODEC.optionalFieldOf("type", Type.GRAPH).forGetter(PassSource::type),
            Codec.STRING.optionalFieldOf("graph", "").forGetter(PassSource::graph),
            Codec.STRING.optionalFieldOf("shader", "").forGetter(PassSource::shader)
    ).apply(instance, PassSource::new));

    public static PassSource ofGraph(String pathWithType) {
        return new PassSource(Type.GRAPH, pathWithType == null ? "" : pathWithType, "");
    }

    public static PassSource ofShader(String shaderLocation) {
        return new PassSource(Type.CUSTOM_SHADER, "", shaderLocation == null ? "" : shaderLocation);
    }

    public PassSource withType(Type type) {
        return new PassSource(type, graph, shader);
    }

    public PassSource withGraph(String graph) {
        return new PassSource(type, graph == null ? "" : graph, shader);
    }

    public PassSource withShader(String shader) {
        return new PassSource(type, graph, shader == null ? "" : shader);
    }
}
