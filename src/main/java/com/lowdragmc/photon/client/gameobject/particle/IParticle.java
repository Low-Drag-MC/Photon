package com.lowdragmc.photon.client.gameobject.particle;

import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.util.RandomSource;

import java.util.function.Function;

public interface IParticle {

    PhotonFXRenderPass getRenderType();

    RandomSource getRandomSource();

    boolean isRemoved();

    default boolean isAlive() {
        return !isRemoved();
    }

    float getT();

    float getT(float partialTicks);

    float getMemRandom(Object object);

    float getMemRandom(Object object, Function<RandomSource, Float> randomFunc);

    void updateTick();

    void render(VertexConsumer buffer, Camera camera, float pPartialTicks);

}
