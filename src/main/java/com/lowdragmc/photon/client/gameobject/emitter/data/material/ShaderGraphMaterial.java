package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.runtime.KGBuiltinUniforms;
import com.lowdragmc.kilagraph.rendertype.runtime.KGMaterialValues;
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
import com.lowdragmc.photon.client.PhotonShaders;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.lowdragmc.photon.client.shadergraph.runtime.ShaderGraphRuntime;
import com.lowdragmc.photon.gui.editor.resource.ShaderGraphResource;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.vfyjxf.taffy.style.AlignItems;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.ParametersAreNonnullByDefault;
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
@OnlyIn(Dist.CLIENT)
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
    @Nullable
    private KGMaterialValues values;

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
        this.values = null;
    }

    public boolean isCompiledError() {
        return entry != null && !entry.isValid();
    }

    public String getCompiledErrorMessage() {
        return entry == null ? "" : entry.getErrorMessage();
    }

    /** Resolve the shared compiled entry, rebuilding this material's value store when it changed. */
    @Nullable
    private ShaderGraphRuntime.Entry refreshEntry() {
        var current = ShaderGraphRuntime.get(getGraphPath());
        if (current != entry) {
            entry = current;
            var compiled = current == null ? null : current.getCompiled();
            values = compiled == null ? null : new KGMaterialValues(compiled);
            if (values != null) {
                overrides.forEach(this::applyOverride);
            }
        }
        return current;
    }

    @Override
    public ShaderInstance getShader(MaterialContext context) {
        var entry = refreshEntry();
        if (entry == null || !entry.isValid()) {
            return PhotonShaders.getHDRParticleShader();
        }
        var shader = entry.variant(context.getShaderDefine());
        var compiled = entry.getCompiled();
        if (shader == null || compiled == null) {
            return PhotonShaders.getHDRParticleShader();
        }
        // Stage this material's uniforms/samplers on the shared shader — uploaded by the draw's apply().
        KGBuiltinUniforms.bind(shader, compiled.builtinUniforms());
        bindDynamicUniforms(shader, compiled);
        if (values != null) {
            values.apply(shader);
        }
        return shader;
    }

    /** The engine-driven uniforms/samplers (Photon pipeline state), mirroring CustomShaderMaterial. */
    private void bindDynamicUniforms(ShaderInstance shader, CompiledShaderGraph compiled) {
        var viewport = shader.getUniform(PhotonShaderCompiler.VIEWPORT);
        if (viewport != null) {
            viewport.set((float) GlStateManager.Viewport.x(), (float) GlStateManager.Viewport.y(),
                    (float) GlStateManager.Viewport.width(), (float) GlStateManager.Viewport.height());
        }
        // KGBuiltinUniforms binds kg_CameraPos to the GAME's main camera — wrong in the editor SceneView
        // (orbit camera). Override with the pipeline's actual render camera, the one particles are rendered
        // camera-relative to, so CameraNode / WorldToScreenUV "absolute" are correct in the editor scene and
        // in-world alike (mirrors CustomShaderMaterial's U_CameraPosition). Runs after KGBuiltinUniforms.bind
        // in getShader(), so it wins.
        var cameraPos = shader.getUniform("kg_CameraPos");
        if (cameraPos != null) {
            var camera = Optional.ofNullable(RenderPassPipeline.getCurrent())
                    .map(RenderPassPipeline::getCamera).orElse(null);
            if (camera != null) {
                var p = camera.getPosition();
                cameraPos.set((float) p.x, (float) p.y, (float) p.z);
            }
        }
        // Same story: KGBuiltinUniforms binds kg_Time (the Time node) from the WORLD clock
        // (mc.level.getGameTime()), which ignores the emitter timeline (play/pause/scrub) and disagrees
        // with the GameTime node — vanilla GameTime IS driven by Photon from the particle time. Override
        // kg_Time to that same particle time: RenderSystem's normalized shader game time (what Photon sets
        // each render) scaled to KG's seconds (24000 ticks = 1200 s), so Time and GameTime nodes agree and
        // freeze/scrub with the emitter.
        var engineTime = shader.getUniform("kg_Time");
        if (engineTime != null) {
            engineTime.set(RenderSystem.getShaderGameTime() * 1200f);
        }
        if (compiled.usesSceneColor() || compiled.usesSceneDepth()) {
            var sampler = Optional.ofNullable(RenderPassPipeline.getCurrent())
                    .map(RenderPassPipeline::getSceneSampler);
            shader.setSampler(PhotonShaderCompiler.SCENE_COLOR,
                    sampler.map(RenderTarget::getColorTextureId).orElse(-1));
            shader.setSampler(PhotonShaderCompiler.SCENE_DEPTH,
                    sampler.map(RenderTarget::getDepthTextureId).orElse(-1));
        }
    }

    // ---- overrides -----------------------------------------------------------------------------

    /** Write one override into the live value store, typed by the compiled uniform field. */
    private void applyOverride(String name, Object value) {
        if (values == null || entry == null || entry.getCompiled() == null) return;
        var compiled = entry.getCompiled();
        switch (value) {
            case RenderTypeGraphTypes.Sampler2DValue sampler -> {
                if (com.lowdragmc.lowdraglib2.LDLib2.isValidResourceLocation(sampler.location())) {
                    values.setTexture(name, ResourceLocation.parse(sampler.location()));
                }
            }
            case RenderTypeGraphTypes.GradientValue gradient -> values.setGradient(name, gradient);
            case RenderTypeGraphTypes.CurveValue curve -> values.setCurve(name, curve);
            case Vector2f v -> values.setUniform(name, v);
            case Vector3f v -> values.setUniform(name, v);
            case Vector4f v -> values.setUniform(name, v);
            case Float f -> values.setByVariable(name, f);
            case Boolean b -> values.setByVariable(name, b ? 1f : 0f);
            case Integer i -> {
                // An Integer is either an INT variable or a COLOR (ARGB) one — disambiguate by field type.
                var field = compiled.uniformFields().get(name);
                if (field != null && field.type() == GlslType.VEC4) {
                    values.setColorUniform(name, i);
                } else {
                    values.setByVariable(name, i);
                }
            }
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
        entry = null; // force value-store rebuild (defaults + overrides) on next use
        values = null;
        if (!(tag instanceof CompoundTag compound)) return;
        var overridesTag = compound.getCompound("overrides");
        for (var name : overridesTag.getAllKeys()) {
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
            case FloatTag f -> f.getAsFloat();
            case IntTag i -> i.getAsInt();
            case ByteTag b -> b.getAsByte() != 0;
            case ListTag list -> switch (list.size()) {
                case 2 -> new Vector2f(list.getFloat(0), list.getFloat(1));
                case 3 -> new Vector3f(list.getFloat(0), list.getFloat(1), list.getFloat(2));
                case 4 -> new Vector4f(list.getFloat(0), list.getFloat(1), list.getFloat(2), list.getFloat(3));
                default -> null;
            };
            case CompoundTag compound -> switch (compound.getString("type")) {
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

    // ---- inspector -------------------------------------------------------------------------------

    @Override
    public IGuiTexture preview() {
        return DynamicTexture.of(() -> isCompiledError() ?
                new TextTexture(getCompiledErrorMessage().isEmpty() ? "error" : getCompiledErrorMessage(), 0xffff0000) :
                preview);
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        createPreview(father);

        var variablesGroup = new ConfiguratorGroup("photon.shader_graph.variables");
        variablesGroup.setCollapse(false);
        variablesGroup.setCanCollapse(false);

        var graphRow = new Configurator("photon.shader_graph.graph");
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
                    values = null;
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
            group.addConfigurators(sub);
        }
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
            applyOverride(name, value);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T getValue() {
            var override = overrides.get(name);
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
