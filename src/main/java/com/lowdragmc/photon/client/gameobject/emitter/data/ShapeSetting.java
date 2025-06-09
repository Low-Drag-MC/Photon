package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.gui.editor.configurator.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Cone;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.IShape;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import org.joml.Vector3f;
import com.lowdragmc.photon.integration.PhotonLDLibPlugin;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * @author KilaBash
 * @date 2023/5/27
 * @implNote Shape
 */
@OnlyIn(Dist.CLIENT)
@Getter
@Setter
public class ShapeSetting implements IConfigurable, IPersistedSerializable {

    @Persisted
    private IShape shape = new Cone();

    @Configurable(tips = "photon.emitter.config.shape.position")
    @NumberFunction3Config(allowSeperated = false, isSeperatedDefault = true, common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = -1000, max = 1000, curveConfig = @CurveConfig(bound = {-3, 3}, xAxis = "duration", yAxis = "position")))
    private NumberFunction3 position = new NumberFunction3(0 ,0, 0);

    @Configurable(tips = "photon.emitter.config.shape.rotation")
    @NumberFunction3Config(allowSeperated = false, isSeperatedDefault = true, common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, min = -Float.MAX_VALUE, max = Float.MAX_VALUE, curveConfig = @CurveConfig(bound = {-180, 180}, xAxis = "duration", yAxis = "rotation")))
    private NumberFunction3 rotation = new NumberFunction3(0 ,0, 0);

    @Configurable(tips = "photon.emitter.config.shape.scale")
    @NumberFunction3Config(allowSeperated = false, isSeperatedDefault = true, common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1000, curveConfig = @CurveConfig(bound = {0, 3}, xAxis = "duration", yAxis = "scale")))
    private NumberFunction3 scale = new NumberFunction3(1, 1, 1);

    @Override
    public void deserializeNBT(CompoundTag tag) {
        IPersistedSerializable.super.deserializeNBT(tag);
        // compatible with old version
        if (tag.getCompound("position").contains("x", Tag.TAG_DOUBLE)) {
            var pos = tag.getCompound("position");
            position = new NumberFunction3(pos.getDouble("x"), pos.getDouble("y"), pos.getDouble("z"));
            var rot = tag.getCompound("rotation");
            rotation = new NumberFunction3(rot.getDouble("x"), rot.getDouble("y"), rot.getDouble("z"));
            var sca = tag.getCompound("scale");
            scale = new NumberFunction3(sca.getDouble("x"), sca.getDouble("y"), sca.getDouble("z"));
        }
    }

    public void setupParticle(TileParticle particle, IParticleEmitter emitter) {
        var t = emitter.getT();
        shape.nextPosVel(particle, emitter,
                position.get(t, () -> emitter.getMemRandom("shape_position")),
                new Vector3f(rotation.get(t, () -> emitter.getMemRandom("shape_rotation")).mul(Mth.TWO_PI / 360)),
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
