package com.lowdragmc.photon.client.gameobject.emitter.particle;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.emitter.data.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Camera;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collection;
import java.util.List;

import static org.lwjgl.opengl.GL30.glBindVertexArray;

/**
 * @author KilaBash
 * @date 2023/6/11
 * @implNote ParticleConfig
 */
public class ParticleConfig implements IConfigurable, IPersistedSerializable {
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.duration", tips = "photon.emitter.config.duration")
    @ConfigNumber(range = {1, Integer.MAX_VALUE})
    protected int duration = 100;
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.looping", tips = "photon.emitter.config.looping")
    protected boolean looping = true;
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.prewarm", tips = "photon.emitter.config.prewarm")
    @ConfigNumber(range = {0, Integer.MAX_VALUE})
    protected int prewarm = 0;
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.startDelay", tips = "photon.emitter.config.startDelay")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, numberType = ConfigNumber.Type.INTEGER, min = 0, curveConfig = @CurveConfig(bound = {0, 100}, xAxis = "duration", yAxis = "delay"))
    protected NumberFunction startDelay = NumberFunction.constant(0);
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.startLifetime", tips = "photon.emitter.config.startLifetime")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, numberType = ConfigNumber.Type.INTEGER, min = 0, defaultValue = 100, curveConfig = @CurveConfig(bound = {0, 200}, xAxis = "duration", yAxis = "life time"))
    protected NumberFunction startLifetime = NumberFunction.constant(100);
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.startSpeed", tips = "photon.emitter.config.startSpeed")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, defaultValue = 1f, curveConfig = @CurveConfig(bound = {-2, 2}, xAxis = "duration", yAxis = "speed"))
    protected NumberFunction startSpeed = NumberFunction.constant(1);
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.startSize", tips = "photon.emitter.config.startSize")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 0.1f, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "size")))
    protected NumberFunction3 startSize = new NumberFunction3(0.1, 0.1, 0.1);
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.startRotation", tips = "photon.emitter.config.startRotation")
    @NumberFunction3Config(affectX = false, affectY = false, common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "duration", yAxis = "rotation")))
    protected NumberFunction3 startRotation = new NumberFunction3(0, 0, 0);
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.startColor", tips = "photon.emitter.config.startColor")
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction startColor = NumberFunction.color(-1);
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.simulationSpace", tips = "photon.emitter.config.simulationSpace")
    protected Space simulationSpace = Space.Local;
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.maxParticles", tips = "photon.emitter.config.maxParticles")
    @ConfigNumber(range = {0, 100000}, wheel = 100)
    protected int maxParticles = 2000;
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.parallelUpdate", tips = {"photon.emitter.config.parallelUpdate.0",
            "photon.emitter.config.parallelUpdate.1"})
    protected boolean parallelUpdate = false;
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.parallelRendering", tips = {
            "photon.emitter.config.parallelRendering.0",
            "photon.emitter.config.parallelRendering.1",
            "photon.emitter.config.parallelRendering.2"})
    protected boolean parallelRendering = false;
    @Configurable(name = "ParticleConfig.emission", subConfigurable = true, tips = "photon.emitter.config.emission")
    public final EmissionSetting emission = new EmissionSetting();
    @Configurable(name = "ParticleConfig.shape", subConfigurable = true, tips = "photon.emitter.config.shape")
    public final ShapeSetting shape = new ShapeSetting();
    @Configurable(name = "ParticleConfig.renderer", subConfigurable = true, tips = "photon.emitter.config.renderer")
    public final ParticleRendererSetting renderer = new ParticleRendererSetting(this);
    @Configurable(name = "ParticleConfig.physics", subConfigurable = true, tips = "photon.emitter.config.physics")
    public final PhysicsSetting physics = new PhysicsSetting();
    @Configurable(name = "ParticleConfig.fixedLight", subConfigurable = true, tips = "photon.emitter.config.lights")
    public final LightOverLifetimeSetting lights = new LightOverLifetimeSetting();
    @Configurable(name = "ParticleConfig.velocityOverLifetime", subConfigurable = true, tips = "photon.emitter.config.velocityOverLifetime")
    public final VelocityOverLifetimeSetting velocityOverLifetime = new VelocityOverLifetimeSetting();
    @Configurable(name = "ParticleConfig.inheritVelocity", subConfigurable = true, tips = "photon.emitter.config.inheritVelocity")
    public final InheritVelocitySetting inheritVelocity = new InheritVelocitySetting();
    @Configurable(name = "ParticleConfig.lifetimeByEmitterSpeed", subConfigurable = true, tips = "photon.emitter.config.lifetimeByEmitterSpeed")
    public final LifetimeByEmitterSpeedSetting lifetimeByEmitterSpeed = new LifetimeByEmitterSpeedSetting();
    @Configurable(name = "ParticleConfig.forceOverLifetime", subConfigurable = true, tips = "photon.emitter.config.forceOverLifetime")
    public final ForceOverLifetimeSetting forceOverLifetime = new ForceOverLifetimeSetting();
    @Configurable(name = "ParticleConfig.colorOverLifetime", subConfigurable = true, tips = "photon.emitter.config.colorOverLifetime")
    public final ColorOverLifetimeSetting colorOverLifetime = new ColorOverLifetimeSetting();
    @Configurable(name = "ParticleConfig.colorBySpeed", subConfigurable = true, tips = "photon.emitter.config.colorBySpeed")
    public final ColorBySpeedSetting colorBySpeed = new ColorBySpeedSetting();
    @Configurable(name = "ParticleConfig.sizeOverLifetime", subConfigurable = true, tips = "photon.emitter.config.sizeOverLifetime")
    public final SizeOverLifetimeSetting sizeOverLifetime = new SizeOverLifetimeSetting();
    @Configurable(name = "ParticleConfig.sizeBySpeed", subConfigurable = true, tips = "photon.emitter.config.sizeBySpeed")
    public final SizeBySpeedSetting sizeBySpeed = new SizeBySpeedSetting();
    @Configurable(name = "ParticleConfig.rotationOverLifetime", subConfigurable = true, tips = "photon.emitter.config.rotationOverLifetime")
    public final RotationOverLifetimeSetting rotationOverLifetime = new RotationOverLifetimeSetting();
    @Configurable(name = "ParticleConfig.rotationBySpeed", subConfigurable = true, tips = "photon.emitter.config.rotationBySpeed")
    public final RotationBySpeedSetting rotationBySpeed = new RotationBySpeedSetting();
    @Configurable(name = "ParticleConfig.noise", subConfigurable = true, tips = "photon.emitter.config.noise")
    public final NoiseSetting noise = new NoiseSetting();
    @Configurable(name = "ParticleConfig.uvAnimation", subConfigurable = true, tips = "photon.emitter.config.uvAnimation")
    public final UVAnimationSetting uvAnimation = new UVAnimationSetting();
    @Configurable(name = "ParticleConfig.trails", subConfigurable = true, tips = "photon.emitter.config.trails")
    public final TrailsSetting trails = new TrailsSetting();
    @Configurable(name = "ParticleConfig.subEmitters", subConfigurable = true, tips = "photon.emitter.config.sub_emitters")
    public final SubEmittersSetting subEmitters = new SubEmittersSetting();
    @Configurable(name = "ParticleConfig.additionalGPUDataSetting", subConfigurable = true, tips = "photon.emitter.config.additional_gpu_data")
    public final ParticleAdditionalGPUDataSetting additionalGPUDataSetting = new ParticleAdditionalGPUDataSetting(this);

    // runtime
    public final RenderPass particleRenderType = new RenderPass();

    public enum Space {
        Local,
        World
    }

    public ParticleConfig() {
        renderer.getMaterials().add(new MaterialSetting());
    }

    @ParametersAreNonnullByDefault
    public class RenderPass extends PhotonFXRenderPass {
        private final ParticleInstanceRenderer instanceRenderer = new ParticleInstanceRenderer(ParticleConfig.this);

        public RenderPass() {
            super(renderer, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        }

        public void clearInstance() {
            instanceRenderer.dispose();
        }

        public void drawParticlesInternal(List<MaterialSetting> materials, RenderPassPipeline pipeline, Collection<IParticle> particles, Camera camera, float partialTicks) {
            if (renderer.isUseGPUInstance()) {
                var context = renderer.getRenderMode() == ParticleRendererSetting.Mode.Model ?
                        MaterialContext.PARTICLE_MODEL_INSTANCE : MaterialContext.PARTICLE_INSTANCE;

                // upload to vbo
                if (instanceRenderer.upload((Collection) particles, camera, partialTicks)) {
                    for (MaterialSetting materialSetting : materials) {
                        materialSetting.pre();
                        renderInstanceWithMaterial(materialSetting.getMaterial(), context);
                        materialSetting.post();
                    }
                }

                // invalidate cache
                glBindVertexArray(0);
                BufferUploader.invalidate();
            } else {
                super.drawParticlesInternal(materials, pipeline, particles, camera, partialTicks);
            }
        }

        protected void renderInstanceWithMaterial(IMaterial material, MaterialContext context) {
            var shader = material.begin(context);
            RenderSystem.setShader(() -> shader);
            instanceRenderer.drawWithShader(shader);
            material.end(context);
        }

        @Override
        public boolean isParallel() {
            return isParallelRendering();
        }

        @Override
        public boolean equals(@Nonnull Object o) {
            return o instanceof RenderPass && super.equals(o);
        }
    }

}
