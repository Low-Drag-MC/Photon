package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinPath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.ui.data.Tooltips;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.IFieldValueConfigurable;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandle;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.shadergraph.runtime.ShaderGraphRuntime;
import com.lowdragmc.photon.gui.editor.resource.ShaderGraphResource;
import dev.vfyjxf.taffy.style.AlignItems;
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
import java.util.Optional;

/**
 * A material driven by a {@link com.lowdragmc.photon.client.shadergraph.ShaderGraph} resource. The
 * material stores only an {@link IResourcePath} reference plus its own <b>overrides</b> for the graph's
 * exposed variables — the compiled shader (and its instancing variants) is shared across every material
 * referencing the same graph via {@link ShaderGraphRuntime}, while each material stages its own uniform
 * values right before its draw, so materials never share value state. Untouched variables keep tracking
 * the graph's defaults (edits to the graph propagate on recompile).
 *
 * <p>Works on every Photon render path: {@code begin} picks the shader variant matching the context's
 * define ({@code ""} CPU quads/trails/beams, {@code PARTICLE_INSTANCE}, {@code PARTICLE_MODEL_INSTANCE}).
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

    // TODO(M2): 1.21 getShader() staged the shared compiled ShaderInstance variant with
    // KGBuiltinUniforms + per-material KGMaterialValues + Photon pipeline dynamic uniforms
    // (viewport, camera-relative kg_CameraBlockPos/Offset, timeline-driven kg_Time, scene
    // color/depth samplers). KilaGraph 26.1 replaced that runtime with RenderTypeFactory /
    // MaterialUniformBuffer / engine UBO blocks — rebuild on that model.

    // ---- overrides -----------------------------------------------------------------------------

    /** TODO(M2): re-apply the override into the compiled material's live value store (was
     *  KGMaterialValues). Until then overrides are only recorded + persisted. */
    private void applyOverride(String name, Object value) {
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
        copied.deserializeAdditionalNBT(serializeAdditionalNBT(com.lowdragmc.lowdraglib2.Platform.getFrozenRegistry()),
                com.lowdragmc.lowdraglib2.Platform.getFrozenRegistry());
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
            cachedOverridesTag = (CompoundTag) serializeAdditionalNBT(com.lowdragmc.lowdraglib2.Platform.getFrozenRegistry());
            cachedOverridesHash = cachedOverridesTag.hashCode();
        }
        return cachedOverridesTag;
    }

    private void invalidateOverridesCache() {
        cachedOverridesTag = null;
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
                IGuiTexture.MISSING_TEXTURE); // TODO(M2): live shader preview returns with the pipeline path
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
    private void showGraphSelector(@Nullable com.lowdragmc.lowdraglib2.gui.ui.ModularUI mui,
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
                    new com.lowdragmc.lowdraglib2.gui.ui.UIElement()
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
        var reset = new com.lowdragmc.lowdraglib2.gui.ui.elements.Button().noText().setOnClick(event -> {
            if (overrides.remove(name) == null) return;
            invalidateOverridesCache();
            entry = null; // force a value-store rebuild (defaults + remaining overrides) on next use
            reloadVariableConfigurators(variablesGroup);
        });
        reset.layout(layout -> {
            layout.height(14);
            layout.width(14);
        }).addChild(new com.lowdragmc.lowdraglib2.gui.ui.UIElement()
                .layout(layout -> {
                    layout.height(10);
                    layout.width(10);
                })
                .style(style -> style.backgroundTexture(com.lowdragmc.lowdraglib2.gui.texture.Icons.REPLAY)
                        .tooltips("photon.shader_graph.variable_reset")));
        sub.lineContainer.addChildAt(reset, sub.tip.getSiblingIndex());
        var mark = new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable sync = () -> {
            boolean overridden = overrides.containsKey(name);
            if (overridden == mark.get()) return;
            mark.set(overridden);
            reset.setDisplay(overridden);
            sub.label.setText(sub.label.getText().copy().withStyle(style -> style.withColor(
                    overridden ? com.lowdragmc.lowdraglib2.gui.ColorPattern.ORANGE.color : -1)));
        };
        mark.set(!overrides.containsKey(name)); // force the initial apply
        sync.run();
        sub.addEventListener(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.TICK, event -> sync.run());
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
