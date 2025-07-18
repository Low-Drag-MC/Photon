package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import lombok.Getter;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.texture.TextureManager;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * @author KilaBash
 * @date 2023/6/11
 * @implNote ParticleQueueRenderType
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class ParticleQueueRenderType implements ParticleRenderType {

    public static final ParticleQueueRenderType OPAQUE_QUEUE = new ParticleQueueRenderType(false);
    public static final ParticleQueueRenderType TRANSLUCENT_QUEUE = new ParticleQueueRenderType(true);

    public final RenderPassPipeline pipeline = new RenderPassPipeline(new ByteBufferBuilder(1536));

    @Getter
    public final boolean isTranslucent;

    private ParticleQueueRenderType(boolean isTranslucent) {
        this.isTranslucent = isTranslucent;
    }

    @Override
    public @Nullable BufferBuilder begin(Tesselator tesselator, TextureManager textureManager) {
        return pipeline;
    }
}
