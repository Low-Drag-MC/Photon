package com.lowdragmc.photon.client.gameobject.emitter.data.shape;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.registry.ILDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.joml.Vector3f;
import net.minecraft.nbt.CompoundTag;

import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote IShape
 */
public interface IShape extends IConfigurable, IPersistedSerializable, ILDLRegisterClient<IShape, Supplier<IShape>> {
    Codec<IShape> CODEC = PhotonRegistries.SHAPES.optionalCodec().dispatch(ILDLRegisterClient::getRegistryHolderOptional,
            optional -> optional.map(holder -> PersistedParser.createCodec(holder.value()).fieldOf("data"))
                    .orElseGet(() -> MapCodec.unit(new Dot())));

    default CompoundTag serializeWrapper() {
        return (CompoundTag) CODEC.encodeStart(NbtOps.INSTANCE, this).result().orElse(new CompoundTag());
    }

    static IShape deserializeWrapper(Tag tag) {
        return CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(new Dot());
    }

    void nextPosVel(TileParticle particle, IParticleEmitter emitter, Vector3f position, Vector3f rotation, Vector3f scale);
}
