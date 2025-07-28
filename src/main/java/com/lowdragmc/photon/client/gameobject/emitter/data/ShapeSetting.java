package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorSelectorConfigurator;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Cone;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.IShape;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import org.joml.Vector3f;
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

    @Configurable(name = "NoiseSetting.position", tips = "photon.emitter.config.shape.position")
    @NumberFunction3Config(allowSeperated = false, isSeperatedDefault = true, common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = -1000, max = 1000, curveConfig = @CurveConfig(bound = {-3, 3}, xAxis = "duration", yAxis = "position")))
    private NumberFunction3 position = new NumberFunction3(0 ,0, 0);

    @Configurable(name = "NoiseSetting.rotation", tips = "photon.emitter.config.shape.rotation")
    @NumberFunction3Config(allowSeperated = false, isSeperatedDefault = true, common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, min = -Float.MAX_VALUE, max = Float.MAX_VALUE, curveConfig = @CurveConfig(bound = {-180, 180}, xAxis = "duration", yAxis = "rotation")))
    private NumberFunction3 rotation = new NumberFunction3(0 ,0, 0);

    @Configurable(name = "ShapeSetting.scale", tips = "photon.emitter.config.shape.scale")
    @NumberFunction3Config(allowSeperated = false, isSeperatedDefault = true, common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1000, curveConfig = @CurveConfig(bound = {0, 3}, xAxis = "duration", yAxis = "scale")))
    private NumberFunction3 scale = new NumberFunction3(1, 1, 1);

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
        father.addConfigurator(new ConfiguratorSelectorConfigurator<>(
                "Shape", () -> shape.name(), name -> shape = PhotonRegistries.SHAPES.get(name).value().get(),
                "Sphere", true, PhotonRegistries.SHAPES.keys().stream().toList(),
                s -> s, (shapeName, group) -> shape.buildConfigurator(group)));
    }

    public void drawGuideLines(MultiBufferSource bufferSource, float partialTicks, IParticleEmitter emitter) {
        var poseStack = new PoseStack();
        poseStack.mulPose(emitter.transform().localToWorldMatrix());
        var t = emitter.getT(partialTicks);
        shape.drawGuideLines(poseStack, bufferSource, partialTicks, emitter,
                position.get(t, () -> emitter.getMemRandom("shape_position")),
                new Vector3f(rotation.get(t, () -> emitter.getMemRandom("shape_rotation")).mul(Mth.TWO_PI / 360)),
                new Vector3f(scale.get(t, () -> emitter.getMemRandom("shape_scale"))));
    }
}
