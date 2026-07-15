package com.lowdragmc.photon.client.gameobject.emitter.particle;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.accessors.Vector3fAccessor;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSelector;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSetter;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.BooleanConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.NumberConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorSelectorConfigurator;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.IModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.JsonModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshDataConfigurator;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Camera;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.function.TriFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.util.Objects;

@Getter
@Setter
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ParticleRendererSetting extends RendererSetting implements IConfigurable, IPersistedSerializable {

    public enum Mode {
        None((p, c, t) -> new Quaternionf()),
        Billboard((p, c, t) -> {
            // read the per-particle emitter's runtime so a per-instance facing override takes effect
            var renderer = p.getRuntime().renderer;
            return renderer.getFacingMode().compute(renderer.getFacingDirection(), p, c, t);
        }),
        Horizontal(0, -90),
        Vertical(0, 0),
        VerticalBillboard((p, c, t) -> {
            var quaternion = new Quaternionf();
            quaternion.rotateY((float) Math.toRadians(180 - c.getYRot()));
            return quaternion;
        }),
        StretchedBillboard((p, c, t) -> new Quaternionf()),
        Model((p, c, t) -> new Quaternionf());

        public final TriFunction<TileParticle, Camera, Float, Quaternionf> quaternion;

        Mode(TriFunction<TileParticle, Camera, Float, Quaternionf> quaternion) {
            this.quaternion = quaternion;
        }

        Mode(Quaternionf quaternion) {
            this.quaternion = (p, c, t) -> quaternion;
        }

        Mode(float yRot, float xRot) {
            var quaternion = new Quaternionf();
            quaternion.rotateY((float) Math.toRadians(yRot));
            quaternion.rotateX((float) Math.toRadians(xRot));
            this.quaternion = (p, c, t) -> quaternion;
        }
    }

    private final ParticleConfig config;
    @Configurable(name = "ParticleRendererSetting.renderMode", tips = "photon.emitter.config.renderer.renderMode")
    @ConfigSelector(subConfiguratorBuilder = "buildSubConfigurator")
    @EqualsAndHashCode.Include
    protected Mode renderMode = Mode.Billboard;
    @Nullable
    @EqualsAndHashCode.Include
    protected MeshData model;
    @Persisted
    @EqualsAndHashCode.Include
    protected boolean shade = true;
    @Persisted
    @EqualsAndHashCode.Include
    protected boolean useBlockUV = true;
    @Persisted
    @EqualsAndHashCode.Include
    protected Vector3f modelPivot = new Vector3f();
    @Persisted
    @EqualsAndHashCode.Include
    protected float velocityScale = 0.0f;
    @Persisted
    @EqualsAndHashCode.Include
    protected float lengthScale = 2.0f;
    @Configurable(name = "ParticleRendererSetting.useGPUInstance")
    @EqualsAndHashCode.Include
    private boolean useGPUInstance = false;
    @Persisted
    @EqualsAndHashCode.Include
    private FacingMode facingMode = FacingMode.DEFAULT;
    @Persisted(subPersisted = true)
    @EqualsAndHashCode.Include
    private final FacingDirectionSetting facingDirection = new FacingDirectionSetting(this);

    public ParticleRendererSetting(ParticleConfig config) {
        this.config = config;
    }

    /**
     * Slot-based per-emitter render override (see {@link RendererSetting.Runtime}) plus the tile-only
     * fields. Overriding one field writes ONE {@link RuntimeValue} slot — no whole-renderer copy — and
     * {@code getX()} falls back per-field to the immutable config. The render pipeline reads through this.
     */
    public static class Runtime extends RendererSetting.Runtime {
        private final ParticleRendererSetting config;
        public final RuntimeValue<Mode> renderMode;
        public final RuntimeValue<MeshData> model;
        public final RuntimeValue<Boolean> shade;
        public final RuntimeValue<Boolean> useBlockUV;
        public final RuntimeValue<Vector3f> modelPivot;
        public final RuntimeValue<Float> velocityScale;
        public final RuntimeValue<Float> lengthScale;
        public final RuntimeValue<Boolean> useGPUInstance;
        public final RuntimeValue<FacingMode> facingMode;
        public final RuntimeValue<FacingDirectionSetting> facingDirection;

        private Runtime(ParticleRendererSetting config) {
            super(config);
            this.config = config;
            this.renderMode = new RuntimeValue<>(config::getRenderMode);
            this.model = new RuntimeValue<>(config::getModel);
            this.shade = new RuntimeValue<>(config::isShade);
            this.useBlockUV = new RuntimeValue<>(config::isUseBlockUV);
            this.modelPivot = new RuntimeValue<>(config::getModelPivot);
            this.velocityScale = new RuntimeValue<>(config::getVelocityScale);
            this.lengthScale = new RuntimeValue<>(config::getLengthScale);
            this.useGPUInstance = new RuntimeValue<>(config::isUseGPUInstance);
            this.facingMode = new RuntimeValue<>(config::getFacingMode);
            this.facingDirection = new RuntimeValue<>(config::getFacingDirection);
        }

        public Mode getRenderMode() { return renderMode.get(); }
        public MeshData getModel() { return model.get(); }
        public IModelSource getModelSource() { return getModel().getSource(); }
        public boolean isShade() { return shade.get(); }
        public boolean isUseBlockUV() { return useBlockUV.get(); }
        public Vector3f getModelPivot() { return modelPivot.get(); }
        public float getVelocityScale() { return velocityScale.get(); }
        public float getLengthScale() { return lengthScale.get(); }
        public boolean isUseGPUInstance() { return useGPUInstance.get(); }
        public FacingMode getFacingMode() { return facingMode.get(); }
        public FacingDirectionSetting getFacingDirection() { return facingDirection.get(); }

        @Override
        public boolean hasOverride() {
            // only PASS-level fields (the batching key) force a separate render pass. facingMode/
            // facingDirection are read per-particle (the Billboard lambda reads p.getRuntime()), so
            // overriding them needs no pass — excluded here and from effectiveEquals.
            return super.hasOverride() || renderMode.isOverridden() || model.isOverridden()
                    || shade.isOverridden() || useBlockUV.isOverridden() || modelPivot.isOverridden()
                    || velocityScale.isOverridden() || lengthScale.isOverridden()
                    || useGPUInstance.isOverridden();
        }

        @Override
        public void clear() {
            super.clear();
            renderMode.clear();
            model.clear();
            shade.clear();
            useBlockUV.clear();
            modelPivot.clear();
            velocityScale.clear();
            lengthScale.clear();
            useGPUInstance.clear();
            facingMode.clear();
            facingDirection.clear();
        }

        // NOTE: facingMode/facingDirection are intentionally NOT part of the batching key — they are read
        // per-particle (the Billboard lambda reads p.getRuntime()), so emitters differing only in facing
        // still merge into one draw (each particle reads its own emitter's value). This diverges from the
        // authored ParticleRendererSetting's @EqualsAndHashCode on purpose.
        @Override
        public boolean effectiveEquals(RendererSetting.Runtime o) {
            if (!super.effectiveEquals(o) || !(o instanceof Runtime other)) return false;
            return getRenderMode() == other.getRenderMode()
                    && Objects.equals(getModel(), other.getModel())
                    && isShade() == other.isShade()
                    && isUseBlockUV() == other.isUseBlockUV()
                    && Objects.equals(getModelPivot(), other.getModelPivot())
                    && getVelocityScale() == other.getVelocityScale()
                    && getLengthScale() == other.getLengthScale()
                    && isUseGPUInstance() == other.isUseGPUInstance();
        }

        @Override
        public int effectiveHashCode() {
            return Objects.hash(super.effectiveHashCode(), getRenderMode(), getModel(), isShade(), isUseBlockUV(),
                    getModelPivot(), getVelocityScale(), getLengthScale(), isUseGPUInstance());
        }
    }

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    public void buildSubConfigurator(Mode mode, ConfiguratorGroup group) {
        if (mode == Mode.StretchedBillboard) {
            group.addConfigurators(
		            new NumberConfigurator("lengthScale", this::getLengthScale, value -> setLengthScale(value.floatValue()), 2.0f, true)
				            .setWheel(0.1f)
				            .setTips("photon.emitter.config.renderer.renderMode.stretchedBillboard.lengthScale"),
		            new NumberConfigurator("velocityScale", this::getVelocityScale, value -> setVelocityScale(value.floatValue()), 0.0f, true)
				            .setWheel(0.1f)
				            .setTips("photon.emitter.config.renderer.renderMode.stretchedBillboard.velocityScale"));
        }
        if (mode == Mode.Billboard) {
            var modeNames = java.util.Arrays.stream(FacingMode.values()).map(Enum::name).toList();
            group.addConfigurators(
                    new ConfiguratorSelectorConfigurator<>(
                            "ParticleRendererSetting.facingMode",
                            () -> facingMode.name(),
                            name -> setFacingMode(FacingMode.valueOf(name)),
                            FacingMode.DEFAULT.name(),
                            true,
                            modeNames,
                            s -> "ParticleRendererSetting.facingMode." + s,
                            (selectedName, subGroup) -> {
                                var selectedMode = FacingMode.valueOf(selectedName);
                                if (selectedMode.requiresDirection()) {
                                    facingDirection.buildConfigurator(subGroup);
                                }
                            }
                    ).setTips(
                            "photon.emitter.config.renderer.facingMode",
                            "photon.emitter.config.renderer.facingMode.DEFAULT",
                            "photon.emitter.config.renderer.facingMode.ROTATE_Y",
                            "photon.emitter.config.renderer.facingMode.LOOKAT_XYZ",
                            "photon.emitter.config.renderer.facingMode.LOOKAT_Y",
                            "photon.emitter.config.renderer.facingMode.LOOKAT_DIRECTION",
                            "photon.emitter.config.renderer.facingMode.DIRECTION_X",
                            "photon.emitter.config.renderer.facingMode.DIRECTION_Y",
                            "photon.emitter.config.renderer.facingMode.DIRECTION_Z",
                            "photon.emitter.config.renderer.facingMode.EMITTER_TRANSFORM_XY",
                            "photon.emitter.config.renderer.facingMode.EMITTER_TRANSFORM_XZ",
                            "photon.emitter.config.renderer.facingMode.EMITTER_TRANSFORM_YZ"
                    )
            );
        }
        if (mode == Mode.Model) {
            group.addConfigurators(
                    // drag a mesh resource in, or click the button to open the resource dialog;
                    // shade/useBlockUV are functional no-ops for raw-UV (obj) meshes by construction
                    new MeshDataConfigurator("model", this::getModel, this::setModel, new MeshData(), true),
                    new BooleanConfigurator("shade", this::isShade, this::setShade, true, true)
                            .setTips("photon.emitter.config.renderer.renderMode.model.shade"),
                    new BooleanConfigurator("useBlockUV", this::isUseBlockUV, this::setUseBlockUV, true, true)
                            .setTips("photon.emitter.config.renderer.renderMode.model.useBlockUV"),
                    new Vector3fAccessor().create("modelPivot", this::getModelPivot, this::setModelPivot,
                            true, getModelPivotField(), this)
                            .setTips("photon.emitter.config.renderer.renderMode.model.modelPivot")
                    );
        }
    }

    private Field getModelPivotField() {
        try {
            return getClass().getDeclaredField("modelPivot");
        } catch (Exception e) {
            Photon.LOGGER.error("Error getting modelPivot field", e);
            throw new RuntimeException(e);
        }
    }

    public MeshData getModel() {
        if (model == null) {
            model = new MeshData(new JsonModelSource(ResourceLocation.parse("block/dirt")));
        }
        return model;
    }

    /** Shortcut to the model's geometry source (render paths need mesh + UV semantics only). */
    public IModelSource getModelSource() {
        return getModel().getSource();
    }

    @ConfigSetter(field = "renderMode")
    public void setRenderMode(Mode mode) {
        this.renderMode = mode;
        clearRenderPassInstance();
    }

    public void setModel(MeshData model) {
        this.model = model;
        clearRenderPassInstance();
    }

    public void setShade(boolean shade) {
        this.shade = shade;
        clearRenderPassInstance();
    }

    public void setUseBlockUV(boolean useBlockUV) {
        this.useBlockUV = useBlockUV;
        clearRenderPassInstance();
    }

    public void setModelPivot(Vector3f modelPivot) {
        this.modelPivot = modelPivot;
        clearRenderPassInstance();
    }

    public void setVelocityScale(float velocityScale) {
        this.velocityScale = velocityScale;
        clearRenderPassInstance();
    }

    public void setLengthScale(float lengthScale) {
        this.lengthScale = lengthScale;
        clearRenderPassInstance();
    }

    public void setFacingMode(FacingMode facingMode) {
        this.facingMode = facingMode == null ? FacingMode.DEFAULT : facingMode;
        clearRenderPassInstance();
    }

    private void onFacingSettingChanged() {
        clearRenderPassInstance();
    }

    @Override
    public void deserializeNBT(HolderLookup.@NotNull Provider provider, @NotNull CompoundTag tag) {
        IPersistedSerializable.super.deserializeNBT(provider, tag);
        if (facingMode == null) {
            facingMode = FacingMode.DEFAULT;
        }
        if (renderMode == Mode.Model && tag.contains("model")) {
            // MeshData's deserializer tolerates legacy payloads (bare modelLocation / source wrapper)
            model = new MeshData(tag.getCompound("model"));
        }
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.@NotNull Provider provider) {
        var tag = IPersistedSerializable.super.serializeNBT(provider);
        if (renderMode == Mode.Model && model != null) {
            tag.put("model", model.serializeNBT(provider));
        }
        return tag;
    }
}
