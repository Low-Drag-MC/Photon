package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.registry.ILDLRegisterClient;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.photon.PhotonRegistries;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import dev.vfyjxf.taffy.style.AlignItems;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/29
 * @implNote Material
 */
@ParametersAreNonnullByDefault
public interface IMaterial extends IConfigurable, IPersistedSerializable, ILDLRegisterClient<IMaterial, Supplier<IMaterial>> {
    // region builtin material
    @LDLRegisterClient(name = "missing", registry = "photon:material")
    final class MissingMaterial implements IMaterial {
        @Override
        public IGuiTexture preview() {
            return IGuiTexture.MISSING_TEXTURE;
        }
    };
    MissingMaterial MISSING = new MissingMaterial();
    // endregion

    Codec<IMaterial> CODEC = PhotonRegistries.MATERIALS.optionalCodec().dispatch(ILDLRegisterClient::getRegistryHolderOptional,
            optional -> optional.map(holder -> PersistedParser.createCodec(holder.value()).fieldOf("data"))
                    .orElseGet(() -> MapCodec.unit(MISSING)));

    @Nullable
    default CompoundTag serializeWrapper() {
        return (CompoundTag) CODEC.encodeStart(NbtOps.INSTANCE, this).result().orElse(null);
    }

    static IMaterial deserializeWrapper(Tag tag) {
        return CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(MISSING);
    }

    // 26.1 note: the 1.21 render seam `ShaderInstance begin(MaterialContext)` / `end(MaterialContext)`
    // is gone with ShaderInstance itself. TODO(M2): materials contribute a RenderPipeline (+ samplers
    // and std140 uniforms) to the pipeline-variant cache instead of imperative begin/end.

    IGuiTexture preview();

    default IMaterial copy() {
        return CODEC.encodeStart(NbtOps.INSTANCE, this).result()
                .flatMap(tag -> CODEC.parse(NbtOps.INSTANCE, tag).result())
                .orElse(MISSING);
    }

    default void createPreview(ConfiguratorGroup father) {
        father.addConfigurators(new Configurator("ldlib.gui.editor.group.preview")
                .addChild(new UIElement().layout(layout -> {
                            layout.setAspectRatio(1.0f);
                            layout.widthPercent(80);
                            layout.alignSelf(AlignItems.CENTER);
                            layout.paddingAll(3);
                        }).addClass("preview_bg").style(style -> style.backgroundTexture(Sprites.BORDER1_RT1))
                        .moveInlineAsDefault()
                        .addChild(new UIElement().layout(layout -> {
                            layout.widthPercent(100);
                            layout.heightPercent(100);
                        }).style(style -> style.backgroundTexture(DynamicTexture.of(this::preview))))));
    }

    @Override
    default void buildConfigurator(ConfiguratorGroup father) {
        createPreview(father);
        IConfigurable.super.buildConfigurator(father);
    }
}
