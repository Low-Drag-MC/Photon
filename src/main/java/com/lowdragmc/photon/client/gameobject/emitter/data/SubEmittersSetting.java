package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigList;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.ReadOnlyManaged;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProject;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.nbt.IntTag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.appliedenergistics.yoga.YogaDisplay;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/7/17
 * @implNote SubEmittersSetting
 */
@OnlyIn(Dist.CLIENT)
@Setter
@Getter
public class SubEmittersSetting extends ToggleGroup {

    @Configurable
    @ConfigList(configuratorMethod = "buildEmitterConfigurator", addDefaultMethod = "addDefaultEmitter")
    @ReadOnlyManaged(serializeMethod = "emittersSerialize", deserializeMethod = "emittersDeserialize")
    protected List<Emitter> emitters = new ArrayList<>();

    public void triggerEvent(FX fx, TileParticle father, Event event) {
        for (Emitter candidate : emitters) {
            if (candidate.event == event) {
                candidate.spawnEmitter(fx, father);
            }
        }
    }

    private Configurator buildEmitterConfigurator(Supplier<Emitter> getter, Consumer<Emitter> setter) {
        var instance = getter.get();
        if (instance != null && instance.createDirectConfigurator() instanceof ConfiguratorGroup group) {
            group.setCollapse(false);
            group.lineContainer.setDisplay(YogaDisplay.NONE);
            return group;
        }
        return new Configurator();
    }

    private Emitter addDefaultEmitter() {
        return new Emitter();
    }

    private IntTag emittersSerialize(List<Emitter> bursts) {
        return IntTag.valueOf(bursts.size());
    }

    private List<Emitter> emittersDeserialize(IntTag tag) {
        var groups = new ArrayList<Emitter>();
        for (int i = 0; i < tag.getAsInt(); i++) {
            groups.add(addDefaultEmitter());
        }
        return groups;
    }

    public enum Event {
        Birth,
        Death,
        Collision,
        FirstCollision,
        Tick
    }

    public static class Emitter implements IConfigurable, IPersistedSerializable {
        protected String emitter = "";
        @Configurable(tips = "photon.emitter.config.sub_emitters.emitter.event")
        protected Event event = Event.Birth;
        @Configurable(tips = "photon.emitter.config.sub_emitters.emitter.emit_probability")
        @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "probability", yAxis = "lifetime"))
        protected NumberFunction emitProbability = NumberFunction.constant(0);
        @Configurable(tips = "photon.emitter.config.sub_emitters.emitter.tick_interval")
        @ConfigNumber(range = {1, Integer.MAX_VALUE})
        protected int tickInterval = 1;
        @Configurable(tips = "photon.emitter.config.sub_emitters.emitter.inherit_color")
        protected boolean inheritColor = false;
        @Configurable(tips = "photon.emitter.config.sub_emitters.emitter.inherit_size")
        protected boolean inheritSize = false;
        @Configurable(tips = "photon.emitter.config.sub_emitters.emitter.inherit_rotation")
        protected boolean inheritRotation = false;
        @Configurable(tips = "photon.emitter.config.sub_emitters.emitter.inherit_lifetime")
        protected boolean inheritLifetime = false;
        @Configurable(tips = "photon.emitter.config.sub_emitters.emitter.inherit_duration")
        protected boolean inheritDuration = false;

        public void spawnEmitter(FX fx, TileParticle father) {
            // TODO sub emitters
            if (father.getAge() % tickInterval == 0 && father.getRandomSource().nextFloat() < emitProbability.get(father.getT(0), () -> father.getMemRandom("sub_emitter_probability"))) {
                var runtime = fx.createSubFXRuntime(emitter);
                if (runtime == null) return;
                runtime.root.updatePos(father.getWorldPos());
                for (var value : runtime.objects.values()) {
                    if (value instanceof IParticleEmitter particleEmitter) {
                        if (inheritLifetime) {
                            particleEmitter.setAge(father.getAge());
                        }
                        if (inheritDuration) {
                            particleEmitter.self().setLifetime(father.getLifetime());
                        }
                        if (inheritColor) {
                            particleEmitter.setRGBAColor(father.getRealColor(0));
                        }
                        if (inheritSize) {
                            particleEmitter.transform().scale(father.getRealSize(0));
                        }
                        if (inheritRotation) {
                            var xyz = father.getRealRotation(0);
                            particleEmitter.transform().rotation(new Quaternionf().rotationXYZ(xyz.x, xyz.y, xyz.z));
                        }
                    }
                }
                runtime.emmit(father.getEmitter().getEffect());
            }
        }

        @Override
        public void buildConfigurator(ConfiguratorGroup father) {
            List<String> candidates = new ArrayList<>();
            candidates.add("");
            // TODO editor instance
            if (Editor.INSTANCE instanceof FXEditor editor && editor.getCurrentProject() instanceof FXProject project) {
                project.getFx().getSubFXs().forEach((k, v) -> candidates.add(k));
            }
            father.addConfigurators(new SelectorConfigurator<>("emitter", () -> emitter, v -> emitter = v,
                    "", true, candidates, s -> s).setTips("photon.emitter.config.sub_emitters.emitter.name"));
            IConfigurable.super.buildConfigurator(father);
        }
    }
}
