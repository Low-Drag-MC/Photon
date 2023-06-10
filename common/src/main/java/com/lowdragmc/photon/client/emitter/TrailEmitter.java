package com.lowdragmc.photon.client.emitter;

import com.lowdragmc.lowdraglib.gui.editor.annotation.ConfigSetter;
import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.utils.ColorUtils;
import com.lowdragmc.lowdraglib.utils.Vector3;
import com.lowdragmc.photon.client.emitter.data.LightOverLifetimeSetting;
import com.lowdragmc.photon.client.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.emitter.data.number.Constant;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.fx.IFXEffect;
import com.lowdragmc.photon.client.particle.LParticle;
import com.lowdragmc.photon.client.particle.TrailParticle;
import com.lowdragmc.photon.core.mixins.accessor.BlendModeAccessor;
import com.lowdragmc.photon.core.mixins.accessor.ShaderInstanceAccessor;
import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Vector4f;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author KilaBash
 * @date 2023/6/6
 * @implNote TrailEmitter
 */
@ParametersAreNonnullByDefault
@LDLRegister(name = "trail", group = "emitter")
public class TrailEmitter extends TrailParticle implements IParticleEmitter {
    @Setter
    @Getter
    @Persisted
    protected String name = "particle emitter";

    @Setter
    @Getter
    @Configurable(tips = "How long the tail should be (ticks)[O, infinity].")
    @NumberRange(range = {0f, Integer.MAX_VALUE})
    protected int time = 20;
    @Getter
    @Configurable(tips = "The minimum distance each trail can travel before adding a new vertex.")
    @NumberRange(range = {0f, Float.MAX_VALUE})
    protected float minVertexDistance = 0.05f;
    @Setter @Getter
    @Configurable(tips = "Should the U coordinate be stretched or tiled?")
    protected TrailParticle.UVMode uvMode = TrailParticle.UVMode.Stretch;
    @Setter
    @Getter
    @Configurable(tips = "Select a width for the trail from its start to end vertex.")
    @NumberFunctionConfig(types = {Constant.class, Curve.class}, min = 0, defaultValue = 1f, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "trail position", yAxis = "width"))
    protected NumberFunction widthOverTrail = NumberFunction.constant(2f);
    @Setter
    @Getter
    @Configurable(tips = "Select a color for the trail from its start to end vertex.")
    @NumberFunctionConfig(types = {Color.class, Gradient.class}, defaultValue = -1)
    protected NumberFunction colorOverTrail = new Gradient();
    @Getter
    @Configurable(name = "Material", subConfigurable = true, tips = "Open Reference for Tail Material.")
    protected final MaterialSetting material = new MaterialSetting();
    @Getter
    @Configurable(name = "Renderer", subConfigurable = true, tips = "Specifies how the particles are rendered.")
    protected final RendererSetting renderer = new RendererSetting();
    @Getter
    @Configurable(name = "Fixed Light", subConfigurable = true, tips = "Controls the light map of each particle during its lifetime.")
    protected final LightOverLifetimeSetting lights = new LightOverLifetimeSetting();

    // runtime
    @Getter
    protected final ParticleRenderType renderType = new RenderType();
    @Getter
    protected final Map<ParticleRenderType, Queue<LParticle>> particles = Map.of(renderType, new ArrayDeque<>(1));
    @Getter @Setter
    protected boolean visible = true;
    @Nullable
    @Getter @Setter
    protected IFXEffect fXEffect;
    protected LinkedList<AtomicInteger> tailsTime = new LinkedList<>();

    protected TrailEmitter() {
        super(null, 0, 0, 0);
        material.setMaterial(new CustomShaderMaterial());
        particles.get(renderType).add(this);
        super.setLifetime(-1);
        super.setUvMode(uvMode);
        super.setMinimumVertexDistance(minVertexDistance);
        super.setOnRemoveTails(t -> {
            var iterT = tailsTime.iterator();
            var iter = tails.iterator();
            while (iter.hasNext() && iterT.hasNext()) {
                var tailTime = iterT.next();
                iter.next();
                if (tailTime.getAndAdd(1) > time) {
                    iterT.remove();
                    iter.remove();
                }
            }
            return true;
        });
        super.setDieWhenRemoved(false);
        super.setDynamicTailColor((t, tail, partialTicks) -> {
            int color = colorOverTrail.get(tail / (t.getTails().size() - 1f), () -> t.getMemRandom("trails-colorOverTrail")).intValue();
            return new Vector4f(ColorUtils.red(color), ColorUtils.green(color), ColorUtils.blue(color), ColorUtils.alpha(color));
        });
        super.setDynamicTailWidth((t, tail, partialTicks) -> 0.2f * widthOverTrail.get(tail / (t.getTails().size() - 1f), () -> t.getMemRandom("trails-widthOverTrail")).floatValue());
        super.setDynamicLight((t, partialTicks) -> {
            if (usingBloom()) {
                return LightTexture.FULL_BRIGHT;
            }
            if (lights.isEnable()) {
                return lights.getLight(t, partialTicks);
            }
            return t.getLight();
        });
    }

    @Override
    protected void update() {
        // effect first
        if (fXEffect != null && fXEffect.updateEmitter(this)) {
            return;
        }
        renderer.setupQuaternion(this);
        super.update();
    }

    @Override
    protected void addNewTail(Vector3 tail) {
        super.addNewTail(tail);
        tailsTime.addLast(new AtomicInteger(0));
    }

    @Override
    @ConfigSetter(field = "minVertexDistance")
    public void setMinimumVertexDistance(float minimumVertexDistance) {
        super.setMinimumVertexDistance(minimumVertexDistance);
        this.minVertexDistance = minimumVertexDistance;
    }

    @Override
    public float getT(float partialTicks) {
        return 1;
    }

    @Override
    public int getAge() {
        return 0;
    }

    @Override
    public void resetParticle() {
        super.resetParticle();
        this.tailsTime.clear();
    }

    @Override
    public void render(@NotNull VertexConsumer pBuffer, Camera pRenderInfo, float pPartialTicks) {
        if (delay <= 0 && isVisible() && PhotonParticleRenderType.checkLayer(renderer.getLayer())) {
            super.render(pBuffer, pRenderInfo, pPartialTicks);
        }
    }

    @Override
    public boolean emitParticle(LParticle particle) {
        return false;
    }

    @Override
    public boolean usingBloom() {
        return renderer.isBloomEffect();
    }

    private class RenderType extends PhotonParticleRenderType {
        @Override
        public void begin(@Nonnull BufferBuilder bufferBuilder, @Nonnull TextureManager textureManager) {
            if (usingBloom() && isVisible()) {
                beginBloom();
            }
            material.pre();
            material.getMaterial().begin(bufferBuilder, textureManager, false);
            Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
            bufferBuilder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public void end(@Nonnull Tesselator tesselator) {
            BlendMode lastBlend = null;
            if (RenderSystem.getShader() instanceof ShaderInstanceAccessor shader) {
                lastBlend = BlendModeAccessor.getLastApplied();
                BlendModeAccessor.setLastApplied(shader.getBlend());
            }
            tesselator.end();
            material.getMaterial().end(tesselator, false);
            material.post();
            if (lastBlend != null) {
                lastBlend.apply();
            }
            if (usingBloom() && isVisible()) {
                endBloom();
            }
        }
    }

}
