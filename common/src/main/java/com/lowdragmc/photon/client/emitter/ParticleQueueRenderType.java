package com.lowdragmc.photon.client.emitter;

import com.lowdragmc.photon.client.particle.LParticle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import lombok.Getter;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureManager;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;

/**
 * @author KilaBash
 * @date 2023/6/11
 * @implNote ParticleQueueRenderType
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class ParticleQueueRenderType extends PhotonParticleRenderType {

    public static final ParticleQueueRenderType INSTANCE = new ParticleQueueRenderType();

    // runtime
    protected final Map<ParticleRenderType, List<Queue<LParticle>>> particles = new HashMap<>();
    private Camera camera;
    private float pPartialTicks;
    @Getter
    private boolean isRenderingQueue;

    @Override
    public void begin(BufferBuilder builder, TextureManager textureManager) {
        particles.clear();
        camera = null;
        isRenderingQueue = false;
    }

    @Override
    public void end(Tesselator tesselator) {
        isRenderingQueue = true;
        var frustum = PhotonParticleRenderType.getFRUSTUM();
        for (var entry : particles.entrySet()) {
            var type = entry.getKey();
            var list = entry.getValue();
            if (!list.isEmpty()) {
                RenderSystem.setShader(GameRenderer::getParticleShader);
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                BufferBuilder bufferbuilder = tesselator.getBuilder();
                type.begin(bufferbuilder, Minecraft.getInstance().getTextureManager());
                for (var queue : list) {
                    for (var particle : queue) {
//                        if (frustum != null && particle.shouldCull() && !frustum.isVisible(particle.getBoundingBox())) continue;
                        particle.render(bufferbuilder, camera, pPartialTicks);
                    }
                }
                type.end(tesselator);
            }
        }
        isRenderingQueue = false;
    }

    public void pipeQueue(@Nonnull ParticleRenderType type, @Nonnull Queue<LParticle> queue, Camera camera, float pPartialTicks) {
        particles.computeIfAbsent(type, t -> new ArrayList<>()).add(queue);
        if (this.camera == null) {
            this.camera = camera;
            this.pPartialTicks = pPartialTicks;
        }
    }
}
