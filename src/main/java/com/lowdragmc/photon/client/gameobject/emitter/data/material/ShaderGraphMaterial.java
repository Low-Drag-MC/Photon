package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.runtime.RenderTypeFactory;
import com.lowdragmc.kilagraph.rendertype.runtime.RenderTypeGraphMaterial;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinPath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Tooltips;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.IFieldValueConfigurable;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandle;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.AutoCloseCleaner;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.render.MaterialPreviewRenderer;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PhotonRenderTypes;
import com.lowdragmc.photon.client.render.PhotonWorldRenderState;
import com.lowdragmc.photon.client.shadergraph.runtime.ShaderGraphRuntime;
import com.lowdragmc.photon.gui.editor.resource.ShaderGraphResource;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.vfyjxf.taffy.style.AlignItems;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A material driven by a {@link com.lowdragmc.photon.client.shadergraph.ShaderGraph} resource. The
 * material stores only an {@link IResourcePath} reference plus its own <b>overrides</b> for the graph's
 * exposed variables — the compiled shader (and its instancing variants) is shared across every material
 * referencing the same graph via {@link ShaderGraphRuntime}, while each material stages its own uniform
 * values right before its draw, so materials never share value state. Untouched variables keep tracking
 * the graph's defaults (edits to the graph propagate on recompile).
 *
 * <p>Works on every Photon render path: one RenderType per {@code MaterialSetting} state (which carries the
 * primitive mode, so CPU trails/ara-trails get their own pipeline), off a pipeline the emitter re-derives
 * per instancing variant ({@code ""} CPU geometry, {@code PARTICLE_INSTANCE}, {@code PARTICLE_MODEL_INSTANCE},
 * {@code TRAIL_INSTANCE}, ...) — in 26.1 the shader define is a pipeline property, not a bind-time choice.
 * Scene color/depth read the render pipeline's scene sampler (Iris-compatible), never KilaGraph's own
 * capture.</p>
 */
@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "shader_graph", registry = "photon:material")
public class ShaderGraphMaterial extends ShaderInstanceMaterial {

    @Persisted
    private IResourcePath graphPath = new BuiltinPath("");

    /** Exposed-variable overrides by display name; only variables the user actually touched. */
    private final Map<String, Object> overrides = new LinkedHashMap<>();

    // runtime
    @Nullable
    private ShaderGraphRuntime.Entry entry;

    public ShaderGraphMaterial() {
    }

    public ShaderGraphMaterial(IResourcePath graphPath) {
        this.graphPath = graphPath;
    }

    /**
     * Never null. An empty {@code BuiltinPath("")} round-trips to null through {@code IResourcePath.CODEC}
     * ({@code "builtin()"} fails the parse pattern), and {@code @Persisted} writes the field reflectively —
     * so a deserialized material can carry null here; normalize on read instead of trusting the field.
     */
    public IResourcePath getGraphPath() {
        if (graphPath == null) graphPath = new BuiltinPath("");
        return graphPath;
    }

    public void setGraphPath(@Nullable IResourcePath graphPath) {
        this.graphPath = graphPath == null ? new BuiltinPath("") : graphPath;
        this.entry = null;
    }

    public boolean isCompiledError() {
        return entry != null && !entry.isValid();
    }

    public String getCompiledErrorMessage() {
        return entry == null ? "" : entry.getErrorMessage();
    }

    /** Resolve the shared compiled entry, pruning stale overrides when it changed. */
    @Nullable
    private ShaderGraphRuntime.Entry refreshEntry() {
        var current = ShaderGraphRuntime.get(getGraphPath());
        if (current != entry) {
            entry = current;
            if (current != null) {
                reconcileOverrides(current);
            }
        }
        return current;
    }

