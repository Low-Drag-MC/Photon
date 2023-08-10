package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.gui.editor.configurator.SelectorConfigurator;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.emitter.data.number.*;
import com.lowdragmc.photon.client.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.emitter.data.number.curve.RandomCurve;
import org.joml.Vector3f;
import com.lowdragmc.photon.client.emitter.data.shape.Cone;
import com.lowdragmc.photon.client.emitter.data.shape.IShape;
import com.lowdragmc.photon.client.particle.LParticle;
import com.lowdragmc.photon.integration.PhotonLDLibPlugin;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Mth;

/**
 * @author KilaBash
 * @date 2023/5/27
 * @implNote Shape
 */
@Environment(EnvType.CLIENT)
public class ShapeSetting implements IConfigurable {

    @Getter
    @Setter
    @Persisted
    private IShape shape = new Cone();
    @Getter @Setter
    @Configurable(tips = "photon.emitter.config.shape.position")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = -1000, max = 1000, curveConfig = @CurveConfig(bound = {-3, 3}, xAxis = "duration", yAxis = "position")))
    private NumberFunction3 position = new NumberFunction3(0 ,0, 0);
    @Getter @Setter
    @Configurable(tips = "photon.emitter.config.shape.rotation")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, min = -Float.MAX_VALUE, max = Float.MAX_VALUE, curveConfig = @CurveConfig(bound = {-180, 180}, xAxis = "duration", yAxis = "rotation")))
    private NumberFunction3 rotation = new NumberFunction3(0 ,0, 0);
    @Getter @Setter
    @Configurable(tips = "photon.emitter.config.shape.scale")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1000, curveConfig = @CurveConfig(bound = {0, 3}, xAxis = "duration", yAxis = "scale")))
    private NumberFunction3 scale = new NumberFunction3(1, 1, 1);


    public void setupParticle(LParticle particle, LParticle emitter) {
        var t = emitter.getT();
        shape.nextPosVel(particle, emitter, emitter.getPos().add(position.get(t, () -> emitter.getMemRandom("shape_position"))),
                emitter.getRotation(0).add(new Vector3f(rotation.get(t, () -> emitter.getMemRandom("shape_rotation"))).mul(Mth.TWO_PI / 360)),
                new Vector3f(scale.get(t, () -> emitter.getMemRandom("shape_scale"))));
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        IConfigurable.super.buildConfigurator(father);
        var group = new ConfiguratorGroup("", false);
        var selector = new SelectorConfigurator<>("Shape", () -> shape.name(), name -> {
            var wrapper = PhotonLDLibPlugin.REGISTER_SHAPES.get(name);
            if (wrapper != null) {
                shape = wrapper.creator().get();
                group.removeAllConfigurators();
                shape.buildConfigurator(group);
                father.computeLayout();
            }
        }, "Sphere", true, PhotonLDLibPlugin.REGISTER_SHAPES.keySet().stream().toList(), String::toString);
        selector.setMax(PhotonLDLibPlugin.REGISTER_SHAPES.size());
        father.addConfigurators(selector);
        group.setCanCollapse(false);
        shape.buildConfigurator(group);
        father.addConfigurators(group);
    }
}
