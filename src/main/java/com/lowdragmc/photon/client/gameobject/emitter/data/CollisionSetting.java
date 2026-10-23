package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigList;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.TransformRefConfigurator;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.TransformRef;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.ReadOnlyManaged;
import com.lowdragmc.photon.client.collision.BlockFilter;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.collider.ColliderObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.gui.editor.view.FXHierarchyView;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** What particles collide with and how they respond, beyond {@link PhysicsSetting}'s bounce and friction. */
@OnlyIn(Dist.CLIENT)
@Getter
@Setter
public class CollisionSetting implements IConfigurable, IPersistedSerializable {

    public enum Shape {
        /** the particle's own size, as a box */
        Box,
        /** the particle's centre only */
        Point
    }

    public enum EntityResponse {
        /** entities block particles like blocks do */
        Solid,
        /** particles pass through and only report entering and leaving */
        Trigger
    }

    public enum EntityCategory {
        All,
        Living,
        Players,
        NonLiving
    }

    public enum ColliderFilter {
        /** every active collider in the same FX scene */
        SceneAll,
        /** only the colliders referenced in the list */
        List
    }

    public enum Dedupe {
        /** every impact reports */
        Off,
        /** each particle reports each target once (per cooldown) */
        PerParticle,
        /** the emitter reports each target once (per cooldown), whichever of its particles hits it */
        PerEmitter,
        /** the emitters of one effect that share a group name report each target once between them */
        PerGroup
    }

    @Configurable(name = "CollisionSetting.blocks", tips = "photon.emitter.config.physics.collision.blocks")
    protected boolean blocks = true;
    @Configurable(name = "CollisionSetting.fluids", tips = "photon.emitter.config.physics.collision.fluids")
    protected boolean fluids = false;
    @Configurable(name = "CollisionSetting.entities", tips = "photon.emitter.config.physics.collision.entities")
    protected boolean entities = false;
    @Configurable(name = "CollisionSetting.colliders", tips = "photon.emitter.config.physics.collision.colliders")
    protected boolean colliders = true;
    @Configurable(name = "CollisionSetting.shape", tips = "photon.emitter.config.physics.collision.shape")
    protected Shape shape = Shape.Box;
    @Configurable(name = "CollisionSetting.radiusScale", tips = "photon.emitter.config.physics.collision.radiusScale")
    @ConfigNumber(range = {0, 16}, wheel = 0.1)
    protected float radiusScale = 1;
    @Configurable(name = "CollisionSetting.killOnTriggerEnter", tips = "photon.emitter.config.physics.collision.killOnTriggerEnter")
    protected boolean killOnTriggerEnter = false;

    @Configurable(name = "CollisionSetting.response", subConfigurable = true, tips = "photon.emitter.config.physics.collision.response")
    public final Response response = new Response();
    @Configurable(name = "CollisionSetting.filters", subConfigurable = true, tips = "photon.emitter.config.physics.collision.filters")
    public final Filters filters = new Filters();
    @Configurable(name = "CollisionSetting.events", subConfigurable = true, tips = "photon.emitter.config.physics.collision.events")
    public final Events events = new Events();
    @Configurable(name = "CollisionSetting.advanced", subConfigurable = true, tips = "photon.emitter.config.physics.collision.advanced")
    public final Advanced advanced = new Advanced();