    /**
     * Drop overrides whose stored value type no longer matches the graph variable's current type.
     * Overrides are keyed by name and persisted self-describing, so changing a variable's type in the
     * graph (same name) leaves a stale value of the old type behind — feeding it to the new type's
     * configurator would throw (e.g. a GradientValue into a Color editor's int cast), and it would
     * never apply anyway. Reconciled against the graph's declared default type for each name.
     */
    private void reconcileOverrides(ShaderGraphRuntime.Entry entry) {
        if (overrides.isEmpty() || entry.getGraph() == null) return;
        var expected = new HashMap<String, Class<?>>();
        for (var declaration : entry.getGraph().graphModel.getGraphVariableModels()) {
            if (declaration == null) continue;
            var def = declaration.tryGetDefaultValue(declaration.getDataType()).result().orElse(null);
            if (def != null) expected.put(declaration.getName(), def.getClass());
        }
        if (overrides.entrySet().removeIf(e -> {
            var cls = expected.get(e.getKey());
            return cls != null && !cls.isInstance(e.getValue());
        })) {
            invalidateOverridesCache();
        }
    }

    /** {@code PhotonGpuChannels} bits of the additional-data channels the compiled graph reads
     *  (0 when unresolved/broken) — lets instanced render passes auto-enable required channels. */
    public long getUsedChannelMask() {
        var entry = refreshEntry();
        return entry != null && entry.isValid() ? entry.getUsedChannelMask() : 0L;
    }

    /** Whether the compiled graph reads any user custom-data stream (a {@code CustomDataNode}) —
     *  lets instanced render passes upload the {@code PhotonCustomData} buffer texture only when needed. */
    public boolean usesCustomData() {
        var entry = refreshEntry();
        return entry != null && entry.isValid() && entry.isUsesCustomData();
    }

    // ---- 26.1 runtime: KilaGraph RenderTypeFactory material ----------------------------------------

    /**
     * This material instance's OWN GPU lifecycle (the {@code CustomShaderMaterial.ShaderState} pattern):
     * the live KilaGraph material — which owns the uniform buffer and the sampler textures — plus the
     * Photon RenderTypes built over it, one per {@code MaterialSetting} state. Holds NO back-reference
     * to the material, so the {@link AutoCloseCleaner} registered on the material fires when it is GC'd
     * (dropped from the resource library / no FX references it) and frees them on the render thread.
     * Without this the RenderTypes would stay in the drain's DrawInfo index forever, pinning the
     * compiled graph and its UBO. The compiled pipelines themselves stay deduped in {@code PhotonPipelines}.
     */
    private static final class GraphState implements AutoCloseable {
        final RenderTypeGraphMaterial material;
        final Map<PhotonPipelines.ParticlePipelineKey, RenderType> renderTypes = new HashMap<>();
        private boolean closed;

        GraphState(RenderTypeGraphMaterial material) {
            this.material = material;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            renderTypes.values().forEach(PhotonRenderTypes::dropCustomShader);
            renderTypes.clear();
            material.close();
        }
    }

    @Nullable
    private transient GraphState state;
    /** The entry {@link #state} was built from — identity marker; null-state + set entry = build failed. */
    @Nullable
    private transient ShaderGraphRuntime.Entry compiledMaterialEntry;
    /** Overrides must be (re)staged into the state's value store before the next draw. */
    private transient boolean overridesStale = true;

    /**
     * KilaGraph generates the GLSL; <b>Photon owns the draw</b> — the 1.21 split, where the graph was
     * compiled to a shader ({@code KGShaderResourceProvider}) that Photon then rendered through its own pass.
     * So the RenderType here is built from Photon's pipeline with THIS emitter's {@link MaterialSetting}
     * state, which is what keeps blend/depth/cull, bloom participation and the GPU-instanced variants
     * working for graph materials. The KilaGraph material is still what owns the uniform values and
     * textures; the drain binds them.
     */
    @Override
    @Nullable
    public RenderType getRenderType(MaterialSetting setting, VertexFormat.Mode mode) {
        var entry = refreshEntry();
        if (entry == null || !entry.isValid() || entry.getCompiled() == null) {
            return null; // missing/broken graph: the material preview surfaces the compile error
        }
        if (compiledMaterialEntry != entry) {
            // this frame's queued jobs may still reference the old state — close it at frame end
            if (state != null) {
                PhotonWorldRenderState.closeAtFrameEnd(state);
                state = null;
            }
            var material = RenderTypeFactory.createMaterial(entry.getCompiled());
            if (material != null) {
                state = new GraphState(material);
                AutoCloseCleaner.registerRenderThread(this, state);
            }
            compiledMaterialEntry = entry;
            overridesStale = true;
        }
        var current = state;
        if (current == null) {
            return null; // generated pipeline failed on the GPU (RenderTypeFactory logged it)
        }
        if (overridesStale) {
            overrides.forEach(this::applyOverride);
            overridesStale = false;
        }
        var key = setting.pipelineKey(mode);
        var existing = current.renderTypes.get(key);
        if (existing != null) {
            return existing;
        }
        var created = PhotonRenderTypes.createGraphShader(entry.getCompiled(), current.material, key,
                entry.getUsedChannelMask(), entry.isUsesCustomData()).orElse(null);
        if (created != null) {
            current.renderTypes.put(key, created);
        }
        return created;
    }

