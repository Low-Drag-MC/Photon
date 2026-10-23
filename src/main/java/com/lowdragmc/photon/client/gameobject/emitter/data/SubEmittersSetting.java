package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigList;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.SearchComponentConfigurator;
import com.lowdragmc.lowdraglib2.gui.ui.utils.UIElementProvider;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib2.syncdata.annotation.ReadOnlyManaged;
import com.lowdragmc.photon.client.collision.BlockFilter;
import com.lowdragmc.photon.client.collision.CollisionEvent;
import com.lowdragmc.photon.client.collision.Contact;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    @Configurable(name = "SubEmittersSetting.emitters")
    @ConfigList(configuratorMethod = "buildEmitterConfigurator", addDefaultMethod = "addDefaultEmitter")
    @ReadOnlyManaged(serializeMethod = "emittersSerialize", deserializeMethod = "emittersDeserialize")
    protected List<Emitter> emitters = new ArrayList<>();

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    public static class Runtime {
        private final SubEmittersSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<List<Emitter>> emitters; // slot only (list → no timeline binding)

        public Runtime(SubEmittersSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.emitters = new RuntimeValue<>(config::getEmitters);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public void triggerEvent(TileParticle father, Event event) {
            triggerEvent(father, event, null);
        }

        /** {@code contact} is the collision behind the event, if any: emitters can spawn at its point. */
        public void triggerEvent(TileParticle father, Event event, @Nullable CollisionEvent contact) {
            for (Emitter candidate : emitters.get()) {
                if (candidate.event == event && candidate.accepts(contact)) {
                    candidate.spawnEmitter(father, contact);
                }
            }
        }

        /**
         * Tick event with integer-tick-crossing semantics: fires when this step crossed a whole tick
         * and that tick matches the candidate's tickInterval. Robust under fractional dt (sub-stepped
         * timeline speed), where the particle's float age almost never lands exactly on an integer.
         */
        public void triggerTickEvent(TileParticle father, float dt) {
            var newTick = Mth.floor(father.getAge());
            if (newTick <= Mth.floor(father.getAge() - dt)) {
                return; // no whole tick crossed this step (dt <= 1 => at most one crossing)
            }
            for (Emitter candidate : emitters.get()) {
                if (candidate.event == Event.Tick && newTick % candidate.tickInterval == 0) {
                    candidate.spawnEmitter(father);
                }
            }
        }

        public void clear() {
            enable.clear();
            emitters.clear();
        }
    }

    private Configurator buildEmitterConfigurator(Supplier<Emitter> getter, Consumer<Emitter> setter) {
        var instance = getter.get();
        if (instance != null && instance.createDirectConfigurator() instanceof ConfiguratorGroup group) {
            group.setCollapse(false);
            group.lineContainer.setDisplay(false);
            return group;
        }
        return new Configurator();
    }

    private Emitter addDefaultEmitter() {
        var emitter = new Emitter();
        emitter.spawnAtContact = true;
        return emitter;
    }

    private IntTag emittersSerialize(List<Emitter> bursts) {
        return IntTag.valueOf(bursts.size());
    }

    private List<Emitter> emittersDeserialize(IntTag tag) {
        var groups = new ArrayList<Emitter>();
        for (int i = 0; i < tag.getAsInt(); i++) {
            groups.add(new Emitter());
        }
        return groups;
    }

    public enum Event {
        Birth,
        Death,
        Collision,
        FirstCollision,
        Tick,
        TriggerEnter,
        TriggerExit;

        /** Whether a contact can be behind it, which is what {@link Surface} filters. */
        public boolean fromContact() {
            return this != Birth && this != Tick;
        }
    }

    public enum Surface {
        Any,
        Blocks,
        Fluids,
        Entities,
        Colliders
    }

    @Setter
    @Getter
    public static class Emitter implements IConfigurable, IPersistedSerializable {
        @Nullable
        @Persisted
        protected ResourceLocation fxLocation = null;
        @Configurable(name = "Emitter.event", tips = "photon.emitter.config.sub_emitters.emitter.event")
        protected Event event = Event.Birth;
        @Configurable(name = "Emitter.emitProbability", tips = "photon.emitter.config.sub_emitters.emitter.emit_probability")
        @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "probability", yAxis = "lifetime"))
        protected NumberFunction emitProbability = NumberFunction.constant(0);
        @Configurable(name = "Emitter.tickInterval", tips = "photon.emitter.config.sub_emitters.emitter.tick_interval")
        @ConfigNumber(range = {1, Integer.MAX_VALUE})
        protected int tickInterval = 1;
        @Configurable(name = "Emitter.inheritColor", tips = "photon.emitter.config.sub_emitters.emitter.inherit_color")
        protected boolean inheritColor = false;
        @Configurable(name = "Emitter.inheritSize", tips = "photon.emitter.config.sub_emitters.emitter.inherit_size")
        protected boolean inheritSize = false;
        @Configurable(name = "Emitter.inheritRotation", tips = "photon.emitter.config.sub_emitters.emitter.inherit_rotation")
        protected boolean inheritRotation = false;
        @Configurable(name = "Emitter.inheritLifetime", tips = "photon.emitter.config.sub_emitters.emitter.inherit_lifetime")
        protected boolean inheritLifetime = false;
        @Configurable(name = "Emitter.inheritDuration", tips = "photon.emitter.config.sub_emitters.emitter.inherit_duration")
        protected boolean inheritDuration = false;
        /** False for entries saved before the option existed; new entries start with it on. */
        @Configurable(name = "Emitter.spawnAtContact", tips = "photon.emitter.config.sub_emitters.emitter.spawn_at_contact")
        protected boolean spawnAtContact = false;
        @Configurable(name = "Emitter.alignToNormal", tips = "photon.emitter.config.sub_emitters.emitter.align_to_normal")
        protected boolean alignToNormal = false;
        @Configurable(name = "Emitter.surface", tips = "photon.emitter.config.sub_emitters.emitter.surface")
        protected Surface surface = Surface.Any;
        @Configurable(name = "Emitter.surfaceBlocks", tips = "photon.emitter.config.sub_emitters.emitter.surface_blocks")
        @ConfigList(configuratorMethod = "buildBlockEntry", addDefaultMethod = "addEmptyEntry")
        @ReadOnlyManaged(serializeMethod = "stringsSerialize", deserializeMethod = "stringsDeserialize")
        protected List<String> surfaceBlocks = new ArrayList<>();
        /** The resolved {@link #surfaceBlocks}; read from particle workers, so replaced whole, never changed. */
        @Nullable
        private volatile SurfaceFilter surfaceFilter;

        private record SurfaceFilter(List<String> entries, int tags, @Nullable BlockFilter filter) {
        }

        /** Whether the contact behind an event passes {@link #surface}; events without one pass only {@link Surface#Any}. Any thread. */
        public boolean accepts(@Nullable CollisionEvent contact) {
            var surface = getSurface();
            if (surface == Surface.Any || event == null || !event.fromContact()) return true;
            if (contact == null) return false;
            byte kind = switch (surface) {
                case Blocks -> Contact.BLOCK;
                case Fluids -> Contact.FLUID;
                case Entities -> Contact.ENTITY;
                default -> Contact.COLLIDER;
            };
            if (contact.kind() != kind) return false;
            if (surface != Surface.Blocks && surface != Surface.Fluids) return true;
            var filter = blockFilter();
            return filter == null || (contact.target() instanceof BlockState state && filter.test(state));
        }

        public Surface getSurface() {
            return surface == null ? Surface.Any : surface;
        }

        @Nullable
        private BlockFilter blockFilter() {
            var cached = surfaceFilter;
            int tags = BlockFilter.tagGeneration();
            if (cached == null || cached.tags != tags || !cached.entries.equals(surfaceBlocks)) {
                var entries = List.copyOf(surfaceBlocks.stream().map(entry -> entry == null ? "" : entry).toList());
                cached = new SurfaceFilter(entries, tags, BlockFilter.of(entries, List.of()));
                surfaceFilter = cached;
            }
            return cached.filter;
        }

        private String addEmptyEntry() {
            return "";
        }

        private Configurator buildBlockEntry(Supplier<String> getter, Consumer<String> setter) {
            return IdLists.blockEntry(getter, setter);
        }

        private ListTag stringsSerialize(List<String> values) {
            return IdLists.serialize(values);
        }

        private List<String> stringsDeserialize(ListTag tag) {
            return IdLists.deserialize(tag);
        }

        public void spawnEmitter(TileParticle father) {
            spawnEmitter(father, null);
        }

        public void spawnEmitter(TileParticle father, @Nullable CollisionEvent contact) {
            if (fxLocation == null) {
                return;
            }
            // probability roll stays here: the particle's RandomSource is confined to its update thread
            boolean spawns = father.getRandomSource().nextFloat() < emitProbability.get(father.getT(0),
                    () -> father.getMemRandom("sub_emitter_probability")).floatValue();
            // FXHelper.getFX + FXRuntime.emit (particle engine) are not thread-safe: particle updates
            // may run on parallelStream workers, so defer the spawn to the game thread — the owning
            // emitter drains it at the end of the same tick (deferred in serial mode too, so ordering
            // is mode-independent; a dead particle's fields stay valid until next tick's removal).
            if (father.getEmitter() instanceof ParticleEmitter particleEmitter) {
                // a de-duplicated contact counts as reported whether or not this roll spawns
                boolean dedupes = contact != null && particleEmitter.dedupes(contact);
                if (!spawns && !dedupes) return;
                particleEmitter.scheduleSubEmitterSpawn(father.nextSpawnOrder(), () -> {
                    if (dedupes && !particleEmitter.passesDedupe(father, contact)) return;
                    if (spawns) doSpawn(father, contact);
                });
            } else if (spawns) {
                doSpawn(father, contact); // non-ParticleEmitter owners never parallel-update TileParticles
            }
        }

        private void doSpawn(TileParticle father, @Nullable CollisionEvent contact) {
            var fx = FXHelper.getFX(fxLocation);
            if (fx == null) return;
            var runtime = fx.createRuntime();
            runtime.root.updatePos(contact != null && spawnAtContact ? contact.position() : father.getWorldPos());
            if (contact != null && alignToNormal) {
                var normal = contact.normal();
                // trigger exits have no surface, so no normal
                if (normal.lengthSquared() > 0.5f) {
                    runtime.root.updateRotation(new Quaternionf().rotationTo(0, 1, 0, normal.x, normal.y, normal.z));
                }
            }
            for (var value : runtime.objects.values()) {
                if (value instanceof IParticleEmitter particleEmitter) {
                    if (inheritLifetime) {
                        particleEmitter.setAge((int) father.getAge());
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
            runtime.emit(father.getEmitter().getEffectExecutor());
        }

        @Override
        public void buildConfigurator(ConfiguratorGroup father) {
            // The search runs per keystroke, so it gets a plain list to scan: FXHelper owns the caching
            // (and drops it on resource reload), and the ids are turned into strings once here rather
            // than once per search. The leading "" is the entry that clears the selection.
            var candidates = new ArrayList<String>();
            candidates.add("");
            FXHelper.listAllFX().forEach(fx -> candidates.add(fx.toString()));

            father.addConfigurators(new SearchComponentConfigurator<>("fx",
                    () -> fxLocation == null ? "" : fxLocation.toString(),
                    v -> fxLocation = (v == null || v.isEmpty()) ? null : ResourceLocation.parse(v),
                    "", true,
                    (word, handler) -> {
                        var search = word.toLowerCase(Locale.ROOT);
                        for (var candidate : candidates) {
                            // the search runs off-thread and is cancelled when the query moves on
                            if (Thread.currentThread().isInterrupted()) return;
                            if (candidate.toLowerCase(Locale.ROOT).contains(search)) {
                                handler.accept(candidate);
                            }
                        }
                    },
                    s -> s,
                    UIElementProvider.text(s -> Component.literal(s == null || s.isEmpty() ? "---" : s)))
                    .setTips("photon.emitter.config.sub_emitters.emitter.name"));
            IConfigurable.super.buildConfigurator(father);
        }
    }
}
