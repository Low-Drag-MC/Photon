package com.lowdragmc.photon.client.postfx.graph;

import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandle;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandleHelpers;
import com.lowdragmc.photon.client.postfx.graph.gui.PassOptionConfigurators;

/**
 * Port types of the effect render graph. {@link #TEXTURE} is the opaque "render target flowing
 * between passes" type — backed by its own marker class so {@code TypeUtils.isAssignableFrom}
 * refuses every wire except TEXTURE→TEXTURE. It carries no CPU value: bindings are resolved by the
 * compiler (scene inputs / pass outputs) and materialized as pooled targets at execution.
 */
public final class RenderGraphTypes {

    /** Marker value class behind {@link #TEXTURE}; never carries real data. */
    public record TextureValue() {
        private static final TextureValue DEFAULT = new TextureValue();

        public static TextureValue defaultValue() {
            return DEFAULT;
        }
    }

    public static final TypeHandle TEXTURE =
            TypeHandleHelpers.customType(TextureValue.class, "PHOTON_RG_TEXTURE", "Texture");

    /** The pass node's composite size option ({@link PassSize}). */
    public static final TypeHandle SIZE =
            TypeHandleHelpers.customType(PassSize.class, "PHOTON_RG_SIZE", "Pass Size");

    /** The pass node's composite source option ({@link PassSource}). */
    public static final TypeHandle SOURCE =
            TypeHandleHelpers.customType(PassSource.class, "PHOTON_RG_SOURCE", "Pass Source");

    static {
        // a non-null default avoids NPEs when the editor builds a port's constant editor
        TypeHandleHelpers.setCustomDefaultValue(TEXTURE, TextureValue::defaultValue);
        TypeHandleHelpers.setCustomColor(TEXTURE, 0xFFDD8844);
        TypeHandleHelpers.setCustomDefaultValue(SIZE, () -> PassSize.DEFAULT);
        TypeHandleHelpers.setCustomDefaultValue(SOURCE, () -> PassSource.DEFAULT);
        TypeHandleHelpers.setCustomConfigurable(SIZE, (vc, type) ->
                PassOptionConfigurators.sizeConfigurator(vc));
        TypeHandleHelpers.setCustomConfigurable(SOURCE, (vc, type) ->
                PassOptionConfigurators.sourceConfigurator(vc));
    }

    private RenderGraphTypes() {}
}
