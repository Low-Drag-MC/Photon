package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigList;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.syncdata.annotation.ReadOnlyManaged;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.render.PhotonStage;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.nbt.IntTag;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RendererSetting {

    /**
     * Which frame slot this emitter's draws belong to — the 1.21 setting, restored. 1.21 routed the
     * emitter into the opaque or the translucent particle queue; 26.1 has no such queues, so the
     * layer picks the {@link PhotonStage} Photon opens its own pass at (right after the solid
     * feature pass, or after vanilla's translucent particles).
     * <p>
     * Independent of a material's blend function: {@code Opaque} means "drawn early, writes depth so
     * later translucent geometry sorts against it", not "unblended".
     */
    public enum Layer {
        Opaque(PhotonStage.AFTER_OPAQUE_FEATURES),
        Translucent(PhotonStage.AFTER_TRANSLUCENT_PARTICLES);

        public final PhotonStage stage;

        Layer(PhotonStage stage) {
            this.stage = stage;
        }
    }

    public enum SortMode {
        NONE,
        DISTANCE;

        /**
         * The point to sort distances from, or null when this mode does not sort. 1.21 read
         * {@code RenderSystem.getVertexSorting()} — the engine's current global, which 26.1 dropped when
         * sorting moved onto {@code RenderSetup.sortOnUpload}; Photon bypasses that path (its drain opens
         * its own passes), so the extraction hands in the eye position instead. The sort itself is
         * {@link com.lowdragmc.photon.client.render.PhotonDistanceSort}.
         *
         * @param eye the viewer, in the space the geometry was baked in (Photon bakes camera-relative, so
         *            that is zero in-world and the scene eye in the editor, whose camera sits at origin)
         */
        @Nullable
        public org.joml.Vector3fc sortOrigin(org.joml.Vector3fc eye) {
            return this == DISTANCE ? eye : null;
        }
    }

    @Configurable(name = "RendererSetting.materials", collapse = false)
    @ConfigList(configuratorMethod = "createMaterialConfigurator", addDefaultMethod = "addDefaultMaterial")
    @ReadOnlyManaged(serializeMethod = "materialSerialize", deserializeMethod = "materialDeserialize")
    @EqualsAndHashCode.Include
    protected List<MaterialSetting> materials = new ArrayList<>();

    @Configurable(name = "RendererSetting.layer", tips = "photon.emitter.config.renderer.layer")
    @EqualsAndHashCode.Include
    protected Layer layer = Layer.Translucent;

    @Configurable(name = "RendererSetting.cull", subConfigurable = true, tips = "photon.emitter.config.renderer.cull")
    protected final Cull cull = new Cull();

    @Configurable(name = "photon.emitter.config.renderer.orderInLayer", tips = "photon.emitter.config.renderer.orderInLayer.tips")
    @EqualsAndHashCode.Include
    protected int orderInLayer = 0;

    @Configurable(name = "photon.emitter.config.renderer.vertexSortingMode", tips = "photon.emitter.config.renderer.vertexSortingMode.tips")
    @EqualsAndHashCode.Include
    protected SortMode vertexSortingMode = SortMode.NONE;

    /** CustomMask (Unreal CustomDepth/Stencil-style): when enabled, this emitter's passes redraw a
     *  flat mask id into the pipeline's mask target, which post effects read via the Custom
     *  Mask/Depth input nodes. Part of the batching key — passes with different mask settings must
     *  not merge ({@link CustomMaskSetting#equals}). */
    @Configurable(name = "photon.emitter.config.renderer.customMask", subConfigurable = true, tips = "photon.emitter.config.renderer.writeCustomMask.tips")
    @EqualsAndHashCode.Include
    protected final CustomMaskSetting customMask = new CustomMaskSetting();

    public static class CustomMaskSetting extends ToggleGroup {
        /** The named mask group this emitter writes (session ids are assigned by {@code MaskGroups};
         *  every persisted form is the string). Effects/clips filter by the same name. */
        @Getter
        @Setter
        @Configurable(name = "photon.emitter.config.renderer.maskGroup", tips = "photon.emitter.config.renderer.maskGroup.tips")
        protected String maskGroup = "default";

        /** 0 = off (the mask covers the whole geometry, i.e. a rectangle for billboards); above 0
         *  the mask sub-pass samples the pass's texture and discards fragments below the cutoff,
         *  clipping the mask to the sprite's shape (needs a texture material on the pass). */
        @Getter
        @Setter
        @Configurable(name = "photon.emitter.config.renderer.maskAlphaCutoff", tips = "photon.emitter.config.renderer.maskAlphaCutoff.tips")
        @ConfigNumber(range = {0, 1})
        protected float maskAlphaCutoff = 0f;

        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof CustomMaskSetting that && isEnable() == that.isEnable()
                    && Objects.equals(maskGroup, that.maskGroup)
                    && maskAlphaCutoff == that.maskAlphaCutoff;
        }

        @Override
        public int hashCode() {
            return Objects.hash(isEnable(), maskGroup, maskAlphaCutoff);
        }
    }

    public boolean isWriteCustomMask() { return customMask.isEnable(); }
    public String getMaskGroup() { return customMask.getMaskGroup(); }
    public float getMaskAlphaCutoff() { return customMask.getMaskAlphaCutoff(); }

    /**
     * The render pass whose instanced GL to tear down when this AUTHORED renderer's structure changes via
     * a setter (transient; NOT persisted / NOT in equals/hashCode). The config renderer points this at its
     * {@code config.particleRenderType} so editor-inspector edits rebuild the shared pass's GL. Per-emitter
     * overrides go through {@link Runtime} slots (not these setters), so they never touch this. Only the
     * tile {@code ParticleRendererSetting} has setters that call {@link #clearRenderPassInstance()}; the
     * instanced settings never tear down GL from a setter.
     */
    @Nullable
    protected transient PhotonFXRenderPass ownerRenderPass;

    public static class Cull extends ToggleGroup {
        @Setter
        @Getter
        @Configurable
        @ConfigNumber(range = {-Float.MAX_VALUE, Float.MAX_VALUE})
        protected AABB cullBox = new AABB(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5);

        public AABB getCullAABB(Emitter particle, float partialTicks) {
            var transform = particle.transform();
            var scale = transform.scale();
            var pos = transform.position();
            return new AABB(cullBox.minX * scale.x, cullBox.minY * scale.y, cullBox.minZ * scale.z,
                    cullBox.maxX * scale.x, cullBox.maxY * scale.y, cullBox.maxZ * scale.z)
                    .move(pos.x, pos.y, pos.z);
        }
    }

    private Configurator createMaterialConfigurator(Supplier<MaterialSetting> getter, Consumer<MaterialSetting> setter) {
        var configurator = getter.get().createDirectConfigurator();
        if (configurator instanceof ConfiguratorGroup group) {
            group.setCollapse(false);
            group.lineContainer.setDisplay(false);
            return group;
        }
        return configurator;
    }

    private MaterialSetting addDefaultMaterial() {
        return new MaterialSetting();
    }

    private IntTag materialSerialize(List<MaterialSetting> value) {
        return IntTag.valueOf(value.size());
    }

    private List<MaterialSetting> materialDeserialize(IntTag size) {
        var materials = new ArrayList<MaterialSetting>();
        for (int i = 0; i < size.intValue(); i++) {
            materials.add(addDefaultMaterial());
        }
        return materials;
    }

    public void setOwnerRenderPass(@Nullable PhotonFXRenderPass ownerRenderPass) {
        this.ownerRenderPass = ownerRenderPass;
    }

    /** Tear down the owning pass's instanced GL after a structure change (no-op until the pass is wired). */
    protected void clearRenderPassInstance() {
        if (ownerRenderPass != null) {
            ownerRenderPass.clearInstance();
        }
    }

    /**
     * Per-emitter render-override layer, co-located here exactly like the other {@code Setting.Runtime}s:
     * the config stays immutable pure data, this holds a named {@link RuntimeValue} slot per renderer
     * field, and each {@code getX()} reads "slot if overridden, else config". Overriding one field (e.g.
     * {@code renderMode}) writes ONE slot — there is NO whole-renderer copy. The owning {@code
     * ParticleRuntime} turns "any slot overridden" into a per-emitter override {@link PhotonFXRenderPass};
     * {@link #effectiveEquals}/{@link #effectiveHashCode} over the effective values are that pass's
     * batching key, so identical overrides still merge into a single draw. Subclasses add their own slots
     * and extend {@code getX}/{@link #hasOverride}/{@link #clear}/{@link #effectiveEquals}.
     */
    public static class Runtime {
        protected final RendererSetting config;
        public final RuntimeValue<List<MaterialSetting>> materials;
        public final RuntimeValue<Layer> layer;
        public final RuntimeValue<Cull> cull;
        public final RuntimeValue<Integer> orderInLayer;
        public final RuntimeValue<SortMode> vertexSortingMode;
        public final RuntimeValue<Boolean> writeCustomMask;
        public final RuntimeValue<String> maskGroup;
        public final RuntimeValue<Float> maskAlphaCutoff;

        protected Runtime(RendererSetting config) {
            this.config = config;
            this.materials = new RuntimeValue<>(config::getMaterials);
            this.layer = new RuntimeValue<>(config::getLayer);
            this.cull = new RuntimeValue<>(config::getCull);
            this.orderInLayer = new RuntimeValue<>(config::getOrderInLayer);
            this.vertexSortingMode = new RuntimeValue<>(config::getVertexSortingMode);
            this.writeCustomMask = new RuntimeValue<>(config::isWriteCustomMask);
            this.maskGroup = new RuntimeValue<>(config::getMaskGroup);
            this.maskAlphaCutoff = new RuntimeValue<>(config::getMaskAlphaCutoff);
        }

        public List<MaterialSetting> getMaterials() { return materials.get(); }
        public Layer getLayer() { return layer.get(); }
        public Cull getCull() { return cull.get(); }
        public int getOrderInLayer() { return orderInLayer.get(); }
        public SortMode getVertexSortingMode() { return vertexSortingMode.get(); }
        public boolean isWriteCustomMask() { return writeCustomMask.get(); }
        public String getMaskGroup() { return maskGroup.get(); }
        public float getMaskAlphaCutoff() { return maskAlphaCutoff.get(); }

        /**
         * Whether a PASS-LEVEL slot is overridden — i.e. this emitter needs its own render pass instead
         * of sharing the config's. Only fields the {@code RenderPass}/its renderer read at pass level (the
         * batching-key set = {@link #effectiveEquals}) count. {@code cull} is deliberately excluded: it is
         * per-emitter culling read by {@code getCullBox}, not a pass concern, so overriding it needs no pass.
         */
        public boolean hasOverride() {
            return materials.isOverridden() || layer.isOverridden()
                    || orderInLayer.isOverridden() || vertexSortingMode.isOverridden()
                    || writeCustomMask.isOverridden() || maskGroup.isOverridden()
                    || maskAlphaCutoff.isOverridden();
        }

        /** Clear every override slot (fall back to the authored config). */
        public void clear() {
            materials.clear();
            layer.clear();
            cull.clear();
            orderInLayer.clear();
            vertexSortingMode.clear();
            writeCustomMask.clear();
            maskGroup.clear();
            maskAlphaCutoff.clear();
        }

        /**
         * Effective-value equality for the batching key. It MUST mirror {@link RendererSetting}'s
         * {@code @EqualsAndHashCode.Include} set (keep in sync when a keyed field is added) — {@code cull}
         * is excluded, matching the config. Only ever compares runtimes of the same concrete type.
         */
        public boolean effectiveEquals(Runtime o) {
            return Objects.equals(getMaterials(), o.getMaterials())
                    && getLayer() == o.getLayer()
                    && getOrderInLayer() == o.getOrderInLayer()
                    && getVertexSortingMode() == o.getVertexSortingMode()
                    && isWriteCustomMask() == o.isWriteCustomMask()
                    && Objects.equals(getMaskGroup(), o.getMaskGroup())
                    && getMaskAlphaCutoff() == o.getMaskAlphaCutoff();
        }

        public int effectiveHashCode() {
            return Objects.hash(getMaterials(), getLayer(), getOrderInLayer(), getVertexSortingMode(),
                    isWriteCustomMask(), getMaskGroup(), getMaskAlphaCutoff());
        }
    }

}
