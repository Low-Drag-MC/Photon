package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.gui.editor.configurator.SelectorConfigurator;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.utils.Vector3;
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
    @NumberRange(range = {-1000, 1000})
    private Vector3 position = Vector3.ZERO.copy();
    @Getter @Setter
    @Configurable(tips = "photon.emitter.config.shape.rotation")
    @NumberRange(range = {-Float.MAX_VALUE, Float.MAX_VALUE}, wheel = 10)
    private Vector3 rotation = Vector3.ZERO.copy();
    @Getter @Setter
    @Configurable(tips = "photon.emitter.config.shape.scale")
    @NumberRange(range = {0, 1000})
    private Vector3 scale = Vector3.ONE.copy();

    public void setupParticle(LParticle particle) {
        shape.nextPosVel(particle, position.copy(), rotation.copy().multiply(Mth.TWO_PI / 360), scale.copy());
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
