package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

import com.google.common.collect.Maps;
import com.lowdragmc.lowdraglib2.client.shader.Shaders;
import com.lowdragmc.lowdraglib2.math.PositionedRect;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.postprocessing.BloomEffect;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import oshi.util.tuples.Pair;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

public class RenderPassPipeline extends BufferBuilder {
    public static class BufferBuilderPool {
        private final ConcurrentLinkedQueue<Tesselator> pool = new ConcurrentLinkedQueue<>();
        public Tesselator acquire() {
            var tesselator = pool.poll();
            return tesselator != null ? tesselator : new Tesselator(1536);
        }

        public void release(Tesselator tesselator) {
            pool.offer(tesselator);
        }
    }

    private static final int MINIMUM_TASK_SIZE = 64;
    private static final BufferBuilderPool BUILDER_POOL = new BufferBuilderPool();

    // runtime
    private final Map<PhotonFXRenderPass, Queue<IParticle>> particles = Maps.newTreeMap(makeRenderPassComparator());
    private Camera camera;
    private float partialTicks;
    private boolean hasBloom = false;

    public static Comparator<PhotonFXRenderPass> makeRenderPassComparator() {
        return (passOne, passTwo) -> {
            var comparedResult = passOne.layerOrder() - passTwo.layerOrder();
            if (comparedResult == 0) {
                return Integer.compare(System.identityHashCode(passOne), System.identityHashCode(passTwo));
            }
            return comparedResult;
        };
    }

