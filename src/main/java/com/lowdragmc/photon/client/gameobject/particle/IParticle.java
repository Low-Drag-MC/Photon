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

    /** Advance this particle's simulation by {@code dt} ticks (1 = a full game tick). */
    void updateTick(float dt);

    default void updateTick() {
        updateTick(1f);
    }

    /** Snapshot the render origin (xo=x) once per tick, before any sub-step advance (driven by the
     *  owning emitter's {@code onTickBegin}); keeps frozen/sped-up particles from jittering. */
    default void syncOrigin() {
    }

    void render(VertexConsumer buffer, Camera camera, float pPartialTicks);

}
