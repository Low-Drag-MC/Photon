package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.lowdraglib2.LDLib2;
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

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.function.Supplier;

/**
 * Where a particle model's geometry comes from. The registry is the format extension point. Both the
 * mesh emission shape and the Model render mode consume sources through {@link #getMesh()}.
 *
 * <p>⚠️ Implementations must implement {@code equals}/{@code hashCode} over all mesh-affecting fields —
 * render-pass batching depends on it.</p>
 *
 * <p>Geometry that changes while it is drawn arrives through {@link DynamicMeshSource} wrapping an
 * {@link IDynamicMesh}, or from a source that is one ({@link AnimatedGltfModelSource}).</p>
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

    /**
     * Map a file under {@code .../assets/<namespace>/<path>} to a ResourceLocation keeping the
     * extension, or null when the file is outside an assets tree. Shared by every file-backed source's
     * "pick a model" dialog — the mapping is about the assets tree, not about any one format.
     */
    @Nullable
    static ResourceLocation getAssetLocationFromFile(File file) {
        String fullPath = file.getPath().replace('\\', '/');
        int assetsIndex = fullPath.indexOf("assets/");
        if (assetsIndex == -1) return null;
        String relativePath = fullPath.substring(assetsIndex + "assets/".length());
        int slashIndex = relativePath.indexOf('/');
        if (slashIndex == -1) return null;
        String location = relativePath.substring(0, slashIndex) + ":" + relativePath.substring(slashIndex + 1);
        return LDLib2.isValidResourceLocation(location) ? ResourceLocation.parse(location) : null;
    }

    /** The source's geometry via the shared cache; {@link PhotonMesh#EMPTY} when unavailable. */
    PhotonMesh getMesh();

    /**
     * The live-geometry provider behind this source, or null when its geometry is static. How the render
     * backend learns a mesh can change under it.
     *
     * <p>⚠️ May go from non-null to null and back: an animated model whose file has not loaded yet has
     * nothing to pose.</p>
     */
    @Nullable
    default IDynamicMesh asDynamic() {
        return null;
    }

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
