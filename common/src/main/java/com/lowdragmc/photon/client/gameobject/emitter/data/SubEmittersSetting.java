package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.accessors.TypesAccessor;
import com.lowdragmc.lowdraglib.gui.editor.annotation.ConfigAccessor;
import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.gui.editor.configurator.Configurator;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.gui.editor.configurator.SelectorConfigurator;
import com.lowdragmc.lowdraglib.gui.editor.runtime.PersistedParser;
import com.lowdragmc.lowdraglib.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
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
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.joml.Quaternionf;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/7/17
 * @implNote SubEmittersSetting
 */
@Environment(EnvType.CLIENT)
@Setter
@Getter
public class SubEmittersSetting extends ToggleGroup implements IConfigurable, ITagSerializable<CompoundTag> {

    @Configurable(persisted = false)
    protected List<Emitter> emitters = new ArrayList<>();

    @Override
    public CompoundTag serializeNBT() {
        var tag = new CompoundTag();
        PersistedParser.serializeNBT(tag, getClass(), this);
        var list = new ListTag();
        for (var emitter : emitters) {
            var element = new CompoundTag();
            PersistedParser.serializeNBT(element, Emitter.class, emitter);
            list.add(element);
        }
        tag.put("emitters", list);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        PersistedParser.deserializeNBT(tag, new HashMap<>(), getClass(), this);
        emitters.clear();
        var list = tag.getList("emitters", Tag.TAG_COMPOUND);
        for (var element : list) {
            if (element instanceof CompoundTag nbt) {
                var emitter = new Emitter();
                try {
                    PersistedParser.deserializeNBT(nbt, new HashMap<>(), Emitter.class, emitter);
                    emitters.add(emitter);
                } catch (Exception ignored) {

                }
            }
        }
    }

    public void triggerEvent(FX fx, TileParticle father, Event event) {
        for (Emitter candidate : emitters) {
            if (candidate.event == event) {
                candidate.spawnEmitter(fx, father);
            }
        }
    }

    @ConfigAccessor
    public static class EmitterAccessor extends TypesAccessor<Emitter> {

        public EmitterAccessor() {
            super(Emitter.class);
        }

        @Override
        public Emitter defaultValue(Field field, Class<?> type) {
            return new Emitter();
        }

        @Override
        public Configurator create(String name, Supplier<Emitter> supplier, Consumer<Emitter> consumer, boolean forceUpdate, Field field) {
            var group = new ConfiguratorGroup("emitter", true);
            var emitter = supplier.get();
            emitter = emitter == null ? new Emitter() : emitter;
            emitter.buildConfigurator(group);
            return group;
        }
    }

    public enum Event {
        Birth,
        Death,
        Collision,
        FirstCollision,
        Tick
    }

    public static class Emitter implements IConfigurable {
        @Persisted
        protected String emitter = "";
        @Configurable(tips = "photon.emitter.config.sub_emitters.emitter.event")
        protected Event event = Event.Birth;
        @Configurable(tips = "photon.emitter.config.sub_emitters.emitter.emit_probability")
        @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "probability", yAxis = "lifetime"))
        protected NumberFunction emitProbability = NumberFunction.constant(0);
        @Configurable(tips = "photon.emitter.config.sub_emitters.emitter.tick_interval")
        @NumberRange(range = {1, Integer.MAX_VALUE})
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
            if (father.getAge() % tickInterval == 0 && father.getRandomSource().nextFloat() < emitProbability.get(father.getT(0), () -> father.getMemRandom("sub_emitter_probability")).floatValue()) {
                var runtime = fx.createSubFXRuntime(emitter);
                if (runtime == null) return;
                runtime.root.updatePos(father.getWorldPos());
                for (var value : runtime.objects.values()) {
                    if (value instanceof IParticleEmitter emitter) {
                        if (inheritLifetime) {
                            emitter.setAge(father.getAge());
                        }
                        if (inheritDuration) {
                            emitter.self().setLifetime(father.getLifetime());
                        }
                        if (inheritColor) {
                            emitter.setRGBAColor(father.getRealColor(0));
                        }
                        if (inheritSize) {
                            emitter.transform().scale(father.getRealSize(0));
                        }
                        if (inheritRotation) {
                            var xyz = father.getRealRotation(0);
                            emitter.transform().rotation(new Quaternionf().rotationXYZ(xyz.x, xyz.y, xyz.z));
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
            if (Editor.INSTANCE instanceof FXEditor editor && editor.getCurrentProject() instanceof FXProject project) {
                project.getFx().getSubFXs().forEach((k, v) -> candidates.add(k));
            }
            var emitterSelector = new SelectorConfigurator<>("emitter", () -> emitter, v -> emitter = v,
                    "", true, candidates, s -> s);
            emitterSelector.setTips("photon.emitter.config.sub_emitters.emitter.name");
            father.addConfigurators(emitterSelector);
            IConfigurable.super.buildConfigurator(father);
        }
    }
}