    // ---- overrides -----------------------------------------------------------------------------

    /** Stage an override into the compiled material's live value store (was KGMaterialValues in 1.21). */
    private void applyOverride(String name, Object value) {
        var current = state;
        if (current == null) return;
        var material = current.material;
        switch (value) {
            case Float f -> material.setUniform(name, f);
            case Integer color -> material.setColorUniform(name, color);
            case Boolean b -> material.setUniform(name, b ? 1f : 0f);
            case Vector2f v -> material.setUniform(name, v);
            case Vector3f v -> material.setUniform(name, v);
            case Vector4f v -> material.setUniform(name, v);
            case RenderTypeGraphTypes.GradientValue gradient -> material.setGradient(name, gradient);
            case RenderTypeGraphTypes.CurveValue curve -> material.setCurve(name, curve);
            case RenderTypeGraphTypes.Sampler2DValue sampler ->
                    material.setTexture(name, Identifier.tryParse(sampler.location()));
            default -> { }
        }
    }

    // ---- serialization ---------------------------------------------------------------------------

    @Override
    public Tag serializeAdditionalNBT(HolderLookup.@NotNull Provider provider) {
        var tag = new CompoundTag();
        var overridesTag = new CompoundTag();
        overrides.forEach((name, value) -> {
            var encoded = encodeValue(value);
            if (encoded != null) overridesTag.put(name, encoded);
        });
        tag.put("overrides", overridesTag);
        return tag;
    }

    @Override
    public void deserializeAdditionalNBT(Tag tag, HolderLookup.@NotNull Provider provider) {
        overrides.clear();
        invalidateOverridesCache();
        entry = null; // force value-store rebuild (defaults + overrides) on next use
        if (!(tag instanceof CompoundTag compound)) return;
        var overridesTag = compound.getCompoundOrEmpty("overrides");
        for (var name : overridesTag.keySet()) {
            var value = decodeValue(overridesTag.get(name));
            if (value != null) overrides.put(name, value);
        }
    }

    /** Self-describing value tag (types survive without the graph being loaded). */
    @Nullable
    private static Tag encodeValue(Object value) {
        return switch (value) {
            case Float f -> FloatTag.valueOf(f);
            case Integer i -> IntTag.valueOf(i);
            case Boolean b -> ByteTag.valueOf(b);
            case Vector2f v -> floatList(v.x, v.y);
            case Vector3f v -> floatList(v.x, v.y, v.z);
            case Vector4f v -> floatList(v.x, v.y, v.z, v.w);
            case RenderTypeGraphTypes.Sampler2DValue sampler ->
                    typed("sampler", RenderTypeGraphTypes.SAMPLER2D_CODEC.encodeStart(NbtOps.INSTANCE, sampler)
                            .result().orElse(null));
            case RenderTypeGraphTypes.GradientValue gradient ->
                    typed("gradient", RenderTypeGraphTypes.GRADIENT_CODEC.encodeStart(NbtOps.INSTANCE, gradient)
                            .result().orElse(null));
            case RenderTypeGraphTypes.CurveValue curve ->
                    typed("curve", RenderTypeGraphTypes.CURVE_CODEC.encodeStart(NbtOps.INSTANCE, curve)
                            .result().orElse(null));
            default -> null;
        };
    }

