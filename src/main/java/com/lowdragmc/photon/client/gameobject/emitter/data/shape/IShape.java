package com.lowdragmc.photon.client.gameobject.emitter.data.shape;

import com.lowdragmc.lowdraglib2.client.utils.RenderBufferUtils;
import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.registry.ILDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import org.joml.Vector3f;
import net.minecraft.nbt.CompoundTag;
import org.lwjgl.opengl.GL11;
import oshi.util.tuples.Pair;

import java.util.List;
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

    // 26.1: immediate Tesselator+BufferUploader draws are gone — route through the scene's buffer
    // source with the vanilla lines render type (blend/depth/width owned by the pipeline).
    default void drawGuideLines(PoseStack poseStack, MultiBufferSource bufferSource, float partialTicks, IParticleEmitter emitter, Vector3f position, Vector3f rotation, Vector3f scale) {
        var edges = getGuideLines(emitter, position, rotation, scale);
        if (edges.isEmpty()) return;
        var buffer = bufferSource.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.lines());
        RenderBufferUtils.drawEdges(poseStack, buffer, edges, ColorPattern.YELLOW.color, 5);
    }

    default List<Pair<Vector3f, Vector3f>> getGuideLines(IParticleEmitter emitter, Vector3f position, Vector3f rotation, Vector3f scale) {
        return List.of();
    }
}
