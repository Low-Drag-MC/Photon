package com.lowdragmc.photon.client.gameobject;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.IScene;
import com.lowdragmc.lowdraglib2.math.Transform;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
@Getter
public class FXObject extends Particle implements IFXObject {
    @Setter
    @Configurable(name = "photon.fx_object.name")
    public String name = name();
    @Nullable
    private IScene scene;
    @Accessors(fluent = true)
    @Configurable(name = "FXObject.transform", subConfigurable = true, collapse = false)
    public final Transform transform = new Transform(this);
    // runtime
    @Getter
    private long lastTick;
    @Getter
    private float lastTickTime;
    @Getter
    private float deltaTime = 0;
    @Setter
    private int delay = 0;
    @Setter
    protected boolean hasPhysics = false;
    @Nullable
    private Level realLevel;
    @Setter
    protected boolean selfVisible = true;
    @Nullable
    @Getter
    protected IEffectExecutor effectExecutor;

    protected FXObject() {
        super(null, 0, 0, 0);
        this.hasPhysics = false;
        this.friction = 1;
    }

    @Override
    public final IFXObject copy(boolean deep) {
        var copied = IFXObject.super.copy(deep);
        if (!deep) {
            copied.setName(name);
            copied.copyTransformFrom(this);
        }
        return copied;
    }

    @Override
    public void setEffect(IEffectExecutor effectExecutor) {
        this.effectExecutor = effectExecutor;
        random.setSeed(effectExecutor.getRandomSource().nextLong());
    }

    @Override
    public final void setSceneInternal(IScene scene) {
        this.scene = scene;
    }

    @Override
    public boolean isAlive() {
        for (var child : transform.children()) {
            if (child.sceneObject() instanceof FXObject fxObject && fxObject.isAlive()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void reset() {
        this.delay = 0;
        this.age = 0;
        this.removed = false;
        this.onGround = false;
    }

    @Nullable
    @Override
    public Level getLevel() {
        return realLevel == null ? super.level : realLevel;
    }

    @Override
    public void setLevel(@Nullable Level level) {
        this.realLevel = level;
    }

    @Override
    public void move(double x, double y, double z) {
    }

    @Override
    protected int getLightColor(float partialTick) {
        if (this.realLevel == null) {
            return 0;
        }
        var pos = transform.position();
        BlockPos blockPos = BlockPos.containing(pos.x, pos.y, pos.z);
        return this.realLevel.isLoaded(blockPos) ? LevelRenderer.getLightColor(this.realLevel, blockPos) : 0;
    }

    @Override
    public void remove(boolean force) {
        remove();
    }

    @Override
    public final void tick() {
        lastTick++;
        if (delay > 0) {
            delay--;
            return;
        }
        // effect first
        updateTick();
    }

    @Override
    public void updateTick() {
        if (effectExecutor != null) {
            effectExecutor.updateFXObjectTick(this);
        }
    }

    @Override
    public void render(@Nonnull VertexConsumer buffer, Camera pRenderInfo, float pPartialTicks) {
        var tickTime = lastTick + pPartialTicks;
        deltaTime = tickTime - lastTickTime;
        lastTickTime = tickTime;
        if (delay > 0) return;
        updateFrame(pPartialTicks);
        if (buffer instanceof RenderPassPipeline passBuffer) {
            passBuffer.setupRenderingState(pRenderInfo, pPartialTicks);
            prepareRenderPass(passBuffer);
        } else {
            Photon.LOGGER.error("Photon FX Object {} is not using a RenderPassBuffer. " +
                            "Please use a RenderPassBuffer to render your FX Objects.", name);
        }
    }

    public void prepareRenderPass(RenderPassPipeline buffer) {

    }

    @Override
    public void updateFrame(float partialTicks) {
        if (effectExecutor != null) {
            effectExecutor.updateFXObjectFrame(this, partialTicks);
        }
    }

    @Override
    @Nonnull
    public ParticleRenderType getRenderType() {
        return NO_RENDER_RENDER_TYPE;
    }

    @Override
    @Nonnull
    public AABB getRenderBoundingBox(float partialTicks) {
        return AABB.INFINITE;
    }

    public static ParticleRenderType NO_RENDER_RENDER_TYPE = new ParticleRenderType() {
        public final RenderPassPipeline pipeline = new RenderPassPipeline(new ByteBufferBuilder(1));

        @Override
        public BufferBuilder begin(Tesselator tesselator, TextureManager textureManager) {
            return pipeline;
        }

        @Override
        public boolean isTranslucent() {
            return false;
        }
    };
}