    /** How speed and lifetime change on every impact. */
    @Getter
    @Setter
    public static class Response implements IConfigurable, IPersistedSerializable {
        @Configurable(name = "CollisionSetting.dampen", tips = "photon.emitter.config.physics.collision.dampen")
        @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "lifetime", yAxis = "dampen"))
        protected NumberFunction dampen = NumberFunction.constant(0);
        @Configurable(name = "CollisionSetting.lifetimeLoss", tips = "photon.emitter.config.physics.collision.lifetimeLoss")
        @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "lifetime", yAxis = "loss"))
        protected NumberFunction lifetimeLoss = NumberFunction.constant(0);
        @Configurable(name = "CollisionSetting.minKillSpeed", tips = "photon.emitter.config.physics.collision.minKillSpeed")
        @ConfigNumber(range = {0, 100}, wheel = 0.01)
        protected float minKillSpeed = 0;
        @Configurable(name = "CollisionSetting.maxKillSpeed", tips = "photon.emitter.config.physics.collision.maxKillSpeed")
        @ConfigNumber(range = {0, 1000}, wheel = 0.1)
        protected float maxKillSpeed = 0;
        @Configurable(name = "CollisionSetting.maxBounces", tips = "photon.emitter.config.physics.collision.maxBounces")
        @ConfigNumber(range = {0, 1000}, wheel = 1)
        protected int maxBounces = 0;
        @Configurable(name = "CollisionSetting.killAfterImpacts", tips = "photon.emitter.config.physics.collision.killAfterImpacts")
        @ConfigNumber(range = {0, 1000}, wheel = 1)
        protected int killAfterImpacts = 0;
        @Configurable(name = "CollisionSetting.stick", tips = "photon.emitter.config.physics.collision.stick")
        protected boolean stick = false;
    }

    /** Which blocks, entities and colliders count. */
    @Getter
    @Setter
    public static class Filters implements IConfigurable, IPersistedSerializable {
        @Configurable(name = "CollisionSetting.onlyBlocks", tips = "photon.emitter.config.physics.collision.onlyBlocks")
        @ConfigList(configuratorMethod = "buildBlockEntry", addDefaultMethod = "addEmptyEntry")
        @ReadOnlyManaged(serializeMethod = "stringsSerialize", deserializeMethod = "stringsDeserialize")
        protected List<String> onlyBlocks = new ArrayList<>();
        @Configurable(name = "CollisionSetting.exceptBlocks", tips = "photon.emitter.config.physics.collision.exceptBlocks")
        @ConfigList(configuratorMethod = "buildBlockEntry", addDefaultMethod = "addEmptyEntry")
        @ReadOnlyManaged(serializeMethod = "stringsSerialize", deserializeMethod = "stringsDeserialize")
        protected List<String> exceptBlocks = new ArrayList<>();
        @Configurable(name = "CollisionSetting.entityResponse", tips = "photon.emitter.config.physics.collision.entityResponse")
        protected EntityResponse entityResponse = EntityResponse.Solid;
        @Configurable(name = "CollisionSetting.entityCategory", tips = "photon.emitter.config.physics.collision.entityCategory")
        protected EntityCategory entityCategory = EntityCategory.Living;
        @Configurable(name = "CollisionSetting.onlyEntities", tips = "photon.emitter.config.physics.collision.onlyEntities")
        @ConfigList(configuratorMethod = "buildEntityEntry", addDefaultMethod = "addEmptyEntry")
        @ReadOnlyManaged(serializeMethod = "stringsSerialize", deserializeMethod = "stringsDeserialize")
        protected List<String> onlyEntities = new ArrayList<>();
        @Configurable(name = "CollisionSetting.exceptEntities", tips = "photon.emitter.config.physics.collision.exceptEntities")
        @ConfigList(configuratorMethod = "buildEntityEntry", addDefaultMethod = "addEmptyEntry")
        @ReadOnlyManaged(serializeMethod = "stringsSerialize", deserializeMethod = "stringsDeserialize")
        protected List<String> exceptEntities = new ArrayList<>();
        @Configurable(name = "CollisionSetting.ignoreOwner", tips = "photon.emitter.config.physics.collision.ignoreOwner")
        protected boolean ignoreOwner = true;
        @Configurable(name = "CollisionSetting.sourceExclusionRadius", tips = "photon.emitter.config.physics.collision.sourceExclusionRadius")
        @ConfigNumber(range = {0, 64}, wheel = 0.25)
        protected float sourceExclusionRadius = 0;
        @Configurable(name = "CollisionSetting.ignoreSpectators", tips = "photon.emitter.config.physics.collision.ignoreSpectators")
        protected boolean ignoreSpectators = true;
        @Configurable(name = "CollisionSetting.pickableOnly", tips = "photon.emitter.config.physics.collision.pickableOnly")
        protected boolean pickableOnly = false;
        @Configurable(name = "CollisionSetting.colliderFilter", tips = "photon.emitter.config.physics.collision.colliderFilter")
        protected ColliderFilter colliderFilter = ColliderFilter.SceneAll;
        @Configurable(name = "CollisionSetting.colliderList", tips = "photon.emitter.config.physics.collision.colliderList")
        @ConfigList(configuratorMethod = "buildColliderEntry", addDefaultMethod = "addDefaultRef")
        @ReadOnlyManaged(serializeMethod = "refsSerialize", deserializeMethod = "refsDeserialize")
        protected List<TransformRef> colliderList = new ArrayList<>();

        public EntityCategory getEntityCategory() {
            return entityCategory == null ? EntityCategory.Living : entityCategory;
        }

        /** Whether the given collider passes the collider filter (matched by transform id). */
        public boolean accepts(ColliderObject collider) {
            if (colliderFilter == ColliderFilter.SceneAll) return true;
            var id = collider.transform().id();
            for (var ref : colliderList) {
                if (id.equals(ref.getTransformId())) return true;
            }
            return false;
        }

        private String addEmptyEntry() {
            return "";
        }

        private Configurator buildBlockEntry(Supplier<String> getter, Consumer<String> setter) {
            return IdLists.blockEntry(getter, setter);
        }

        private Configurator buildEntityEntry(Supplier<String> getter, Consumer<String> setter) {
            return IdLists.entityEntry(getter, setter);
        }

        private Configurator buildColliderEntry(Supplier<TransformRef> getter, Consumer<TransformRef> setter) {
            return new TransformRefConfigurator("collider", getter, setter, new TransformRef(), true) {
                @Override
                protected boolean canDropObject(@Nonnull Object object) {
                    return (object instanceof FXHierarchyView.DraggingNode(var draggedNode)
                            && draggedNode.key instanceof ColliderObject) || super.canDropObject(object);
                }

                @Override
                protected void onDropObject(@Nonnull Object object) {
                    if (object instanceof FXHierarchyView.DraggingNode(var draggedNode)) {
                        onValueUpdatePassively(new TransformRef(draggedNode.key.transform()));
                        updateValue();
                    } else {
                        super.onDropObject(object);
                    }
                }
            };
        }

        private TransformRef addDefaultRef() {
            return new TransformRef();
        }

        private ListTag stringsSerialize(List<String> values) {
            return IdLists.serialize(values);
        }

        private List<String> stringsDeserialize(ListTag tag) {
            return IdLists.deserialize(tag);
        }

        private ListTag refsSerialize(List<TransformRef> refs) {
            var list = new ListTag();
            for (var ref : refs) {
                list.add(StringTag.valueOf(ref.getTransformId() == null ? "" : ref.getTransformId().toString()));
            }
            return list;
        }

        private List<TransformRef> refsDeserialize(ListTag tag) {
            var refs = new ArrayList<TransformRef>();
            for (Tag element : tag) {
                var value = element.getAsString();
                try {
                    refs.add(value.isEmpty() ? new TransformRef() : new TransformRef(UUID.fromString(value)));
                } catch (IllegalArgumentException e) {
                    refs.add(new TransformRef());
                }
            }
            return refs;
        }
    }

    /** Which impacts and contact deaths are reported, to sub-emitters and listeners alike; the physics stays the same. */
    @Getter
    @Setter
    public static class Events implements IConfigurable, IPersistedSerializable {
        @Configurable(name = "CollisionSetting.minImpactSpeed", tips = "photon.emitter.config.physics.collision.minImpactSpeed")
        @ConfigNumber(range = {0, 100}, wheel = 0.01)
        protected float minImpactSpeed = 0;
        @Configurable(name = "CollisionSetting.dedupe", tips = "photon.emitter.config.physics.collision.dedupe")
        protected Dedupe dedupe = Dedupe.Off;
        @Configurable(name = "CollisionSetting.dedupeCooldown", tips = "photon.emitter.config.physics.collision.dedupeCooldown")
        @ConfigNumber(range = {0, 1000000}, wheel = 1)
        protected int cooldown = 0;
        @Configurable(name = "CollisionSetting.dedupeGroup", tips = "photon.emitter.config.physics.collision.dedupeGroup")
        protected String group = "";

        public Dedupe getDedupe() {
            return dedupe == null ? Dedupe.Off : dedupe;
        }

        public String getGroup() {
            return group == null ? "" : group;
        }
    }

    /** Solver limits; the defaults suit almost everything. */
    @Getter
    @Setter
    public static class Advanced implements IConfigurable, IPersistedSerializable {
        @Configurable(name = "CollisionSetting.maxContacts", tips = "photon.emitter.config.physics.collision.maxContacts")
        @ConfigNumber(range = {1, 16}, wheel = 1)
        protected int maxContacts = 4;
        @Configurable(name = "CollisionSetting.depenetrate", tips = "photon.emitter.config.physics.collision.depenetrate")
        protected boolean depenetrate = true;
        @Configurable(name = "CollisionSetting.maxDepenetration", tips = "photon.emitter.config.physics.collision.maxDepenetration")
        @ConfigNumber(range = {0, 8}, wheel = 0.05)
        protected float maxDepenetration = 0.5f;
    }

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    /** Per-emitter runtime layer: timeline slots and the resolved filters. */
    public static class Runtime {
        private final CollisionSetting config;
        public final RuntimeValue<Boolean> blocks;
        public final RuntimeValue<Boolean> fluids;
        public final RuntimeValue<Boolean> entities;
        public final RuntimeValue<Boolean> colliders;
        public final RuntimeValue<NumberFunction> dampen;
        public final RuntimeValue<NumberFunction> lifetimeLoss;
        @Nullable
        private BlockFilter blockFilter;
        @Nullable
        private List<String> blockFilterOnly, blockFilterExcept;
        private int blockFilterTags = -1;
        /** Null: every type. */
        @Nullable
        private Set<EntityType<?>> onlyTypes;
        private Set<EntityType<?>> exceptTypes = Set.of();
        @Nullable
        private List<String> typesOnly, typesExcept;
        private int typesTags = -1;

        public Runtime(CollisionSetting config) {
            this.config = config;
            this.blocks = new RuntimeValue<>(() -> config.blocks);
            this.fluids = new RuntimeValue<>(() -> config.fluids);
            this.entities = new RuntimeValue<>(() -> config.entities);
            this.colliders = new RuntimeValue<>(() -> config.colliders);
            this.dampen = new RuntimeValue<>(() -> config.response.dampen);
            this.lifetimeLoss = new RuntimeValue<>(() -> config.response.lifetimeLoss);
        }

        public CollisionSetting config() {
            return config;
        }

        public float getDampen(IParticle particle) {
            return dampen.get().get(particle.getT(), () -> particle.getMemRandom("collision_dampen")).floatValue();
        }

        public float getLifetimeLoss(IParticle particle) {
            return lifetimeLoss.get().get(particle.getT(), () -> particle.getMemRandom("collision_lifetime_loss")).floatValue();
        }

        /** The resolved block filter, or null for all blocks. Game thread. */
        @Nullable
        public BlockFilter blockFilter() {
            var filters = config.filters;
            int tags = BlockFilter.tagGeneration();
            if (blockFilterTags != tags || !filters.onlyBlocks.equals(blockFilterOnly) || !filters.exceptBlocks.equals(blockFilterExcept)) {
                blockFilterOnly = new ArrayList<>(filters.onlyBlocks);
                blockFilterExcept = new ArrayList<>(filters.exceptBlocks);
                blockFilterTags = tags;
                blockFilter = BlockFilter.of(blockFilterOnly, blockFilterExcept);
            }
            return blockFilter;
        }

        /** Whether an entity passes the category and type filters (the owner is excluded by the caller). Game thread. */
        public boolean acceptsEntity(Entity entity) {
            var filters = config.filters;
            if (filters.ignoreSpectators && entity.isSpectator()) return false;
            if (filters.pickableOnly && !entity.isPickable()) return false;
            boolean category = switch (filters.getEntityCategory()) {
                case All -> true;
                case Living -> entity instanceof LivingEntity;
                case Players -> entity instanceof Player;
                case NonLiving -> !(entity instanceof LivingEntity);
            };
            if (!category) return false;
            int tags = BlockFilter.tagGeneration();
            if (typesTags != tags || !filters.onlyEntities.equals(typesOnly) || !filters.exceptEntities.equals(typesExcept)) {
                typesOnly = new ArrayList<>(filters.onlyEntities);
                typesExcept = new ArrayList<>(filters.exceptEntities);
                typesTags = tags;
                onlyTypes = BlockFilter.hasEntries(typesOnly) ? resolveEntityTypes(typesOnly) : null;
                exceptTypes = resolveEntityTypes(typesExcept);
            }
            var type = entity.getType();
            return (onlyTypes == null || onlyTypes.contains(type)) && !exceptTypes.contains(type);
        }

        private static Set<EntityType<?>> resolveEntityTypes(List<String> entries) {
            var types = new HashSet<EntityType<?>>();
            for (var entry : entries) {
                if (entry == null) continue;
                var text = entry.trim();
                if (text.isEmpty()) continue;
                if (text.startsWith("#")) {
                    var id = ResourceLocation.tryParse(text.substring(1));
                    if (id == null) continue;
                    BuiltInRegistries.ENTITY_TYPE.getTagOrEmpty(TagKey.create(Registries.ENTITY_TYPE, id))
                            .forEach(holder -> types.add(holder.value()));
                } else {
                    var id = ResourceLocation.tryParse(text);
                    if (id != null) BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresent(types::add);
                }
            }
            return types;
        }

        public void clear() {
            blocks.clear();
            fluids.clear();
            entities.clear();
            colliders.clear();
            dampen.clear();
            lifetimeLoss.clear();
        }
    }
}