    @Nullable
    private static Object decodeValue(@Nullable Tag tag) {
        return switch (tag) {
            case FloatTag f -> f.floatValue();
            case IntTag i -> i.intValue();
            case ByteTag b -> b.byteValue() != 0;
            case ListTag list -> switch (list.size()) {
                case 2 -> new Vector2f(list.getFloatOr(0, 0.0F), list.getFloatOr(1, 0.0F));
                case 3 -> new Vector3f(list.getFloatOr(0, 0.0F), list.getFloatOr(1, 0.0F), list.getFloatOr(2, 0.0F));
                case 4 -> new Vector4f(list.getFloatOr(0, 0.0F), list.getFloatOr(1, 0.0F), list.getFloatOr(2, 0.0F), list.getFloatOr(3, 0.0F));
                default -> null;
            };
            case CompoundTag compound -> switch (compound.getStringOr("type", "")) {
                case "sampler" -> RenderTypeGraphTypes.SAMPLER2D_CODEC.parse(NbtOps.INSTANCE, compound.get("data"))
                        .result().orElse(null);
                case "gradient" -> RenderTypeGraphTypes.GRADIENT_CODEC.parse(NbtOps.INSTANCE, compound.get("data"))
                        .result().orElse(null);
                case "curve" -> RenderTypeGraphTypes.CURVE_CODEC.parse(NbtOps.INSTANCE, compound.get("data"))
                        .result().orElse(null);
                default -> null;
            };
            case null, default -> null;
        };
    }

    private static ListTag floatList(float... components) {
        var list = new ListTag();
        for (var component : components) list.add(FloatTag.valueOf(component));
        return list;
    }

    @Nullable
    private static Tag typed(String type, @Nullable Tag data) {
        if (data == null) return null;
        var tag = new CompoundTag();
        tag.putString("type", type);
        tag.put("data", data);
        return tag;
    }

    @Override
    public IMaterial copy() {
        var copied = new ShaderGraphMaterial(getGraphPath());
        copied.deserializeAdditionalNBT(serializeAdditionalNBT(Platform.getFrozenRegistry()),
                Platform.getFrozenRegistry());
        return copied;
    }

    /**
     * Serialized-overrides cache backing {@link #equals}/{@link #hashCode} — the render-pass batching
     * TreeMap runs them every frame, and serializing per comparison would allocate NBT trees each
     * time. Invalidated ({@link #invalidateOverridesCache()}) on every {@code overrides} mutation.
     */
    @Nullable
    private CompoundTag cachedOverridesTag;
    private int cachedOverridesHash;

    private CompoundTag overridesTag() {
        if (cachedOverridesTag == null) {
            cachedOverridesTag = (CompoundTag) serializeAdditionalNBT(Platform.getFrozenRegistry());
            cachedOverridesHash = cachedOverridesTag.hashCode();
        }
        return cachedOverridesTag;
    }

    private void invalidateOverridesCache() {
        cachedOverridesTag = null;
        overridesStale = true; // re-stage values into the compiled material before the next draw
    }

    /**
     * Value equality by (graph path, serialized overrides) so two separately-deserialized materials
     * referencing the same graph with the same values merge into ONE render pass (cross-FX batching).
     * The override comparison uses cached serialized tags: override values (GradientValue, vectors,
     * ...) don't implement equals reliably, while CompoundTag.equals is deep.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShaderGraphMaterial that)) return false;
        if (!getGraphPath().equals(that.getGraphPath())) return false;
        return overridesTag().equals(that.overridesTag());
    }

    /**
     * MUST include the overrides: the render-pass TreeMap comparator tie-breaks unequal passes by
     * {@code Integer.compare(hashCode, hashCode)} — a graph-path-only hash made two same-graph
     * passes with different overrides compare as 0 and merge, rendering one with the other's values.
     */
    @Override
    public int hashCode() {
        overridesTag(); // ensure cachedOverridesHash is computed
        return getGraphPath().hashCode() * 31 + cachedOverridesHash;
    }

    // ---- inspector -------------------------------------------------------------------------------

    @Override
    public IGuiTexture preview() {
        return DynamicTexture.of(() -> isCompiledError() ?
                new TextTexture(getCompiledErrorMessage().isEmpty() ? "error" : getCompiledErrorMessage(), 0xffff0000) :
                MaterialPreviewRenderer.previewOf(this));
    }

