package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.core.mixins.accessor.ParticleEngineAccessor;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import lombok.Getter;
import net.minecraft.client.Minecraft;
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

    /**
     * Whether the currently rendering particle source still holds particles for this queue.
     *
     * <p>Used to answer "is another Photon build coming this frame?": the opaque queue always renders
     * before the translucent one, but by the time the opaque pipeline builds, the translucent pipeline
     * has not been fed yet — the pending particles are only visible on the source that owns them
     * (the editor scene's manager while it renders, otherwise the vanilla engine).
     */
    public boolean hasQueuedParticles() {
        var editorScene = PhotonParticleManager.getRenderingManager();
        var byRenderType = editorScene != null
                ? editorScene.particlesByRenderType()
                : ((ParticleEngineAccessor) Minecraft.getInstance().particleEngine).getParticles();
        var queue = byRenderType.get(this);
        return queue != null && !queue.isEmpty();
    }
}
