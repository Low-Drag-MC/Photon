package com.lowdragmc.photon.client.gameobject;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.gui.editor.ui.sceneeditor.data.Transform;
import com.lowdragmc.lowdraglib2.gui.editor.ui.sceneeditor.sceneobject.IScene;
import com.lowdragmc.photon.client.fx.IEffect;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
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
    @Configurable(subConfigurable = true, collapse = false)
    public final Transform transform = new Transform(this);
    // runtime
    @Nullable
    private Level realLevel;
    @Setter
    protected boolean visible = true;
    @Nullable
    @Getter @Setter
    protected IEffect effect;

    protected FXObject() {
        super(null, 0, 0, 0);
        this.hasPhysics = false;
        this.friction = 1;
    }

    @Override
    public IFXObject deepCopy() {
        var data = serializeNBT();
        if (data.contains("transform")) {
            data.getCompound("transform").remove("id");
        }
        return IFXObject.deserializeWrapper(data);
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
    public boolean isVisible() {
        if (!visible) return false;
        if (transform.parent() != null && transform.parent().sceneObject() instanceof IFXObject ifxObject) {
            return ifxObject.isVisible();
        }
        return true;
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
        return this.realLevel.hasChunkAt(blockPos) ? LevelRenderer.getLightColor(this.realLevel, blockPos) : 0;
    }

    @Override
    public void remove(boolean force) {
        remove();
    }

    @Override
    public void tick() {
        // effect first
        updateTick();
    }

    @Override
    public void updateTick() {
        if (effect != null) {
            effect.updateFXObjectTick(this);
        }
    }

    @Override
    public void render(@Nonnull VertexConsumer pBuffer, Camera pRenderInfo, float pPartialTicks) {
        updateFrame(pPartialTicks);
    }

    @Override
    public void updateFrame(float partialTicks) {
        if (effect != null) {
            effect.updateFXObjectFrame(this, partialTicks);
        }
    }

    @Override
    @Nonnull
    public ParticleRenderType getRenderType() {
        return NO_RENDER_RENDER_TYPE;
    }

    // compatibility with forge particle
    public boolean shouldCull() {
        return false;
    }

    public static ParticleRenderType NO_RENDER_RENDER_TYPE = new ParticleRenderType() {
        @Override
        public void begin(BufferBuilder builder, TextureManager textureManager) {}

        @Override
        public void end(Tesselator tesselator) {}
    };
}