    @Override
    public IGuiTexture previewLive() {
        return DynamicTexture.of(() -> isCompiledError() ?
                new TextTexture(getCompiledErrorMessage().isEmpty() ? "error" : getCompiledErrorMessage(), 0xffff0000) :
                MaterialPreviewRenderer.livePreviewOf(this));
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        createPreview(father);

        var variablesGroup = new ConfiguratorGroup("photon.shader_graph.variables");
        variablesGroup.setCollapse(false);
        variablesGroup.setCanCollapse(false);
        variablesGroup.setTips("photon.shader_graph.variables.tip");

        var graphRow = new Configurator("photon.shader_graph.graph");
        graphRow.setTips("photon.shader_graph.graph.tip");
        var selectButton = new Button();
        selectButton.setText(getGraphPath().getResourceName().isEmpty() ? "photon.shader_graph.select"
                : getGraphPath().getResourceName());
        selectButton.setOnClick(event -> showGraphSelector(event.currentElement.getModularUI(),
                event.x, event.y, () -> {
                    var name = getGraphPath().getResourceName();
                    selectButton.setText(name.isEmpty() ? "photon.shader_graph.select" : name, false);
                    reloadVariableConfigurators(variablesGroup);
                    graphRow.notifyChanges();
                }));
        selectButton.layout(layout -> layout.alignSelf(AlignItems.CENTER));
        graphRow.addInlineChild(selectButton);

        var reloadRow = new Configurator().addInlineChild(new Button()
                .setOnClick(event -> {
                    ShaderGraphRuntime.invalidate(getGraphPath());
                    entry = null;
                    reloadVariableConfigurators(variablesGroup);
                }).setText("photon.reload_shader").layout(layout -> layout.alignSelf(AlignItems.CENTER)));

        reloadVariableConfigurators(variablesGroup);

        father.addConfigurators(graphRow, reloadRow, variablesGroup);
    }

    /**
     * The full resource-browser selector (the same UI {@code IMaterialConfigurator#showMaterialDialog}
     * uses). Selection is live (clicking a graph applies it, so its exposed variables show immediately);
     * cancel restores the previous graph. The path arrives via {@link ShaderGraphResource}'s selection
     * listener — the stock dialog only reports values.
     */
    private void showGraphSelector(@Nullable ModularUI mui,
                                   float x, float y, Runnable onChanged) {
        if (mui == null) return;
        var previous = getGraphPath();
        ShaderGraphResource.INSTANCE.setPathSelectListener(path -> {
            setGraphPath(path);
            onChanged.run();
        });
        var dialog = ShaderGraphResource.INSTANCE.getResourceInstance().createSelectorDialog(x, y,
                tag -> { }, () -> {
                    setGraphPath(previous);
                    onChanged.run();
                });
        dialog.setOnClose(() -> ShaderGraphResource.INSTANCE.setPathSelectListener(null));
        dialog.show(mui);
    }

    /** Rebuild one editor row per exposed variable, values overlaid over the graph defaults. */
    private void reloadVariableConfigurators(ConfiguratorGroup group) {
        group.removeAllConfigurators();
        var entry = refreshEntry();
        if (entry == null || entry.getGraph() == null || entry.getCompiled() == null) {
            // Missing resource / compile failure: show the reason instead of a silently empty group.
            var message = entry == null || entry.getErrorMessage().isEmpty() ? "missing shader graph"
                    : entry.getErrorMessage();
            group.addConfigurators(new Configurator().addInlineChild(
                    new UIElement()
                            .layout(layout -> layout.height(14))
                            .style(style -> style.backgroundTexture(new TextTexture(message, 0xffff5555)))));
            return;
        }
        var compiled = entry.getCompiled();
        for (var declaration : entry.getGraph().graphModel.getGraphVariableModels()) {
            if (declaration == null) continue;
            var name = declaration.getName();
            // Only variables that actually became a uniform/sampler in the compiled shader.
            if (!compiled.uniformFields().containsKey(name) && !compiled.variableSamplers().containsKey(name)) {
                continue;
            }
            var type = declaration.getDataTypeHandle();
            Object defaultValue = declaration.tryGetDefaultValue(declaration.getDataType()).result().orElse(null);
            var row = new VariableRow(name, type, defaultValue);
            var sub = new ConfiguratorGroup(name);
            sub.setCollapse(false);
            row.buildConfigurator(sub);
            attachOverrideReset(sub, group, name);
            group.addConfigurators(sub);
        }
    }

