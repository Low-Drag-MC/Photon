package com.lowdragmc.photon.client.emitter;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.utils.ColorUtils;
import com.lowdragmc.lowdraglib.utils.Vector3;
import com.lowdragmc.photon.client.data.*;
import com.lowdragmc.photon.client.data.number.*;
import com.lowdragmc.photon.client.data.number.color.Color;
import com.lowdragmc.photon.client.data.number.color.Gradient;
import com.lowdragmc.photon.client.data.number.color.RandomColor;
import com.lowdragmc.photon.client.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.data.number.curve.Curve;
import com.lowdragmc.photon.client.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.particle.LParticle;
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
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.Mth;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * @author KilaBash
 * @date 2023/5/25
 * @implNote ParticleEmitter
 */
@ParametersAreNonnullByDefault
@LDLRegister(name = "particle", group = "emitter")
public class ParticleEmitter extends LParticle implements IParticleEmitter {
    @Setter
    @Getter
    @Persisted
    protected String name = "particle emitter";
    @Setter
    @Getter
    @Configurable(tips = "The length of time (tick) the Particle Emitter is emitting particles. If the system is looping, this indicates the length of one cycle.")
    @NumberRange(range = {0, Integer.MAX_VALUE})
    protected int duration = 100;
    @Setter
    @Getter
    @Configurable(tips = "If true, the emission cycle will repeat after the duration.")
    protected boolean looping = true;
    @Setter
    @Getter
    @Configurable(tips = "Delay in seconds that this Particle System will wait before emitting particles.")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, isDecimals = false, min = 0, curveConfig = @CurveConfig(bound = {0, 100}, xAxis = "duration", yAxis = "delay"))
    protected NumberFunction startDelay = NumberFunction.constant(0);
    @Setter
    @Getter
    @Configurable(tips = "Start lifetime in ticks, particle will die when its lifetime reaches 0.")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, isDecimals = false, min = 0, defaultValue = 100, curveConfig = @CurveConfig(bound = {0, 200}, xAxis = "duration", yAxis = "life time"))
    protected NumberFunction startLifetime = NumberFunction.constant(100);
    @Setter
    @Getter
    @Configurable(tips = "The start speed of particles, applied in the starting direction.")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, defaultValue = 1f, curveConfig = @CurveConfig(bound = {-2, 2}, xAxis = "duration", yAxis = "speed"))
    protected NumberFunction startSpeed = NumberFunction.constant(1);
    @Setter
    @Getter
    @Configurable(tips = "The start size of particles.")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 0.1f, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "size"))
    protected NumberFunction startSize = NumberFunction.constant(0.1f);
    @Setter
    @Getter
    @Configurable(tips = "The start rotation of particles in degrees. (x-roll, y-pitch, z-yaw)" )
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 360, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "duration", yAxis = "rotation")))
    protected NumberFunction3 startRotation = new NumberFunction3(0, 0, 0);
    @Setter
    @Getter
    @Configurable(tips = "The start color of particles.")
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction startColor = NumberFunction.color(-1);
    @Setter
    @Getter
    @Configurable(tips = "The number of particles in the system will be limited by this number. Emission will be temporarily halted if this is reached")
    @NumberRange(range = {0, 10000})
    protected int maxParticles = 2000;
    @Getter
    @Configurable(name = "Emission", subConfigurable = true, tips = "Emission of the emitter. This controls the rate at which particles are emitted as well as burst emissions.")
    protected final EmissionSetting emission = new EmissionSetting();
    @Getter
    @Configurable(name = "Shape", subConfigurable = true, tips = "Shape of the emitter volume, which controls where particles are emitted and their initial direction.")
    protected final ShapeSetting shape = new ShapeSetting();
    @Getter
    @Configurable(name = "Material", subConfigurable = true, tips = "Open Reference for Particle Material.")
    protected final MaterialSetting material = new MaterialSetting();
    @Getter
    @Configurable(name = "Renderer", subConfigurable = true, tips = "Specifies how the particles are rendered.")
    protected final RendererSetting renderer = new RendererSetting();
    @Getter
    @Configurable(name = "Physics", subConfigurable = true, tips = "The physics of particles.")
    protected final PhysicsSetting physics = new PhysicsSetting();
    @Getter
    @Configurable(name = "Light", subConfigurable = true, tips = "Controls the light map of each particle during its lifetime.")
    protected final LightOverLifetimeSetting lights = new LightOverLifetimeSetting();
    @Getter
    @Configurable(name = "Velocity over Lifetime", subConfigurable = true, tips = "Controls the velocity of each particle during its lifetime.")
    protected final VelocityOverLifetimeSetting velocityOverLifetime = new VelocityOverLifetimeSetting();
    @Getter
    @Configurable(name = "Inherit Velocity", subConfigurable = true, tips = "Controls the velocity inherited from the emitter, foreach particle.")
    protected final InheritVelocitySetting inheritVelocity = new InheritVelocitySetting();
    @Getter
    @Configurable(name = "Lifetime by Emitter Speed", subConfigurable = true, tips = "Controls the initial lifetime of each particle based on the speed of the emitter when the particle was spawned.")
    protected final LifetimeByEmitterSpeedSetting lifetimeByEmitterSpeed = new LifetimeByEmitterSpeedSetting();
    @Getter
    @Configurable(name = "Force over Lifetime", subConfigurable = true, tips = "Controls the force of each particle during its lifetime.")
    protected final ForceOverLifetimeSetting forceOverLifetime = new ForceOverLifetimeSetting();
    @Getter
    @Configurable(name = "Color over Lifetime", subConfigurable = true, tips = "Controls the color of each particle during its lifetime.")
    protected final ColorOverLifetimeSetting colorOverLifetime = new ColorOverLifetimeSetting();
    @Getter
    @Configurable(name = "Color by Speed", subConfigurable = true, tips = "Controls the color of each particle based on its speed.")
    protected final ColorBySpeedSetting colorBySpeed = new ColorBySpeedSetting();
    @Getter
    @Configurable(name = "Size over Lifetime", subConfigurable = true, tips = "Controls the size of each particle during its lifetime.")
    protected final SizeOverLifetimeSetting sizeOverLifetime = new SizeOverLifetimeSetting();
    @Getter
    @Configurable(name = "Size by Speed", subConfigurable = true, tips = "Controls the size of each particle based on its speed.")
    protected final SizeBySpeedSetting sizeBySpeed = new SizeBySpeedSetting();
    @Getter
    @Configurable(name = "Rotation over Lifetime", subConfigurable = true, tips = "Controls the rotation of each particle during its lifetime.")
    protected final RotationOverLifetimeSetting rotationOverLifetime = new RotationOverLifetimeSetting();
    @Getter
    @Configurable(name = "Rotation by Speed", subConfigurable = true, tips = "Controls the angular velocity of each particle based on its speed.")
    protected final RotationBySpeedSetting rotationBySpeed = new RotationBySpeedSetting();
    @Getter
    @Configurable(name = "Noise", subConfigurable = true, tips = "Add noise/turbulence to particle movement.")
    protected final NoiseSetting noise = new NoiseSetting();
    @Getter
    @Configurable(name = "UV Animation", subConfigurable = true, tips = "Particle UV animation. This allows you to specify a texture sheet (a texture with multiple tiles/subframes) and animate or randomize over it per particle.")
    protected final UVAnimationSetting uvAnimation = new UVAnimationSetting();
    @Getter
    @Configurable(name = "Trails", subConfigurable = true, tips = "Attach trails to the particles.")
    protected final TrailsSetting trails = new TrailsSetting();

    // runtime
    @Getter
    protected final Map<ParticleRenderType, LinkedList<LParticle>> particles = new LinkedHashMap<>();
    @Getter
    protected int particleAmount = 0;
    protected final ParticleRenderType renderType = new RenderType();
    @Getter @Setter
    protected boolean visible = true;

    public ParticleEmitter() {
        super(null, 0, 0, 0);
    }

    public LParticle createRawParticle() {
        return new Basic(level, this.x, this.y, this.z, renderType);
    }


    @Override
    public void tick() {
        // emit new particle
        if (!isRemoved() && particleAmount < maxParticles) {
            var randomSource = getRandomSource();
            var number = emission.getEmissionCount(this.age, t, randomSource);
            for (int i = 0; i < number; i++) {
                var particle = createRawParticle();
                particle.setLevel(getLevel());
                // start value
                particle.setDelay(startDelay.get(randomSource, t).intValue());
                if (lifetimeByEmitterSpeed.isEnable()) {
                    particle.setLifetime(lifetimeByEmitterSpeed.getLifetime(particle, this, startLifetime.get(randomSource, t).intValue()));
                } else {
                    particle.setLifetime(startLifetime.get(randomSource, t).intValue());
                }
                shape.setupParticle(particle);
                particle.setSpeed(startSpeed.get(randomSource, t).floatValue());
                particle.setSize(startSize.get(randomSource, t).floatValue());
                var rotation = startRotation.get(randomSource, t).multiply(Mth.TWO_PI / 360);
                particle.setRoll((float) rotation.x);
                particle.setPitch((float) rotation.y);
                particle.setYaw((float) rotation.z);
                particle.setARGBColor(startColor.get(randomSource, t).intValue());
                renderer.setupQuaternion(particle);
                if (physics.isEnable()) {
                    particle.setPhysics(physics.isHasCollision());
                    particle.setGravity(physics.getGravity().get(randomSource, 0).floatValue());
                    particle.setFriction(physics.getFriction().get(randomSource, 0).floatValue());
                }
                // particle logic
                final var emitterVelocity = getVelocity();

                if (velocityOverLifetime.isEnable() || inheritVelocity.isEnable()) {
                    particle.setVelocityAddition(p -> {
                        var addition = new Vector3(0, 0, 0);
                        if (velocityOverLifetime.isEnable()) {
                            addition.add(velocityOverLifetime.getVelocityAddition(p, this));
                        }
                        if (inheritVelocity.isEnable()) {
                            addition.add(inheritVelocity.getVelocityAddition(p, this, emitterVelocity));
                        }
                        return addition;
                    });
                }

                if (velocityOverLifetime.isEnable()) {
                    particle.setVelocityMultiplier(p -> {
                        float multiplier = 1;
                        if (velocityOverLifetime.isEnable()) {
                            multiplier *= velocityOverLifetime.getVelocityMultiplier(p);
                        }
                        return multiplier;
                    });
                }

                if (forceOverLifetime.isEnable() || sizeOverLifetime.isEnable() || sizeBySpeed.isEnable() || physics.isEnable() || noise.isEnable()) {
                    particle.setOnUpdate(p -> {
                        if (forceOverLifetime.isEnable()) {
                            p.setSpeed(p.getVelocity().add(forceOverLifetime.getForce(p)));
                        }
                        if (sizeOverLifetime.isEnable()) {
                            p.setQuadSize(sizeOverLifetime.getSize(p, 0));
                        }
                        if (sizeBySpeed.isEnable()) {
                            p.setQuadSize(sizeBySpeed.getSize(p));
                        }
                        if (physics.isEnable()) {
                            p.setFriction(physics.getFrictionModifier(p));
                            p.setGravity(physics.getGravityModifier(p));
                        }
                        if (noise.isEnable()) {
                            p.setPos(p.getPos(1).add(noise.getPosition(p, 0)), false);
                        }

                    });
                }


                if (colorOverLifetime.isEnable() || colorBySpeed.isEnable()) {
                    particle.setDynamicColor((p, partialTicks) -> {
                        float a = 1f;
                        float r = 1f;
                        float g = 1f;
                        float b = 1f;
                        if (colorOverLifetime.isEnable()) {
                            int color = colorOverLifetime.getColor(p, partialTicks);
                            a *= ColorUtils.alpha(color);
                            r *= ColorUtils.red(color);
                            g *= ColorUtils.green(color);
                            b *= ColorUtils.blue(color);
                        }
                        if (colorBySpeed.isEnable()) {
                            int color = colorBySpeed.getColor(p);
                            a *= ColorUtils.alpha(color);
                            r *= ColorUtils.red(color);
                            g *= ColorUtils.green(color);
                            b *= ColorUtils.blue(color);
                        }
                        return new Vector4f(r, g, b, a);
                    });
                }

                if (sizeOverLifetime.isEnable() || noise.isEnable()) {
                    particle.setDynamicSize((p, partialTicks) -> {
                        var size = p.getQuadSize(partialTicks);
                        if (sizeOverLifetime.isEnable()) {
                            size = sizeOverLifetime.getSize(p, partialTicks);
                        }
                        if (noise.isEnable()) {
                            size = size.copy().add(noise.getSize(p, partialTicks));
                        }
                        return size;
                    });
                }

                if (rotationOverLifetime.isEnable() || rotationBySpeed.isEnable() || noise.isEnable()) {
                    particle.setRotationAddition((p, partialTicks) -> {
                        var addition = new Vector3(0, 0, 0);
                        if (rotationOverLifetime.isEnable()) {
                            addition.add(rotationOverLifetime.getRotation(p, partialTicks));
                        }
                        if (rotationBySpeed.isEnable()) {
                            addition.add(rotationBySpeed.getRotation(p));
                        }
                        if (noise.isEnable()) {
                            addition.add(noise.getRotation(p, partialTicks));
                        }
                        return addition;
                    });
                }

                if (noise.isEnable()) {
                    particle.setPositionAddition((p, partialTicks) -> {
                       if (noise.isEnable()) {
                           return noise.getPosition(p, partialTicks);
                       }
                       return Vector3.ZERO;
                    });
                }

                if (uvAnimation.isEnable()) {
                    particle.setDynamicUVs((p, partialTicks) -> {
                        if (uvAnimation.isEnable()) {
                            return uvAnimation.getUVs(p, partialTicks);
                        }
                        return new Vector4f(p.getU0(partialTicks), p.getV0(partialTicks), p.getU1(partialTicks), p.getV1(partialTicks));
                    });
                }

                if (lights.isEnable()) {
                    particle.setDynamicLight((p, partialTicks) -> {
                        if (lights.isEnable()) {
                            return lights.getLight(p, partialTicks);
                        }
                        return p.getLight();
                    });
                }

                if (trails.isEnable()) {
                    trails.setup(this, particle);
                }

                if (!emitParticle(particle)) {
                    break;
                }
            }
        }

        // particles life cycle
        var iter = particles.entrySet().iterator();
        particleAmount = 0;
        while (iter.hasNext()) {
            var entry = iter.next();
            var list = entry.getValue();
            var iterator = list.iterator();
            while (iterator.hasNext()) {
                var p = iterator.next();
                if (!p.isAlive()) {
                    iterator.remove();
                } else {
                    p.tick();
                }
            }
            particleAmount += list.size();
            if (list.isEmpty()) {
                iter.remove();
            }
        }

        if (this.age >= this.duration && !isLooping()) {
            this.remove();
        }

        this.age++;
        t = (this.age % this.duration) * 1f / this.duration;

    }

    @Override
    public int getLifetime() {
        return duration;
    }

    @Override
    public boolean isAlive() {
        return !removed || particleAmount != 0;
    }

    @Override
    protected void updateOrigin() {
        super.updateOrigin();
        setLifetime(this.duration);
    }

    @Override
    public void resetParticle() {
        super.resetParticle();
        this.particles.clear();
    }

    @Override
    public final void render(VertexConsumer buffer, Camera renderInfo, float partialTicks) {
        if (visible) {
            particles.forEach((particleRenderType, lParticles) -> {
                if (particleRenderType != ParticleRenderType.NO_RENDER) {
                    Tesselator tesselator = Tesselator.getInstance();
                    BufferBuilder bufferbuilder = tesselator.getBuilder();
                    particleRenderType.begin(bufferbuilder, Minecraft.getInstance().getTextureManager());
                    for(var particle : lParticles) {
                        particle.render(bufferbuilder, renderInfo, partialTicks);
                    }
                    particleRenderType.end(tesselator);
                }
            });
        }
    }

    @Override
    @Nonnull
    public final ParticleRenderType getRenderType() {
        return ParticleRenderType.CUSTOM;
    }

    private class RenderType implements ParticleRenderType {
        @Override
        public void begin(@Nonnull BufferBuilder bufferBuilder, @Nonnull TextureManager textureManager) {
            material.pre();
            material.getMaterial().begin(bufferBuilder, textureManager, false);
            Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
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
        }
    }


    //////////////////////////////////////
    //********      Emitter    *********//
    //////////////////////////////////////

    public boolean emitParticle(LParticle particle) {
        if (this.emitter != null) {
            return this.emitter.emitParticle(particle);
        } else {
            particles.computeIfAbsent(particle.getRenderType(), type -> new LinkedList<>()).add(particle);
            particle.addParticle(this);
            particleAmount++;
            return particleAmount <= maxParticles;
        }
    }
}
