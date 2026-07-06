package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigList;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.TransformRefConfigurator;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.TransformRef;
import com.lowdragmc.lowdraglib2.syncdata.annotation.ReadOnlyManaged;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.forcefield.ForceFieldObject;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.gui.editor.view.FXHierarchyView;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Pure-data external-forces config (Unity {@code ExternalForcesModule} parity): lets sibling
 * {@link ForceFieldObject}s in the same FX scene affect this emitter's particles, scaled by a
 * multiplier curve, optionally restricted to an explicit list of fields.
 */
@OnlyIn(Dist.CLIENT)
@Setter
@Getter
public class ExternalForcesSetting extends ToggleGroup {

    public enum Filter {
        /** every active force field in the same FX scene affects this emitter */
        SceneAll,
        /** only the force fields referenced in the influence list */
        List
    }

    @Configurable(name = "ExternalForcesSetting.multiplier", tips = "photon.emitter.config.externalForces.multiplier")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 1f, curveConfig = @CurveConfig(bound = {0, 2}, xAxis = "lifetime", yAxis = "multiplier"))
    protected NumberFunction multiplier = NumberFunction.constant(1);

    @Configurable(name = "ExternalForcesSetting.influenceFilter", tips = "photon.emitter.config.externalForces.influenceFilter")
    protected Filter influenceFilter = Filter.SceneAll;

    @Configurable(name = "ExternalForcesSetting.influenceList", tips = "photon.emitter.config.externalForces.influenceList")
    @ConfigList(configuratorMethod = "buildRefConfigurator", addDefaultMethod = "addDefaultRef")
    @ReadOnlyManaged(serializeMethod = "refsSerialize", deserializeMethod = "refsDeserialize")
    protected List<TransformRef> influenceList = new ArrayList<>();

    /** Whether the given force field passes this setting's influence filter (matched by transform id). */
    public boolean isInfluencedBy(ForceFieldObject field) {
        if (influenceFilter == Filter.SceneAll) {
            return true;
        }
        var id = field.transform().id();
        for (var ref : influenceList) {
            if (id.equals(ref.getTransformId())) {
                return true;
            }
        }
        return false;
    }

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    /** Per-emitter runtime: timeline-overridable enable + multiplier slots. */
    public static class Runtime {
        private final ExternalForcesSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<NumberFunction> multiplier;

        public Runtime(ExternalForcesSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.multiplier = new RuntimeValue<>(() -> config.multiplier);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public ExternalForcesSetting getConfig() {
            return config;
        }

        public float getMultiplier(IParticle particle) {
            return multiplier.get().get(particle.getT(), () -> particle.getMemRandom(this)).floatValue();
        }

        public void clear() {
            enable.clear();
            multiplier.clear();
        }
    }

    private Configurator buildRefConfigurator(Supplier<TransformRef> getter, Consumer<TransformRef> setter) {
        return new TransformRefConfigurator("force_field", getter, setter, new TransformRef(), true) {
            @Override
            protected boolean canDropObject(@Nonnull Object object) {
                // only force-field nodes are meaningful influence entries
                return (object instanceof FXHierarchyView.DraggingNode(var draggedNode)
                        && draggedNode.key instanceof ForceFieldObject) || super.canDropObject(object);
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

    // TransformRef elements are INBTSerializable (not IPersistedSerializable), so the managed
    // serialize/deserialize methods carry the full list state (a ListTag of uuid strings).
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
            if (value.isEmpty()) {
                refs.add(new TransformRef());
            } else {
                try {
                    refs.add(new TransformRef(UUID.fromString(value)));
                } catch (IllegalArgumentException e) {
                    refs.add(new TransformRef());
                }
            }
        }
        return refs;
    }
}
