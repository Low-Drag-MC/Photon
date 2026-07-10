package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.registry.ILDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.photon.PhotonRegistries;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Supplier;

/**
 * Where a particle model's geometry comes from: a baked JSON model ({@link JsonModelSource}) or a
 * runtime-parsed OBJ file ({@link ObjModelSource}). The registry itself is the format extension
 * point — a future glTF loader is just another {@code @LDLRegisterClient} implementation. Both the
 * mesh emission shape ({@code MeshData}) and the Model render mode ({@code ParticleRendererSetting})
 * consume sources through {@link #getMesh()}, which resolves via the shared {@link PhotonMeshCache}.
 * Implementations must implement {@code equals}/{@code hashCode} over all mesh-affecting config
 * fields — render-pass batching and {@code MeshData} equality depend on it.
 */
public interface IModelSource extends IConfigurable, IPersistedSerializable, ILDLRegisterClient<IModelSource, Supplier<IModelSource>> {
    Codec<IModelSource> CODEC = PhotonRegistries.MODEL_SOURCES.optionalCodec().dispatch(ILDLRegisterClient::getRegistryHolderOptional,
            optional -> optional.map(holder -> PersistedParser.createCodec(holder.value()).fieldOf("data"))
                    .orElseGet(() -> MapCodec.unit(JsonModelSource::new)));

    default CompoundTag serializeWrapper() {
        return (CompoundTag) CODEC.encodeStart(NbtOps.INSTANCE, this).result().orElse(new CompoundTag());
    }

    static IModelSource deserializeWrapper(Tag tag) {
        // legacy (pre-v5) renderer payload: a bare IModelRenderer compound {modelLocation: "..."}.
        // exported .fx files carry no version and bypass the project datafixer, so keep this inline.
        if (tag instanceof CompoundTag compound && !compound.contains("type")
                && compound.contains("modelLocation", Tag.TAG_STRING)) {
            return new JsonModelSource(ResourceLocation.parse(compound.getString("modelLocation")));
        }
        return CODEC.parse(NbtOps.INSTANCE, tag).result().orElseGet(JsonModelSource::new);
    }

    /** The source's geometry via the shared cache; {@link PhotonMesh#EMPTY} when unavailable. */
    PhotonMesh getMesh();

    /** Drop this source's cache entry so the next {@link #getMesh()} reloads. */
    void invalidate();

    /**
     * True when vertex UVs are atlas coordinates (baked JSON models). Drives the
     * {@code shade}/{@code useBlockUV} options — meaningless for raw-UV sources.
     */
    default boolean hasAtlasUV() {
        return false;
    }

    IModelSource copy();
}
