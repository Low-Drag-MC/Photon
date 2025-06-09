package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import net.minecraft.nbt.CompoundTag;
import org.joml.Vector3f;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote SizeOverLifetimeSetting
 */
@OnlyIn(Dist.CLIENT)
@Setter
@Getter
public class SizeOverLifetimeSetting extends ToggleGroup implements IPersistedSerializable {

    @Configurable(tips = "photon.emitter.config.sizeOverLifetime.size")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "lifetime", yAxis = "size")))
    protected NumberFunction3 size = new NumberFunction3(1, 1, 1);

    public Vector3f getSize(IParticle particle, float partialTicks) {
        return size.get(particle.getT(partialTicks), () -> particle.getMemRandom("sol0"));
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        IPersistedSerializable.super.deserializeNBT(tag);
        // compatible with old version
        if (tag.contains("scale")) {
            var number = NumberFunction.deserializeWrapper(tag.getCompound("scale"));
            size = new NumberFunction3(number, NumberFunction.copy(number), NumberFunction.copy(number));
        }
    }
}
