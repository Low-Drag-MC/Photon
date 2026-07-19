package com.lowdragmc.photon.client.gameobject.emitter.data.shape;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSelector;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.NumberConfigurator;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.configurator.NumberFunctionAccessor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import net.minecraft.util.RandomSource;

import java.lang.reflect.Field;

public class ShapeArcSetting implements IConfigurable, IPersistedSerializable {

    @Configurable(name = "ShapeArcSetting.arcMode", tips = "photon.emitter.config.shape.arc.mode")
    @ConfigSelector(subConfiguratorBuilder = "buildModeConfigurator")
    private ShapeArcMode arcMode = ShapeArcMode.Random;
    @Persisted
    private float arcSpread = 0.0f;

    @Persisted
    @NumberFunctionConfig(
            types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class},
            defaultValue = 1.0f,
            curveConfig = @CurveConfig(bound = {-4, 4}, xAxis = "duration", yAxis = "rotations/s")
    )
    private NumberFunction arcSpeed = NumberFunction.constant(1.0f);

    public ShapeArcMode getArcMode() {
        return arcMode;
    }

    public void setArcMode(ShapeArcMode arcMode) {
        this.arcMode = arcMode == null ? ShapeArcMode.Random : arcMode;
    }

    public float getArcSpread() {
        return arcSpread;
    }

    public void setArcSpread(float arcSpread) {
        this.arcSpread = Math.max(0.0f, Math.min(1.0f, arcSpread));
    }

    public NumberFunction getArcSpeed() {
        return arcSpeed;
    }

    public void setArcSpeed(NumberFunction arcSpeed) {
        this.arcSpeed = arcSpeed == null ? NumberFunction.constant(1.0f) : arcSpeed;
    }

    public double sampleArcFraction(TileParticle particle, IParticleEmitter emitter, RandomSource randomSource) {
        var speed = arcMode.usesArcSpeed()
                ? arcSpeed.get(randomSource, emitter.getT()).floatValue()
                : 1.0f;
        return arcMode.sample(
                arcSpread,
                speed,
                emitter.getAge(),
                particle.getParticleBatchIndex(),
                particle.getParticleBatchCount(),
                randomSource.nextDouble()
        );
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup parent) {
        IConfigurable.super.buildConfigurator(parent);
    }

    private void buildModeConfigurator(ShapeArcMode mode, ConfiguratorGroup group) {
        group.addConfigurators(new NumberConfigurator(
                "ShapeArcSetting.arcSpread",
                () -> (Number) arcSpread,
                value -> setArcSpread(value.floatValue()),
                0.0f,
                true
        ).setTips("photon.emitter.config.shape.arc.spread"));
        if (mode.usesArcSpeed()) {
            group.addConfigurators(createArcSpeedConfigurator());
        }
    }

    private Configurator createArcSpeedConfigurator() {
        try {
            Field field = ShapeArcSetting.class.getDeclaredField("arcSpeed");
            return new NumberFunctionAccessor().create(
                    "ShapeArcSetting.arcSpeed",
                    this::getArcSpeed,
                    this::setArcSpeed,
                    true,
                    field,
                    this
            ).setTips("photon.emitter.config.shape.arc.speed");
        } catch (NoSuchFieldException e) {
            return new Configurator();
        }
    }
}