    public RenderPassPipeline() {
        super(new ByteBufferBuilder(1), VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
    }

    @Override
    public @Nullable MeshData build() {
        RenderSystem.setShader(GameRenderer::getParticleShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        for (var entry : particles.entrySet()) {
            var renderPass = entry.getKey();
            var particleQueue = entry.getValue();
            if (!particleQueue.isEmpty()) {
                renderPass.prepareStatus(this);
                renderParticles(renderPass, particleQueue);
                renderPass.releaseStatus(this);
            }
        }
        if (hasBloom) {
            renderBloom();
        }
        clearRenderingState();
        return null;
    }

    private void renderParticles(PhotonFXRenderPass renderPass, Queue<IParticle> particleQueue) {
        if (renderPass.isParallel()) {
            renderParticlesParallel(renderPass, particleQueue);
        } else {
            renderParticlesSequential(renderPass, particleQueue);
        }
    }

    private void renderParticlesParallel(PhotonFXRenderPass renderPass, Queue<IParticle> particleQueue) {
        try (var forkJoinPool = ForkJoinPool.commonPool()) {
            var maxThreads = ForkJoinPool.getCommonPoolParallelism() + 1;
            var task = new ParallelRenderingTask(Math.max(particleQueue.size() / maxThreads, MINIMUM_TASK_SIZE), renderPass, particleQueue.spliterator());
            for (var pair : forkJoinPool.submit(task).get()) {
                var data = pair.getB().build();
                if (data != null) {
                    BufferUploader.drawWithShader(data);
                }
                BUILDER_POOL.release(pair.getA());
            }
        } catch (Throwable throwable) {
            Photon.LOGGER.error("Error rendering particles in parallel", throwable);
        }
    }

    private void renderParticlesSequential(PhotonFXRenderPass renderPass, Queue<IParticle> particleQueue) {
        var tesselator = Tesselator.getInstance();
        var buffer = renderPass.begin(tesselator);

        for (var particle : particleQueue) {
            particle.render(buffer, camera, partialTicks);
        }

        var data = buffer.build();
        if (data != null) {
            BufferUploader.drawWithShader(data);
        }
    }

    private void clearRenderingState() {
        particles.clear();
        camera = null;
        hasBloom = false;
    }

    public void setupRenderingState(Camera camera, float partialTicks) {
        this.camera = camera;
        this.partialTicks = partialTicks;
    }

    public void pipeQueue(@Nonnull PhotonFXRenderPass renderPass, @Nonnull Collection<IParticle> queue) {
        particles.computeIfAbsent(renderPass, t -> new ArrayDeque<>()).addAll(queue);
    }

    /// Push data parallel
    class ParallelRenderingTask extends RecursiveTask<List<Pair<Tesselator, BufferBuilder>>> {
        private final int threshold;
        private final PhotonFXRenderPass renderPass;
        private final Spliterator<IParticle> particleSpliterator;

        public ParallelRenderingTask(int threshold, PhotonFXRenderPass renderPass, Spliterator<IParticle> particleSpliterator) {
            this.renderPass = renderPass;
            this.particleSpliterator = particleSpliterator;
            this.threshold = threshold;
        }

        @Override
        protected List<Pair<Tesselator, BufferBuilder>> compute() {
            if (particleSpliterator.estimateSize() > threshold) {
                var split = particleSpliterator.trySplit();
                var firstTask = new ParallelRenderingTask(threshold, renderPass, particleSpliterator).fork();

                List<Pair<Tesselator, BufferBuilder>> result = new ArrayList<>();
                if (split != null) {
                    result.addAll(new ParallelRenderingTask(threshold, renderPass, split).compute());
                }
                result.addAll(firstTask.join());

                return result;
            } else {
                var tesselator = BUILDER_POOL.acquire();
                var buffer = renderPass.begin(tesselator);

                particleSpliterator.forEachRemaining(p -> p.render(buffer, camera, partialTicks));
                return List.of(new Pair<>(tesselator, buffer));
            }
        }
    }


    /// Bloom
    private void renderBloom() {
        if (!Photon.isUsingShaderPack()) {
            // setup view port
            var lastViewport = PositionedRect.of(GlStateManager.Viewport.x(), GlStateManager.Viewport.y(), GlStateManager.Viewport.width(), GlStateManager.Viewport.height());
            var input = BloomEffect.getInput();
            var output = BloomEffect.getOutput();
            var background = Minecraft.getInstance().getMainRenderTarget();
            if (lastViewport.position.x != 0 ||
                    lastViewport.position.y != 0 ||
                    lastViewport.size.width != background.width ||
                    lastViewport.size.height != background.height){
                RenderSystem.viewport(0, 0, background.width, background.height);
            }

            // render bloom effect
            BloomEffect.renderBloom(background.width, background.height,
                    background.getColorTextureId(),
                    input.getColorTextureId(),
                    output);

            // clean input
            input.bindWrite(false);
            GlStateManager._clearColor(0.0f, 0.0f, 0.0f, 0.0f);
            int i = GL11.GL_COLOR_BUFFER_BIT;
            GlStateManager._clear(i, Minecraft.ON_OSX);

            // draw effect back to main target
            GlStateManager._colorMask(true, true, true, true);
            GlStateManager._disableDepthTest();
            GlStateManager._depthMask(false);

            background.bindWrite(false);

            Shaders.getBlitShader().setSampler("DiffuseSampler", output.getColorTextureId());

            Shaders.getBlitShader().apply();
            GlStateManager._enableBlend();
            RenderSystem.defaultBlendFunc();

            var tesselator = Tesselator.getInstance();
            var buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            buffer.addVertex(-1, 1, 0);
            buffer.addVertex(-1, -1, 0);
            buffer.addVertex(1, -1, 0);
            buffer.addVertex(1, 1, 0);
            BufferUploader.draw(buffer.buildOrThrow());
            Shaders.getBlitShader().clear();

            GlStateManager._depthMask(true);
            GlStateManager._colorMask(true, true, true, true);
            GlStateManager._enableDepthTest();

            // restore view port
            if (lastViewport.position.x != 0 ||
                    lastViewport.position.y != 0 ||
                    lastViewport.size.width != background.width ||
                    lastViewport.size.height != background.height){
                RenderSystem.viewport(lastViewport.position.x, lastViewport.position.y, lastViewport.size.width, lastViewport.size.height);
            }
        }
    }

    public void beginBloom() {
        if (!Photon.isUsingShaderPack()) {
            var input = BloomEffect.getInput();
            input.bindWrite(false);
            hasBloom = true;
        }
    }

    public void endBloom() {
        if (!Photon.isUsingShaderPack()) {
            var background = Minecraft.getInstance().getMainRenderTarget();
            background.bindWrite(false);
        }
    }

}