package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.gui.editor.configurator.Configurator;
import com.lowdragmc.lowdraglib2.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.editor.runtime.ConfiguratorParser;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
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
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.nbt.CompoundTag;
import org.joml.Vector4f;

import java.util.HashMap;

/**
 * @author KilaBash
 * @date 2023/6/1
 * @implNote TrailsSetting
 */
@OnlyIn(Dist.CLIENT)
public class TrailsSetting extends ToggleGroup implements IPersistedSerializable {
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.trails.ratio")
    @ConfigNumber(range = {0f, 1f})
    protected float ratio = 1f;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.trails.lifetime")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, defaultValue = 1, curveConfig = @CurveConfig(xAxis = "lifetime", yAxis = "trail length"))
    protected NumberFunction lifetime = NumberFunction.constant(1);
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.trails.dieWithParticles")
    protected boolean dieWithParticles = false;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.trails.sizeAffectsWidth")
    protected boolean sizeAffectsWidth = true;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.trails.sizeAffectsLifetime")
    protected boolean sizeAffectsLifetime = false;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.trails.inheritParticleColor")
    protected boolean inheritParticleColor = true;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.trails.colorOverLifetime")
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction colorOverLifetime = new Gradient();

    @Persisted(subPersisted = true)
    public final TrailConfig config = new TrailConfig();

    public TrailsSetting() {
        config.setWidthOverTrail(NumberFunction.constant(0.5f));
        config.setParallelRendering(true);
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        IPersistedSerializable.super.deserializeNBT(tag);
        // compatible with old version
        if (tag.contains("material")) {
            config.getMaterial().deserializeNBT(tag.getCompound("material"));
            config.setColorOverTrail(NumberFunction.deserializeWrapper(tag.getCompound("colorOverTrail")));
            config.setWidthOverTrail(NumberFunction.deserializeWrapper(tag.getCompound("widthOverTrail")));
            config.setUvMode(TrailParticle.UVMode.valueOf(tag.getString("uvMode")));
            config.setMinVertexDistance(tag.getFloat("minimumVertexDistance"));
        }
    }

    public void setup(ParticleEmitter emitter, TileParticle particle) {
        var random = emitter.getRandomSource();
        if (random.nextFloat() < ratio) { // has tail
            var trail = new TrailParticle(emitter, config, emitter.getThreadSafeRandomSource());
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
        }
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        super.buildConfigurator(father);
        ConfiguratorParser.createConfigurators(father, new HashMap<>(), config.getClass(), config);
        // remove time configurator from trail config
        for (Configurator configurator : father.getConfigurators()) {
            if (configurator.getName().equals("time")) {
                father.removeConfigurator(configurator);
                break;
            }
        }
    }
}
