package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.editor.accessors.TypesAccessor;
import com.lowdragmc.lowdraglib.gui.editor.annotation.ConfigAccessor;
import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.configurator.Configurator;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.gui.editor.runtime.ConfiguratorParser;
import com.lowdragmc.lowdraglib.gui.editor.runtime.PersistedParser;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.lowdragmc.lowdraglib.utils.ColorUtils;
import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.emitter.data.number.Constant;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.fx.IEffect;
import com.lowdragmc.photon.client.particle.LParticle;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
public class SubEmittersSetting extends ToggleGroup implements IConfigurable, ITagSerializable<CompoundTag> {

    @Setter
    @Getter
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

    public void triggerEvent(IParticleEmitter emitter, LParticle father, Event event) {
        if (emitter.getEffect() != null) {
            for (Emitter candidate : emitters) {
                if (candidate.event == event) {
                    var subParticle = candidate.spawnEmitter(father, emitter.getEffect());
                    if (subParticle != null) {
                        subParticle.setEffect(emitter.getEffect());
                        emitter.emitParticle(subParticle.self());
                    }
                }
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
            ConfiguratorParser.createConfigurators(group, new HashMap<>(), Emitter.class, emitter);
            Emitter finalEmitter = emitter;
            group.setDraggingConsumer(
                    o -> o instanceof IParticleEmitter,
                    o -> group.setBackground(ColorPattern.T_GREEN.rectTexture()),
                    o -> group.setBackground(IGuiTexture.EMPTY),
                    o -> {
                        if (o instanceof IParticleEmitter particleEmitter) {
                            finalEmitter.emitter = particleEmitter.getName();
                            consumer.accept(finalEmitter);
                        }
                        group.setBackground(IGuiTexture.EMPTY);
                    });
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

    public static class Emitter {
        @Configurable(tips = "photon.emitter.config.sub_emitters.emitter.name")
        protected String emitter = "";
        protected IParticleEmitter cache = null;
        @Configurable(tips = "photon.emitter.config.sub_emitters.emitter.event")
        protected Event event = Event.Birth;
        @Configurable(tips = "photon.emitter.config.sub_emitters.emitter.emit_probability")
        @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "probability", yAxis = "lifetime"))
        protected NumberFunction emitProbability = NumberFunction.constant(0);
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

        @Nullable
        public IParticleEmitter spawnEmitter(LParticle father, @Nonnull IEffect effect) {
            if (cache == null) cache = effect.getEmitterByName(emitter);
            if (cache != null && father.getRandomSource().nextFloat() < emitProbability.get(father.getT(0), () -> father.getMemRandom("sub_emitter_probability")).floatValue()) {
                var copied = cache.copy();
                copied.reset();
                copied.updatePos(father.getPos());
                if (inheritLifetime) {
                    copied.self().setAge(father.getAge());
                }
                if (inheritDuration) {
                    copied.self().setLifetime(father.getLifetime());
                }
                if (inheritColor) {
                    var color = father.getColor(0);
                    copied.self().setARGBColor(ColorUtils.color(color.w(), color.x(), color.y(), color.z()));
                }
                if (inheritSize) {
                    copied.self().setQuadSize(father.getQuadSize(0));
                }
                if (inheritRotation) {
                    copied.self().setRotation(father.getRotation(0));
                }
                return copied;
            }
            return null;
        }
    }
}
