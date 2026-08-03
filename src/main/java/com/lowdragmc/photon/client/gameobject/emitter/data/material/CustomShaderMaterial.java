package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.configurator.ConfiguratorParser;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.NumberConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.StringConfigurator;
import com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.AutoCloseCleaner;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.render.MaterialPreviewRenderer;
import com.lowdragmc.photon.client.render.PhotonCustomUniforms;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PhotonRenderTypes;
import com.lowdragmc.photon.client.render.PhotonWorldRenderState;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.serialization.MapCodec;
import dev.vfyjxf.taffy.style.AlignItems;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * User custom-shader material — the 1.21 {@code LDShaderHolder} contract rebuilt on 26.1:
 * <ul>
 *   <li><b>Custom vsh + fsh</b>: the shader JSON's own {@code vertex}/{@code fragment} programs
 *       (a custom vertex stage is fully supported, e.g. scan/shield/tornado_body).</li>
 *   <li><b>Dynamic uniforms</b>: the shader's plain 1.21 uniforms live in the fixed
 *       {@code PhotonCustomMaterial} std140 block; this material owns a live
 *       {@link PhotonCustomUniforms} buffer — layout from the shader JSON's {@code uniforms} list
 *       (1.21 parity; the same block is emitted into both stages), values from JSON defaults + the
 *       saved {@code shaderData} blob + editor edits. Changing a value re-uploads bytes, never
 *       recompiles.</li>
 *   <li><b>Dynamic samplers</b>: JSON-declared names bound per material (see {@link #liveSamplers()});
 *       {@code SamplerScene*} bind the live scene capture.</li>
 *   <li><b>Defines</b>: compile-time VARIANTS only (the engine's *_INSTANCE selection over the
 *       vertex stage; user defines flow through {@code CustomShaderKey.defines}).</li>
 * </ul>
 * <b>Lifecycle</b>: this material OWNS its GPU state — the uniform buffer and its RenderTypes (one
 * per draw variant), held in {@link ShaderState}, freed by a render-thread {@code Cleaner} when the
 * material is released (GC'd from the resource library / no FX references it). Nothing is cached
 * globally; the shared compiled pipeline is deduped in {@code PhotonPipelines}. Switching a value or
 * a sampler texture never recompiles or spawns a RenderType.
 */
@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "custom_shader", registry = "photon:material")
public class CustomShaderMaterial extends ShaderInstanceMaterial {
    public final static int MAX_SAMPLER = 128;
    public final static int MAX_SAMPLING = 128;

    @Getter
    @Persisted
    private Identifier shaderLocation = Photon.id("circle");
    @Configurable(name = "SamplerCurve", subConfigurable = true)
    public final CurveTexture curveTexture = new CurveTexture(MAX_SAMPLING, MAX_SAMPLER);
    @Configurable(name = "SamplerGradient", subConfigurable = true)
    public final GradientTexture gradientTexture = new GradientTexture(MAX_SAMPLING, MAX_SAMPLER);
    /** 1.21 saved the LDShaderHolder's uniform values under "shaderData"; carried as an opaque blob
     *  so M0/M1 round-trips don't lose user data before M2 reattaches it. */
    private CompoundTag pendingShaderData = new CompoundTag();
    @Getter
    private String compiledErrorMessage = "";

    public CustomShaderMaterial() {
    }

    public CustomShaderMaterial(Identifier shaderLocation) {
        this.shaderLocation = shaderLocation;
    }

    public void setShader(Identifier shaderLocation) {
        this.shaderLocation = shaderLocation;
        recompile();
    }

    @Override
    public IMaterial copy() {
        var copied = new CustomShaderMaterial(shaderLocation);
        var data = serializeAdditionalNBT(Platform.getFrozenRegistry());
        copied.deserializeAdditionalNBT(data, Platform.getFrozenRegistry());
        return copied;
    }

    // 1.21 wrote the sampler textures as bare lists (INBTSerializable<ListTag>); 26.1's ValueIO
    // layout is {curves|gradients: [...]} — rewrite legacy keys in place before the field pass
    @Override
    public void deserialize(net.minecraft.world.level.storage.@NotNull ValueInput input) {
        var raw = input.read(MapCodec.assumeMapUnsafe(CompoundTag.CODEC)).orElse(null);
        if (raw != null && (raw.get("curveTexture") instanceof ListTag
                || raw.get("gradientTexture") instanceof ListTag)) {
            wrapLegacyList(raw, "curveTexture", "curves");
            wrapLegacyList(raw, "gradientTexture", "gradients");
            PersistedParser.deserializeNBT(raw, this, Platform.getFrozenRegistry());
            return;
        }
        PersistedParser.deserialize(this, input);
    }

    private static void wrapLegacyList(CompoundTag tag, String key, String innerKey) {
        if (tag.get(key) instanceof ListTag list) {
            var wrapped = new CompoundTag();
            wrapped.put(innerKey, list);
            tag.put(key, wrapped);
        }
    }

    @Override
    public Tag serializeAdditionalNBT(HolderLookup.@NotNull Provider provider) {
        if (pendingShaderData.isEmpty()) {
            return EndTag.INSTANCE;
        }
        var shaderData = new CompoundTag();
        shaderData.put("shaderData", pendingShaderData.copy());
        return shaderData;
    }

    @Override
    public void deserializeAdditionalNBT(Tag tag, HolderLookup.@NotNull Provider provider) {
        if (!(tag instanceof CompoundTag shaderData)) return;
        pendingShaderData = shaderData.getCompoundOrEmpty("shaderData").copy();
        valuesDirty = true; // values changed — re-upload only, layout is unchanged
    }

    public boolean isCompiledError() {
        return !compiledErrorMessage.isEmpty();
    }

    public void recompile() {
        // let the (old) shader re-attempt compilation after a source/JSON fix
        PhotonRenderTypes.clearFailedCustomShader(fragmentShaderId());
        compiledErrorMessage = "";
        // JSON metadata (vertex/fragment/samplers/layout) may have changed — re-parse on next use
        cachedMeta = null;
        liveSamplers = null;
        // the whole GPU state (buffer + this material's RenderTypes) may be stale — this frame's
        // queued jobs may still reference it, so close it at frame end; state() rebuilds on next use.
        // (The Cleaner registered for this state also fires on material GC; close() is idempotent.)
        if (state != null) {
            PhotonWorldRenderState.closeAtFrameEnd(state);
            state = null;
        }
    }

    // ---- 26.1 runtime: dynamic uniforms in the PhotonCustomMaterial UBO ------------------------------

    /** Uniform names owned by the engine in the 1.21 shader JSONs — never material values. */
    private static final Set<String> BUILTIN_UNIFORMS = Set.of(
            "ModelViewMat", "ProjMat", "IViewRotMat", "ColorModulator", "FogStart", "FogEnd",
            "FogColor", "FogShape", "GameTime", "ScreenSize", "LineWidth");

    /**
     * This material instance's OWN GPU lifecycle — the 1.21 {@code LDShaderHolder} equivalent: the
     * live {@code PhotonCustomMaterial} uniform buffer plus this material's RenderTypes (one per draw
     * variant, i.e. per blend/mode/state — the 1.21 "shaderHolder holds the define variants" pattern).
     * The material OWNS these; nothing is cached globally. Holds NO back-reference to the material, so
     * the {@link com.lowdragmc.photon.client.AutoCloseCleaner} registered on the material fires on GC
     * (when it's dropped from the resource library / no FX reference it) and frees them ON THE RENDER
     * THREAD. The expensive part (the compiled pipeline) is still deduped in {@code PhotonPipelines},
     * so per-instance ownership never duplicates a GPU compile.
     */
    private static final class ShaderState implements AutoCloseable {
        final PhotonCustomUniforms uniforms;
        final Map<PhotonPipelines.CustomShaderKey, RenderType> renderTypes =
                new ConcurrentHashMap<>();
        private boolean closed;

        ShaderState(List<PhotonCustomUniforms.Field> layout) {
            this.uniforms = new PhotonCustomUniforms(layout);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            renderTypes.values().forEach(PhotonRenderTypes::dropCustomShader);
            renderTypes.clear();
            uniforms.close();
        }
    }

    @Nullable
    private transient ShaderState state;
    private transient boolean valuesDirty = true;
    /** The resource-reload generation this material last built against; a mismatch (F3+T / resource
     *  pack change) drops the stale JSON metadata + GPU state so the layout is re-read. */
    private transient int builtGeneration = -1;

    // ---- shader JSON metadata (parsed once, invalidated on recompile) ---------------------------

    /** The 1.21 shader-JSON declarations that drive COMPILATION: the vertex + fragment programs
     *  (custom vsh restored — {@code scan}/{@code shield}/{@code tornado_body}/… route their own
     *  vertex stage here), the custom sampler + scene-sampler NAMES, and the PhotonCustomMaterial
     *  block layout. Layout source of truth = the JSON {@code uniforms} list (1.21 parity); the
     *  converter emits the same set into BOTH stages' GLSL block, so vsh/fsh/Java always agree. */
    private record ShaderMeta(Identifier vertex, Identifier fragment,
                              List<String> samplerNames,
                              List<String> sceneSamplers,
                              List<PhotonCustomUniforms.Field> layout) {
    }

    @Nullable
    private transient ShaderMeta cachedMeta;

    private ShaderMeta meta() {
        if (cachedMeta == null) {
            cachedMeta = parseMeta();
        }
        return cachedMeta;
    }

    private Identifier fragmentShaderId() {
        return meta().fragment();
    }

    /** {@code "ns:path"} → the asset shader id {@code "ns:core/path"}. */
    private static Identifier mapProgram(String ref) {
        var id = Identifier.parse(ref);
        return Identifier.fromNamespaceAndPath(id.getNamespace(), "core/" + id.getPath());
    }

    private ShaderMeta parseMeta() {
        // defaults: shared vertex stage, fragment derived from the material's shaderLocation
        var vertex = new Identifier[]{Photon.id("core/particle")};
        var fragment = new Identifier[]{Identifier.fromNamespaceAndPath(
                shaderLocation.getNamespace(), "core/" + shaderLocation.getPath())};
        var samplerNames = new TreeSet<String>();
        var sceneSamplers = new TreeSet<String>();
        var layout = new TreeMap<String, PhotonCustomUniforms.Field>();
        readShaderJson(json -> {
            if (json.has("vertex")) vertex[0] = mapProgram(json.get("vertex").getAsString());
            if (json.has("fragment")) fragment[0] = mapProgram(json.get("fragment").getAsString());
            if (json.has("samplers")) {
                for (var element : json.getAsJsonArray("samplers")) {
                    var name = element.getAsJsonObject().get("name").getAsString();
                    if (isSceneSampler(name)) sceneSamplers.add(name);
                    else if (!BUILTIN_SAMPLERS.contains(name)) samplerNames.add(name);
                }
            }
            if (json.has("uniforms")) {
                for (var element : json.getAsJsonArray("uniforms")) {
                    var uniform = element.getAsJsonObject();
                    var name = uniform.get("name").getAsString();
                    if (BUILTIN_UNIFORMS.contains(name) || name.startsWith("U_")) continue;
                    var type = jsonUniformType(
                            uniform.has("type") ? uniform.get("type").getAsString() : "float",
                            uniform.has("count") ? uniform.get("count").getAsInt() : 1);
                    if (type != null) {
                        layout.put(name, new PhotonCustomUniforms.Field(name, type));
                    } else {
                        compiledErrorMessage = "unsupported uniform type for " + name;
                    }
                }
            }
        });
        return new ShaderMeta(vertex[0], fragment[0],
                List.copyOf(samplerNames), List.copyOf(sceneSamplers),
                List.copyOf(layout.values()));
    }

    /** 1.21 shader-JSON uniform type/count → the std140 block member type (the block skips
     *  builtins/{@code U_*}; matrices are {@code matrix4x4}, vectors are float+count). */
    @Nullable
    private static PhotonCustomUniforms.Type jsonUniformType(String type, int count) {
        if ("matrix4x4".equals(type)) return PhotonCustomUniforms.Type.MAT4;
        if ("int".equals(type)) return PhotonCustomUniforms.Type.INT;
        return switch (count) {
            case 1 -> PhotonCustomUniforms.Type.FLOAT;
            case 2 -> PhotonCustomUniforms.Type.VEC2;
            case 3 -> PhotonCustomUniforms.Type.VEC3;
            case 4 -> PhotonCustomUniforms.Type.VEC4;
            case 16 -> PhotonCustomUniforms.Type.MAT4;
            default -> null;
        };
    }

    /**
     * This material's live uniforms buffer. Layout = the JSON {@code uniforms} list (1.21 parity);
     * values applied afterwards. Editing a value re-uploads bytes — never recompiles.
     */
    // state()/uniforms are field-managed and freed by the Cleaner (never try-with-resources)
    @SuppressWarnings("resource")
    public PhotonCustomUniforms customUniforms() {
        return state().uniforms;
    }

    /** This material's GPU state (lazy). Registers the render-thread Cleaner on first build, so the
     *  buffer + its RenderTypes are freed when the material is GC'd (dropped from the library / no FX
     *  references it). {@code owner=this} material is long-lived vs the state, so the Cleaner never
     *  fires early; {@code resource=state} holds no back-reference to the material. */
    private ShaderState state() {
        if (state == null) {
            state = new ShaderState(meta().layout());
            AutoCloseCleaner.registerRenderThread(this, state);
            valuesDirty = true;
        }
        if (valuesDirty) {
            applyValues(state.uniforms);
            valuesDirty = false;
        }
        return state;
    }

    // ---- live sampler bindings (per material; drain reads them live, no RenderType churn) --------

    @Nullable
    private transient Map<String, Identifier> liveSamplers;

    /** The material's OWN mutable sampler-binding map — {@code Sampler0} (the unused base, missing)
     *  plus every custom sampler → its bound texture (saved blob, else missing). Referenced live by
     *  {@link PhotonRenderTypes.PhotonDrawInfo}: swapping a texture
     *  mutates it in place, so no new RenderType/pipeline is created. Scene samplers are NOT here —
     *  the drain binds those from the scene capture. */
    private Map<String, Identifier> liveSamplers() {
        if (liveSamplers == null) {
            liveSamplers = new ConcurrentHashMap<>();
        }
        var missing = MissingTextureAtlasSprite.getLocation();
        liveSamplers.put("Sampler0", missing);
        var saved = pendingShaderData.getCompoundOrEmpty("samplers");
        for (var name : meta().samplerNames()) {
            var texture = missing;
            var samplerTag = saved.getCompoundOrEmpty(name);
            if ("texture".equals(samplerTag.getStringOr("type", ""))) {
                var parsed = Identifier.tryParse(samplerTag.getStringOr("resource", ""));
                if (parsed != null) texture = parsed;
            }
            liveSamplers.put(name, texture);
        }
        // the two engine-provided samplers: live textures this material owns, not saved resources
        bindOwnSampler("SamplerCurve", curveTexture.textureId());
        bindOwnSampler("SamplerGradient", gradientTexture.textureId());
        return liveSamplers;
    }

    /** Override a JSON-declared sampler with one of this material's own live textures (curve/gradient
     *  samplers were {@code addDynamicSampler} calls in 1.21). No-op when the shader doesn't declare it. */
    private void bindOwnSampler(String name, @Nullable Identifier id) {
        if (id != null && liveSamplers != null && liveSamplers.containsKey(name)) {
            liveSamplers.put(name, id);
        }
    }

    /** JSON defaults overlaid with the saved per-material values (1.21 shaderData blob). */
    private void applyValues(PhotonCustomUniforms target) {
        readShaderJson(json -> {
            if (!json.has("uniforms")) return;
            for (var element : json.getAsJsonArray("uniforms")) {
                var uniform = element.getAsJsonObject();
                var name = uniform.get("name").getAsString();
                if (BUILTIN_UNIFORMS.contains(name) || name.startsWith("U_") || !uniform.has("values")) continue;
                var values = uniform.getAsJsonArray("values");
                var components = new float[values.size()];
                for (int i = 0; i < components.length; i++) {
                    components[i] = values.get(i).getAsFloat();
                }
                target.set(name, components);
            }
        });
        var uniforms = pendingShaderData.getCompoundOrEmpty("uniforms");
        for (var name : uniforms.keySet()) {
            if (BUILTIN_UNIFORMS.contains(name) || name.startsWith("U_")) continue;
            switch (uniforms.get(name)) {
                case ListTag list when !list.isEmpty() -> {
                    var components = new float[list.size()];
                    for (int i = 0; i < components.length; i++) {
                        components[i] = list.getFloatOr(i, 0f);
                    }
                    target.set(name, components);
                }
                case IntArrayTag ints when !ints.isEmpty() -> {
                    var array = ints.getAsIntArray();
                    var components = new float[array.length];
                    for (int i = 0; i < components.length; i++) {
                        components[i] = array[i];
                    }
                    target.set(name, components);
                }
                case null, default -> { }
            }
        }
    }

    /** Set one uniform's components from the editor: persists into the shaderData blob and marks
     *  the live buffer dirty (a few-byte re-upload — no recompilation). */
    public void setUniformValue(String name, float... components) {
        var uniforms = pendingShaderData.getCompoundOrEmpty("uniforms");
        var list = new ListTag();
        for (var component : components) {
            list.add(FloatTag.valueOf(component));
        }
        uniforms.put(name, list);
        pendingShaderData.put("uniforms", uniforms);
        if (state != null) {
            state.uniforms.set(name, components);
        }
    }

    /** Current components for one uniform (saved override, else JSON default, else zeros). */
    public float[] getUniformValue(String name, int count) {
        var result = new float[count];
        readShaderJson(json -> {
            if (!json.has("uniforms")) return;
            for (var element : json.getAsJsonArray("uniforms")) {
                var uniform = element.getAsJsonObject();
                if (!uniform.get("name").getAsString().equals(name) || !uniform.has("values")) continue;
                var values = uniform.getAsJsonArray("values");
                for (int i = 0; i < count && i < values.size(); i++) {
                    result[i] = values.get(i).getAsFloat();
                }
            }
        });
        if (pendingShaderData.getCompoundOrEmpty("uniforms").get(name) instanceof ListTag list) {
            for (int i = 0; i < count && i < list.size(); i++) {
                result[i] = list.getFloatOr(i, 0f);
            }
        }
        return result;
    }

    /** Parse this shader's JSON manifest (kept from 1.21 as the configurator/layout metadata). */
    private void readShaderJson(Consumer<JsonObject> consumer) {
        var jsonId = Identifier.fromNamespaceAndPath(shaderLocation.getNamespace(),
                "shaders/core/" + shaderLocation.getPath() + ".json");
        Minecraft.getInstance().getResourceManager().getResource(jsonId).ifPresent(resource -> {
            try (var reader = resource.openAsReader()) {
                consumer.accept(JsonParser.parseReader(reader).getAsJsonObject());
            } catch (Exception e) {
                compiledErrorMessage = "bad shader json: " + e.getMessage();
            }
        });
    }

    /** Engine-owned sampler names — bound by the pipeline itself, never per-material. */
    private static final Set<String> BUILTIN_SAMPLERS = Set.of(
            "Sampler0", "Sampler1", "Sampler2");

    /** {@code SamplerScene*} names are scene-capture samplers (the 1.21 contract: SamplerSceneColor
     *  / SamplerSceneDepth) — no texture id; the drain binds {@code PhotonSceneCapture} views. The
     *  material's custom texture bindings live in {@link #liveSamplers()}. */
    private static boolean isSceneSampler(String name) {
        return name.startsWith("SamplerScene");
    }

    @Override
    @SuppressWarnings("resource") // state() is field-managed, freed by the Cleaner
    public RenderType getRenderType(
            MaterialSetting setting,
            VertexFormat.Mode mode) {
        // resource reload: re-read the JSON layout + rebuild the buffer/RenderTypes (the GLSL is
        // recompiled by the engine, but our std140 layout comes from the shader JSON)
        int generation = PhotonRenderTypes.reloadGeneration();
        if (builtGeneration != generation) {
            builtGeneration = generation;
            if (cachedMeta != null || state != null) {
                recompile();
            }
        }
        var s = state();
        var m = meta();
        var key = new PhotonPipelines.CustomShaderKey(
                m.vertex(), m.fragment(), Map.of(),
                m.samplerNames(), m.sceneSamplers(), setting.pipelineKey(mode));
        // this material OWNS its RenderTypes, one per draw variant (blend/mode/state); build + register
        // on first use of each variant, freed together when the material is released
        var renderType = s.renderTypes.get(key);
        if (renderType == null) {
            renderType = PhotonRenderTypes.createCustomShader(key, s.uniforms, liveSamplers()).orElse(null);
            if (renderType != null) {
                s.renderTypes.put(key, renderType);
            }
        }
        if (renderType != null) {
            s.uniforms.prepareUpload(); // extraction runs outside any render pass
            return renderType;
        }
        if (compiledErrorMessage.isEmpty()) {
            compiledErrorMessage = "shader failed to compile (26.1-format GLSL required): " + shaderLocation;
        }
        // visibly broken rather than invisible — same policy as MissingMaterial
        return MaterialRenderTypes.hdrParticle(
                MissingTextureAtlasSprite.getLocation(),
                setting.pipelineKey(mode));
    }

    @Override
    public IGuiTexture preview() {
        return DynamicTexture.of(() -> isCompiledError() ?
                new TextTexture(compiledErrorMessage.isEmpty() ? "error" : compiledErrorMessage, 0xffff0000) :
                MaterialPreviewRenderer.previewOf(this));
    }

    @Override
    public IGuiTexture previewLive() {
        return DynamicTexture.of(() -> isCompiledError() ?
                new TextTexture(compiledErrorMessage.isEmpty() ? "error" : compiledErrorMessage, 0xffff0000) :
                MaterialPreviewRenderer.livePreviewOf(this));
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        createPreview(father);

        var configurator = new Configurator();
        var shaderConfigurator = new ConfiguratorGroup("photon.shader.settings");
        shaderConfigurator.setCollapse(false);
        shaderConfigurator.setCanCollapse(false);

        var shaderLocationField = new StringConfigurator("photon.shader",
                () -> shaderLocation.toString(),
                s -> {
                    setShader(Identifier.parse(s));
                    configurator.notifyChanges();
                },
                shaderLocation.toString(),
                true).setResourceLocation(true);

        var reloadButton = new Configurator().addInlineChild(new Button()
                .setOnClick(event -> recompile())
                .setText("photon.reload_shader").layout(layout -> layout.alignSelf(AlignItems.CENTER)));

        configurator.inlineContainer.addChild( // button to select shader
                new Button().setText("photon.select_shader").setOnClick(e -> {
                    var mui = e.currentElement.getModularUI();
                    if (mui == null) return;
                    Dialog.showFileDialog("photon.select_shader", LDLib2.getAssetsDir(), true, Dialog.suffixFilter(".json"), r -> {
                        if (r != null && r.isFile()) {
                            var location = getShaderFromFile(r);
                            if (location == null) return;
                            setShader(location);
                            configurator.notifyChanges();
                        }
                    }).show(mui.ui.rootElement);
                }).layout(layout -> layout.alignSelf(AlignItems.CENTER)));

        father.addConfigurators(
                configurator,
                shaderLocationField,
                reloadButton,
                shaderConfigurator
        );
        buildUniformConfigurators(shaderConfigurator);
        ConfiguratorParser.createConfigurators(father, this);
    }

    /** Visual uniform editors, one row per {@code PhotonCustomMaterial} field (metadata from the
     *  shader JSON): edits persist into the shaderData blob and re-upload the live UBO — the 1.21
     *  dynamic-value editing experience, zero recompiles. */
    @SuppressWarnings("resource") // customUniforms() is field-managed, freed by the Cleaner
    private void buildUniformConfigurators(ConfiguratorGroup group) {
        for (var field : customUniforms().fields()) {
            var count = Math.min(field.type().components, 4); // mat4 editing stays file-side
            if (field.type() == PhotonCustomUniforms.Type.MAT4) {
                continue;
            }
            var name = field.name();
            var row = new Configurator(name);
            for (int i = 0; i < count; i++) {
                var component = i;
                row.inlineContainer.addChildren(new NumberConfigurator("",
                        () -> getUniformValue(name, count)[component],
                        value -> {
                            var components = getUniformValue(name, count);
                            components[component] = value.floatValue();
                            setUniformValue(name, components);
                        },
                        0f, true));
            }
            group.addConfigurators(row);
        }
    }

    @Nullable
    public static Identifier getShaderFromFile(File filePath) {
        String fullPath = filePath.getPath().replace('\\', '/');

        // find the "assets/" directory in the path
        var assetsIndex = fullPath.indexOf("assets/");
        if (assetsIndex == -1) {
            return null;
        }

        var relativePath = fullPath.substring(assetsIndex + "assets/".length());

        // find mod_id
        var slashIndex = relativePath.indexOf('/');
        if (slashIndex == -1) {
            return null;
        }

        var modId = relativePath.substring(0, slashIndex);
        var subPath = relativePath.substring(slashIndex + 1);

        // find shader location
        var shaderIndex = subPath.indexOf("shaders/core/");
        if (shaderIndex == -1) {
            return null;
        }

        var shaderPath = subPath.substring(shaderIndex + "shaders/core/".length());
        if (!shaderPath.endsWith(".json")) {
            return null;
        }

        var location = modId + ":" + shaderPath.substring(0, shaderPath.length() - 5); // remove ".json" suffix

        if (LDLib2.isValidResourceLocation(location)) {
            return Identifier.parse(location);
        }
        return null;
    }
}