    /** Property#createConfigurator-style reset on a variable group header: a small REPLAY square,
     *  shown while the variable is overridden (orange title); clicking drops the override and
     *  rebuilds the live value store from the graph defaults. */
    private void attachOverrideReset(ConfiguratorGroup sub, ConfiguratorGroup variablesGroup, String name) {
        var reset = new Button().noText().setOnClick(event -> {
            if (overrides.remove(name) == null) return;
            invalidateOverridesCache();
            entry = null; // force a value-store rebuild (defaults + remaining overrides) on next use
            reloadVariableConfigurators(variablesGroup);
        });
        reset.layout(layout -> {
            layout.height(14);
            layout.width(14);
        }).addChild(new UIElement()
                .layout(layout -> {
                    layout.height(10);
                    layout.width(10);
                })
                .style(style -> style.backgroundTexture(Icons.REPLAY)
                        .tooltips("photon.shader_graph.variable_reset")));
        sub.lineContainer.addChildAt(reset, sub.tip.getSiblingIndex());
        var mark = new AtomicBoolean(false);
        Runnable sync = () -> {
            boolean overridden = overrides.containsKey(name);
            if (overridden == mark.get()) return;
            mark.set(overridden);
            reset.setDisplay(overridden);
            sub.label.setText(sub.label.getText().copy().withStyle(style -> style.withColor(
                    overridden ? ColorPattern.ORANGE.color : -1)));
        };
        mark.set(!overrides.containsKey(name)); // force the initial apply
        sync.run();
        sub.addEventListener(UIEvents.TICK, event -> sync.run());
    }

    /** Deep-copy mutable values so editors never alias the graph's default instances. */
    @Nullable
    private static Object copyValue(@Nullable Object value) {
        return switch (value) {
            case RenderTypeGraphTypes.GradientValue gradient -> gradient.copy();
            case RenderTypeGraphTypes.CurveValue curve -> curve.copy();
            case Vector2f v -> new Vector2f(v);
            case Vector3f v -> new Vector3f(v);
            case Vector4f v -> new Vector4f(v);
            case null, default -> value;
        };
    }

    /**
     * The type-driven editor adapter for one exposed variable: reads the override (else the graph
     * default), writes overrides + the live value store. The editor UI itself comes from the variable's
     * {@link TypeHandle} (the same resolution the graph Blackboard uses — so CURVE/GRADIENT/SAMPLER2D get
     * their full custom editors, vectors/colors the built-in ones).
     */
    private class VariableRow implements IFieldValueConfigurable {
        private final String name;
        private final TypeHandle type;
        @Nullable
        private final Object defaultValue;

        private VariableRow(String name, TypeHandle type, @Nullable Object defaultValue) {
            this.name = name;
            this.type = type;
            this.defaultValue = defaultValue;
        }

        @Override
        public void setValue(Object value) {
            if (value == null) return;
            overrides.put(name, value);
            invalidateOverridesCache();
            applyOverride(name, value);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T getValue() {
            var override = overrides.get(name);
            // defensive: a stale override of the wrong type (variable type changed) would crash this
            // row's typed configurator — fall back to the graph default (refreshEntry also prunes these)
            if (override != null && defaultValue != null && !defaultValue.getClass().isInstance(override)) {
                override = null;
            }
            return (T) (override != null ? override : copyValue(defaultValue));
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T getDefaultValue() {
            return (T) copyValue(defaultValue);
        }

        @Override
        public Tooltips getTooltips() {
            return Tooltips.of(new String[0]);
        }

        @Override
        public void buildConfigurator(ConfiguratorGroup father) {
            var resolved = type.resolveConfigurable();
            if (resolved == null) return;
            var configurable = resolved.createConfigurable(this, type);
            if (configurable == null) return;
            var staging = new ConfiguratorGroup();
            configurable.buildConfigurator(staging);
            for (var configurator : staging.getConfigurators()) {
                father.addConfigurator(configurator);
            }
        }
    }
}
