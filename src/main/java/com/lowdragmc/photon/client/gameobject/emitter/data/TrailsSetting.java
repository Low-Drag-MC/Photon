package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSelector;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
import com.lowdragmc.photon.client.gameobject.emitter.aratrail.AraTrailConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.trail.TrailConfig;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.lowdragmc.photon.client.gameobject.particle.TrailParticle;
import com.lowdragmc.photon.client.gameobject.particle.aratrail.AraTrailParticle;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector4f;

import java.util.HashMap;

/**
 * @author KilaBash
 * @date 2023/6/1
 * @implNote TrailsSetting
 */
@OnlyIn(Dist.CLIENT)
public class TrailsSetting extends ToggleGroup {
    public enum TrailType {
        TRAIL,
        ARA_TRAIL;
    }
    @Setter
    @Getter
    @Configurable(name = "TrailsSetting.ratio", tips = "photon.emitter.config.trails.ratio")
    @ConfigNumber(range = {0f, 1f})
    protected float ratio = 1f;
    @Setter
    @Getter
    @Configurable(name = "TrailsSetting.lifetime", tips = "photon.emitter.config.trails.lifetime")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, defaultValue = 1, curveConfig = @CurveConfig(xAxis = "lifetime", yAxis = "trail length"))
    protected NumberFunction lifetime = NumberFunction.constant(1f);
    @Setter
    @Getter
    @Configurable(name = "TrailsSetting.dieWithParticles", tips = "photon.emitter.config.trails.dieWithParticles")
    protected boolean dieWithParticles = false;
    @Setter
    @Getter
    @Configurable(name = "TrailsSetting.sizeAffectsWidth", tips = "photon.emitter.config.trails.sizeAffectsWidth")
    protected boolean sizeAffectsWidth = true;
    @Setter
    @Getter
    @Configurable(name = "TrailsSetting.sizeAffectsLifetime", tips = "photon.emitter.config.trails.sizeAffectsLifetime")
    protected boolean sizeAffectsLifetime = false;
    @Setter
    @Getter
    @Configurable(name = "TrailsSetting.inheritParticleColor", tips = "photon.emitter.config.trails.inheritParticleColor")
    protected boolean inheritParticleColor = true;
    @Setter
    @Getter
    @Configurable(name = "TrailsSetting.colorOverLifetime", tips = "photon.emitter.config.trails.colorOverLifetime")
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction colorOverLifetime = new Gradient();
    @Setter
    @Getter
    @Configurable(name = "TrailsSetting.trailType")
    @ConfigSelector(subConfiguratorBuilder = "createTrailTypeConfigurator")
    protected TrailType trailType = TrailType.TRAIL;

    public final TrailConfig config = new TrailConfig();
    public final AraTrailConfig araConfig = new AraTrailConfig();

    public TrailsSetting() {
        config.setWidthOverTrail(NumberFunction.constant(0.5f));
        config.setParallelRendering(true);
        araConfig.thickness = 0.5f;
        araConfig.minDistance = 0.05f;
    }

    public void setup(ParticleEmitter emitter, TileParticle particle) {
        var random = emitter.getRandomSource();
        if (random.nextFloat() < ratio) { // has tail
            if (trailType == TrailType.TRAIL) {
                var trail = new TrailParticle(emitter, config);
                trail.setDelay(particle.getDelay() + trail.getDelay());
                trail.setHeadPositionSupplier(particle::getWorldPos);
                trail.setDieWhenAllTailsRemoved(!dieWithParticles);
                trail.setOnUpdate(() -> {
                    if (particle.isRemoved()) {
                        trail.setRemoved(true);
                    }
                });
                trail.setLifetimeSupplier(() -> {
                    var time = lifetime.get(particle.getT(), () -> particle.getMemRandom("trails-lifetime")).floatValue() * particle.getLifetime();
                    if (sizeAffectsLifetime) {
                        time *= Vector3fHelper.max(particle.getRealSize(0));
                    }
                    return time;
                });
                trail.setWidthMultiplier(() -> {
                    if (sizeAffectsWidth) {
                        return Vector3fHelper.max(particle.getRealSize(0));
                    }
                    return 1f;
                });
                trail.setColorMultiplier(t -> {
                    var color = new Vector4f(1);
                    if (inheritParticleColor) {
                        color.mul(particle.getRealColor(t));
                    }
                    if (colorOverLifetime != null) {
                        var c = colorOverLifetime.get(particle.getT(t), () -> particle.getMemRandom("trails-color")).intValue();
                        color.mul(ColorUtils.red(c), ColorUtils.green(c), ColorUtils.blue(c), ColorUtils.alpha(c));
                    }
                    return color;
                });

                emitter.emitParticle(trail);
            } else if (trailType == TrailType.ARA_TRAIL) {
                var trail = new AraTrailParticle(emitter, araConfig);
//                trail.setDelay(particle.getDelay() + trail.getDelay());
                trail.setWorldPositionSupplier(particle::getWorldPos);
                trail.setWorldUpSupplier(particle::getWorldUp);
                trail.setWorldForwardSupplier(particle::getWorldForward);
                trail.setWorldRightSupplier(particle::getWorldRight);
                trail.setDieWhenAllTailsRemoved(!dieWithParticles);
                trail.setOnUpdate(() -> {
                    if (particle.isRemoved()) {
                        trail.setRemoved(true);
                    }
                });
                trail.setLifetimeSupplier(() -> {
                    var time = lifetime.get(particle.getT(), () -> particle.getMemRandom("trails-lifetime")).floatValue() * particle.getLifetime();
                    if (sizeAffectsLifetime) {
                        time *= Vector3fHelper.max(particle.getRealSize(0));
                    }
                    return time / 20; // convert to second
                });
                trail.setThicknessMultiplierSupplier(t -> {
                    if (sizeAffectsWidth) {
                        return Vector3fHelper.max(particle.getRealSize(t));
                    }
                    return 1f;
                });
                trail.setColorMultiplierSupplier(t -> {
                    var color = new Vector4f(1);
                    if (inheritParticleColor) {
                        color.mul(particle.getRealColor(t));
                    }
                    if (colorOverLifetime != null) {
                        var c = colorOverLifetime.get(particle.getT(t), () -> particle.getMemRandom("trails-color")).intValue();
                        color.mul(ColorUtils.red(c), ColorUtils.green(c), ColorUtils.blue(c), ColorUtils.alpha(c));
                    }
                    return color;
                });

                emitter.emitParticle(trail);
            }
        }
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.@NotNull Provider provider) {
        var data = super.serializeNBT(provider);
        data.put("config", config.serializeNBT(provider));
        data.put("araConfig", araConfig.serializeNBT(provider));
        return data;
    }

    @Override
    public void deserializeNBT(HolderLookup.@NotNull Provider provider, @NotNull CompoundTag tag) {
        super.deserializeNBT(provider, tag);
        config.deserializeNBT(provider, tag.getCompound("config"));
        araConfig.deserializeNBT(provider, tag.getCompound("araConfig"));
    }

    private void createTrailTypeConfigurator(TrailType value, ConfiguratorGroup group) {
        switch (value) {
            case TRAIL -> {
                config.buildConfigurator(group);
                // remove time configurator from trail config
                for (var configurator : group.getConfigurators()) {
                    if (configurator.label.getText().equals(Component.translatable("TrailConfig.time"))) {
                        group.removeConfigurator(configurator);
                        break;
                    }
                }
            }
            case ARA_TRAIL -> {
                araConfig.buildConfigurator(group);
            }
        }
    }
}
