package com.lowdragmc.photon.client.emitter;

import com.lowdragmc.photon.client.particle.LParticle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import lombok.Getter;
import lombok.val;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.apache.commons.lang3.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

/**
 * @author KilaBash
 * @date 2023/6/11
 * @implNote ParticleQueueRenderType
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class ParticleQueueRenderType extends PhotonParticleRenderType {

    public static final ParticleQueueRenderType INSTANCE = new ParticleQueueRenderType();
    private static final BufferBuilder[] BUFFERS = new BufferBuilder[ForkJoinPool.getCommonPoolParallelism() + 1];
    static {
        for (int i = 0; i < BUFFERS.length; i++) {
            BUFFERS[i] = new BufferBuilder(256);
        }
    }

    // runtime
    protected final Map<PhotonParticleRenderType, Queue<LParticle>> particles = new HashMap<>();
    private Camera camera;
    private float pPartialTicks;
    @Getter
    private boolean isRenderingQueue;

    @Override
    public void begin(BufferBuilder builder) {
        particles.clear();
        camera = null;
        isRenderingQueue = false;
    }

    @Override
    public void end(BufferBuilder builder) {
        isRenderingQueue = true;
        for (var entry : particles.entrySet()) {
            var type = entry.getKey();
            var list = entry.getValue();
            if (!list.isEmpty()) {
                RenderSystem.setShader(GameRenderer::getParticleShader);
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                type.prepareStatus();

                if (type.isParallel()) {
                    val forkJoinPool = ForkJoinPool.commonPool();
                    val task = forkJoinPool.submit(new ParallelRenderingTask(BUFFERS, type, list.spliterator()));
                    try {
                        for (var buffer : task.get()) {
                            type.end(buffer);
                        }
                    } catch (Throwable ignored) {
                        ignored.printStackTrace();
                    } finally {
                        forkJoinPool.shutdown();
                    }
                } else {
                    type.begin(builder);
                    for (var particle : list) {
                        particle.render(builder, camera, pPartialTicks);
                    }
                    type.end(builder);
                }

                type.releaseStatus();
            }
        }
        isRenderingQueue = false;
    }

    public void pipeQueue(@Nonnull PhotonParticleRenderType type, @Nonnull Queue<LParticle> queue, Camera camera, float pPartialTicks) {
        particles.computeIfAbsent(type, t -> new ArrayDeque<>()).addAll(queue);
        if (this.camera == null) {
            this.camera = camera;
            this.pPartialTicks = pPartialTicks;
        }
    }

    class ParallelRenderingTask extends RecursiveTask<List<BufferBuilder>> {
        private final BufferBuilder[] buffers;
        private final PhotonParticleRenderType type;
        private final Spliterator<LParticle> particles;

        public ParallelRenderingTask(BufferBuilder[] buffers, PhotonParticleRenderType type, Spliterator<LParticle> particles) {
            this.buffers = buffers;
            this.type = type;
            this.particles = particles;
        }

        @Override
        protected List<BufferBuilder> compute() {
            if(buffers.length > 1){
                var split = particles.trySplit();
                var task1 = new ParallelRenderingTask(ArrayUtils.subarray(buffers, 0, buffers.length / 2), type, particles).fork();
                if (split != null) {
                    var task2 = new ParallelRenderingTask(ArrayUtils.subarray(buffers, buffers.length / 2, buffers.length), type, split).fork();
                    var result = new ArrayList<>(task1.join());
                    result.addAll(task2.join());
                    return result;
                }
                return task1.join();
            } else {
                type.begin(buffers[0]);
                particles.forEachRemaining(particle -> particle.render(buffers[0], camera, pPartialTicks));
                return List.of(buffers[0]);
            }
        }

    }
}
