package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigList;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.ReadOnlyManaged;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.nbt.IntTag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.util.RandomSource;
import org.appliedenergistics.yoga.YogaDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/6/1
 * @implNote EmissionSetting
 */
@OnlyIn(Dist.CLIENT)
@Setter
@Getter
public class EmissionSetting implements IConfigurable, IPersistedSerializable {

    public enum Mode {
        Exacting,
        Random
    }

    @Configurable(name = "EmissionSetting.emissionRate", tips = "photon.emitter.config.emission.emissionRate")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 0.5f, curveConfig = @CurveConfig(bound = {0, 5}, xAxis = "duration", yAxis = "emission rate"))
    protected NumberFunction emissionRate = NumberFunction.constant(0.5f);

    @Configurable(name = "EmissionSetting.distanceRate", tips = "photon.emitter.config.emission.distanceRate")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 0.5f, curveConfig = @CurveConfig(bound = {0, 5}, xAxis = "duration", yAxis = "emission rate"))
    protected NumberFunction distanceRate = NumberFunction.constant(0);

    @Configurable(name = "EmissionSetting.emissionMode", tips = "photon.emitter.config.emission.emissionMode")
    protected Mode emissionMode = Mode.Exacting;

    @Configurable(name = "EmissionSetting.bursts", tips = "photon.emitter.config.emission.bursts")
    @ConfigList(configuratorMethod = "buildBurstConfigurator", addDefaultMethod = "addDefaultBurst")
    @ReadOnlyManaged(serializeMethod = "burstsSerialize", deserializeMethod = "burstsDeserialize")
    protected List<Burst> bursts = new ArrayList<>();

    public int getEmissionCount(ParticleEmitter particleEmitter, RandomSource randomSource) {
        var emitterAge = particleEmitter.getAge();
        var t = particleEmitter.getT();
        var timeValue = emissionRate.get(randomSource, t);
        var distanceValue = distanceRate.get(randomSource, t).floatValue();
        var number = timeValue.intValue();
        var decimals = timeValue.floatValue() - timeValue.intValue();
        if (emissionMode == Mode.Exacting) {
            if (decimals > 0 && emitterAge % ((int) (1 / decimals)) == 0) {
                number += 1;
            }
        } else {
            if (randomSource.nextFloat() < decimals) {
                number += 1;
            }
        }
        if (distanceValue > 0) {
            var emitDistance = (int) (particleEmitter.getAccumulatedDistance() / distanceValue);
            number += emitDistance;
            particleEmitter.setAccumulatedDistance(particleEmitter.getAccumulatedDistance() - emitDistance * distanceValue);
        }

        for (var bust : bursts) {
            var realAge = emitterAge - bust.time;
            if (realAge >= 0) {
                var count = bust.count.get(randomSource, t).intValue();
                if (realAge % bust.interval == 0) {
                    if (bust.cycles == 0) {
                        if (randomSource.nextFloat() < bust.probability) {
                            number += count;
                        }
                    } else if (realAge / bust.interval < bust.cycles) {
                        if (randomSource.nextFloat() < bust.probability) {
                            number += count;
                        }
                    }
                }
            }
        }
        return number;
    }

    private Configurator buildBurstConfigurator(Supplier<Burst> getter, Consumer<Burst> setter) {
        var instance = getter.get();
        if (instance != null && instance.createDirectConfigurator() instanceof ConfiguratorGroup group) {
            group.setCollapse(false);
            group.lineContainer.setDisplay(YogaDisplay.NONE);
            return group;
        }
        return new Configurator();
    }

    private Burst addDefaultBurst() {
        return new Burst();
    }

    private IntTag burstsSerialize(List<Burst> bursts) {
        return IntTag.valueOf(bursts.size());
    }

    private List<Burst> burstsDeserialize(IntTag tag) {
        var groups = new ArrayList<Burst>();
        for (int i = 0; i < tag.getAsInt(); i++) {
            groups.add(addDefaultBurst());
        }
        return groups;
    }

    public static class Burst implements IConfigurable, IPersistedSerializable{
        @Configurable(name = "Burst.time", tips = "photon.emitter.config.emission.bursts.time")
        @ConfigNumber(range = {0, Integer.MAX_VALUE}, wheel = 1)
        public int time = 0;
        @Setter
        @Getter
        @Configurable(name = "Burst.count", tips = "photon.emitter.config.emission.bursts.count")
        @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 50, curveConfig = @CurveConfig(bound = {0, 50}, xAxis = "duration", yAxis = "emit count"))
        protected NumberFunction count = NumberFunction.constant(50);
        @Configurable(name = "Burst.cycles", tips = "photon.emitter.config.emission.bursts.cycles")
        @ConfigNumber(range = {0, Integer.MAX_VALUE})
        public int cycles = 1;
        @Configurable(name = "Burst.interval", tips = "photon.emitter.config.emission.bursts.interval")
        @ConfigNumber(range = {1, Integer.MAX_VALUE}, wheel = 1)
        public int interval = 1;
        @Configurable(name = "Burst.probability", tips = "photon.emitter.config.emission.bursts.probability")
        @ConfigNumber(range = {0, 1})
        public float probability = 1;
    }
}
