package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.math.Range;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector4f;

/**
 * @author KilaBash
 * @date 2023/5/31
 * @implNote UVAnimation
 */
@OnlyIn(Dist.CLIENT)
public class UVAnimationSetting extends ToggleGroup {
    public enum Animation {
        WholeSheet,
        SingleRow,
    }

    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.uvAnimation.tiles")
    @ConfigNumber(range = {1, Integer.MAX_VALUE})
    protected Range tiles = Range.of(1, 1);

    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.uvAnimation.animation")
    protected Animation animation = Animation.WholeSheet;

    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.uvAnimation.frameOverTime")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, curveConfig = @CurveConfig(bound = {0, 4}, xAxis = "lifetime", yAxis = "frame over time"))
    protected NumberFunction frameOverTime = NumberFunction.constant(0);

    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.uvAnimation.startFrame")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class}, min = 0)
    protected NumberFunction startFrame = NumberFunction.constant(0);

    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.uvAnimation.cycle")
    @ConfigNumber(range = {0, Integer.MAX_VALUE}, wheel = 1)
    protected float cycle = 1;

    public Vector4f getUVs(IParticle particle, float partialTicks) {
        var t = particle.getT(partialTicks);
        var cellU = 1f / tiles.getA().intValue();
        var cellV = 1f / tiles.getB().intValue();
        var currentFrame = this.startFrame.get(t, () -> particle.getMemRandom("startFrame"));
        currentFrame += cycle * frameOverTime.get(t, () -> particle.getMemRandom("frameOverTime"));
        float u0, v0, u1, v1;
        var cellSize = tiles.getA().intValue();
        if (animation == Animation.WholeSheet) {
            int X = (int) (currentFrame % cellSize);
            int Y = (int) (currentFrame / cellSize);
            u0 = X * cellU;
            v0 = Y * cellV;
        } else {
            int X = (int) (currentFrame % cellSize);
            int Y = (int) (particle.getMemRandom("randomRow") * tiles.getB().intValue());
            u0 = X * cellU;
            v0 = Y * cellV;
        }
        u1 = u0 + cellU;
        v1 = v0 + cellV;
        return new Vector4f(u0, v0, u1, v1);
    }
}
