package com.lowdragmc.photon.client.fx.fxpack;

import com.google.gson.JsonParser;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinPath;
import com.lowdragmc.lowdraglib2.editor.resource.FilePath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.editor.resource.ResourceInstance;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.gui.editor.FXProject;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
import com.lowdragmc.photon.gui.editor.resource.MeshResource;
import com.lowdragmc.photon.gui.editor.resource.PhotonShaderFunctionGraphResource;
import com.lowdragmc.photon.gui.editor.resource.ShaderGraphResource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes one FX (plus everything it references) into an {@link FXPacks .fxpack} zip, in standard
 * resource-pack layout. Exporting is <b>additive</b>: repeated exports into the same file add/update
 * entries, so a pack is built up one effect at a time — shared resources land on identical
 * content-addressed names and are naturally stored once.
 * <ul>
 *   <li><b>library resources</b> (materials / shader graphs / function subgraphs / meshes) become
 *       content-addressed files {@code assets/photon_fx/<kind>/<hash>.<kind>.nbt} (the
 *       {@code {type,data}} wrapper LDLib2's {@code PackFileResourceProvider} reads), and the
 *       references inside the exported fx are <b>rewritten</b> to {@code file(assets/photon_fx/...)}
 *       paths — identical content across any exports lands on the identical path, so effects sharing
 *       a resource dedup at the pack layer and their materials batch through ordinary path
 *       equality;</li>
 *   <li><b>raw assets</b> — any referenced {@code .png}/{@code .obj}/{@code .glb}/{@code .gltf} and {@code custom_shader}
 *       json+vsh+fsh files — are carried under their <b>original locations</b> (no rewriting), so
 *       mods and user resource packs override them naturally (user-reskinnable);</li>
 *   <li>the fx itself is written as a plain (references-only) definition at
 *       {@code assets/<ns>/fx/<name>.fx}, loaded by {@code FXHelper} under the id
 *       {@code <ns>:<name>}.</li>
 * </ul>
 * The exporter works on the serialized NBT (a scratch copy — the live project is never touched) with
 * one recursive walk. Assets that resolve from vanilla or a mod jar are skipped (the recipient's game
 * ships them), builtin library paths are skipped (they resolve on every install), and
 * {@code json_model} sources can't be packed (baked through the model system) and produce a warning.
 * <p>
 * CONTRACT with {@link FXPacks#gc}: gc's mark phase must be able to re-discover every packed file
 * from the fx entries ({@code file(assets/...)} strings, {@code .png}/{@code .obj}/{@code .glb}/{@code .gltf} location strings,
 * the {@code custom_shader} json→vsh/fsh chain, and the render-graph pass-source
 * {@code CUSTOM_SHADER} json→vsh/fsh chain). When adding a new packed kind here, add the
 * matching mark pattern in {@code FXPacks.mark} — otherwise gc sweeps the new files right after
 * this exporter writes them. The post-processing kinds (render_graph / fullscreen_graph, incl. the
 * nested render_graph→fullscreen_graph references) travel as {@code file(assets/...)} strings and
 * are covered by mark's generic pattern.
 */
@OnlyIn(Dist.CLIENT)
public final class FXPackExporter {

    /** The namespace packed library files are exported under. */
    public static final String LIBRARY_NAMESPACE = "photon_fx";
    /** pack.mcmeta written into fresh packs (also makes an .fxpack a valid ordinary resource pack). */
    private static final String PACK_MCMETA = """
            {"pack": {"description": "Photon FX pack", "pack_format": 34}}
            """;

    /** Export outcome for the summary log. */
    public record Result(ResourceLocation fxId, int fileCount, List<String> warnings) {
    }

    private final HolderLookup.Provider provider;
    /** Collected pack files (insertion-ordered for deterministic zips). */
    private final Map<ResourceLocation, byte[]> files = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    /** Memoized library embeds by kind+original path (also the cycle guard). */
    private final Map<String, String> packedLibraryPaths = new HashMap<>();
    private final Set<String> packingInProgress = new HashSet<>();
    /** Asset locations already handled (packed or deliberately skipped). */
    private final Set<ResourceLocation> handledAssets = new HashSet<>();

    private FXPackExporter(HolderLookup.Provider provider) {
        this.provider = provider;
    }

    /**
     * Export {@code fx} into {@code fxpackFile} (created if absent) as {@code <namespace>:<fxName>}.
     * Names are sanitized to valid resource-location characters.
     */
    public static Result exportInto(File fxpackFile, String namespace, String fxName, FX fx,
                                    HolderLookup.Provider provider) throws IOException {
        var exporter = new FXPackExporter(provider);
        var root = fx.serializeNBT(provider); // fresh serialization = scratch copy, safe to rewrite
        exporter.walk(root);
        root.putInt("version", FXProject.VERSION);

        var fxId = ResourceLocation.fromNamespaceAndPath(sanitize(namespace), sanitize(fxName));
        var fxBytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, fxBytes);

        if (fxpackFile.getParentFile() != null) {
            fxpackFile.getParentFile().mkdirs();
        }
        try (var zip = FileSystems.newFileSystem(fxpackFile.toPath(), Map.of("create", "true"))) {
            var mcmeta = zip.getPath("pack.mcmeta");
            if (!Files.exists(mcmeta)) {
                Files.writeString(mcmeta, PACK_MCMETA);
            }
            writeEntry(zip.getPath("assets", fxId.getNamespace(), "fx", fxId.getPath() + FX.SUFFIX),
                    fxBytes.toByteArray());
            for (var entry : exporter.files.entrySet()) {
                var location = entry.getKey();
                writeEntry(zip.getPath("assets", location.getNamespace(), location.getPath()), entry.getValue());
            }
            // replacing an fx orphans its previous version's resources — sweep them out
            FXPacks.gc(zip);
        }
        return new Result(fxId, exporter.files.size() + 1, List.copyOf(exporter.warnings));
    }

    private static void writeEntry(java.nio.file.Path path, byte[] bytes) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, bytes);
    }

    /** Lowercase + replace everything outside {@code [a-z0-9/._-]} so ids are always valid. */
    private static String sanitize(String name) {
        var sanitized = name.toLowerCase().replaceAll("[^a-z0-9/._-]", "_");
        // a name with no usable characters (e.g. a file literally called ".fxpack") must not
        // produce an empty namespace/path — ResourceLocation would reject it and abort the export
        return sanitized.isBlank() ? "fx" : sanitized;
    }

    // ---- the walk --------------------------------------------------------------------------------

    private void walk(CompoundTag tag) {
        // typed wrapper handlers first (they may rewrite path strings inside "data")
        if (tag.contains("type", Tag.TAG_STRING) && tag.contains("data", Tag.TAG_COMPOUND)) {
            var data = tag.getCompound("data");
            switch (tag.getString("type")) {
                case "ui_resource_material" -> rewritePathField(data, "resourcePath",
                        MaterialResource.INSTANCE.getResourceInstance());
                case "shader_graph" -> rewritePathField(data, "graphPath",
                        ShaderGraphResource.INSTANCE.getResourceInstance());
                case "resource_mesh" -> rewritePathField(data, "resourcePath",
                        MeshResource.INSTANCE.getResourceInstance());
                case "custom_shader" -> {
                    if (data.contains("shaderLocation", Tag.TAG_STRING)) {
                        packCoreShader(ResourceLocation.tryParse(data.getString("shaderLocation")));
                    }
                }
                case "json_model" -> warnings.add("json model '%s' cannot be packed (baked models need to ship as assets)"
                        .formatted(data.getString("modelLocation")));
                default -> { }
            }
        }
        // graph-internal external subgraph references
        if (tag.contains("externalPathString", Tag.TAG_STRING)) {
            rewriteSubgraphRef(tag);
        }
        // post-process clips: {effect: "type(path)", weight: {...}} (PostProcessTrack clip extras)
        if (tag.contains("effect", Tag.TAG_STRING) && tag.contains("weight", Tag.TAG_COMPOUND)) {
            rewriteEffectRef(tag);
        }
        // render-graph pass sources (PassSource.CODEC: type omitted for the GRAPH default)
        if ("CUSTOM_SHADER".equals(tag.getString("type")) && tag.contains("shader", Tag.TAG_STRING)) {
            packCoreShader(ResourceLocation.tryParse(tag.getString("shader")));
        }
        if (tag.contains("graph", Tag.TAG_STRING)) {
            rewriteFullscreenGraphRef(tag);
        }
        // generic recursion + raw-asset string sweep
        for (var key : tag.getAllKeys()) {
            walkChild(tag.get(key));
        }
    }

    private void walkChild(@Nullable Tag child) {
        switch (child) {
            case CompoundTag compound -> walk(compound);
            case ListTag list -> list.forEach(this::walkChild);
            case StringTag string -> sweepAssetString(string.getAsString());
            case null, default -> { }
        }
    }

    /** Any string that is a raw-asset resource location ({@link FXPacks#isPackableAsset}) gets packed
     *  under that location. */
    private void sweepAssetString(String value) {
        if (FXPacks.isPackableAsset(value)) {
            var location = ResourceLocation.tryParse(value);
            if (location != null) {
                packAsset(location);
            }
        }
    }

    // ---- library resources --------------------------------------------------------------------------

    /** Pack the library resource referenced by {@code data.fieldKey} and rewrite it to the packed path. */
    private <T> void rewritePathField(CompoundTag data, String fieldKey, ResourceInstance<T> instance) {
        if (!data.contains(fieldKey, Tag.TAG_STRING)) return;
        var path = IResourcePath.parse(data.getString(fieldKey));
        var rewritten = packLibraryResource(instance, path);
        if (rewritten != null) {
            data.putString(fieldKey, rewritten);
        }
    }

    /** A post-process clip's effect: a render-graph asset, or a bare fullscreen graph played
     *  through the single-pass adapter (mirror {@code PostEffectStack.resolveEffect}'s order). */
    private void rewriteEffectRef(CompoundTag clipTag) {
        var path = IResourcePath.parse(clipTag.getString("effect"));
        if (path == null || path instanceof BuiltinPath) return;
        String rewritten = null;
        var renderGraphs = com.lowdragmc.photon.gui.editor.resource.RenderGraphResource.INSTANCE.getResourceInstance();
        if (renderGraphs.getResource(path) != null) {
            rewritten = packLibraryResource(renderGraphs, path);
        } else {
            var fullscreenGraphs = com.lowdragmc.photon.gui.editor.resource.FullscreenShaderGraphResource.INSTANCE
                    .getResourceInstance();
            if (fullscreenGraphs.getResource(path) != null) {
                rewritten = packLibraryResource(fullscreenGraphs, path);
            } else {
                warnings.add("post-process clip effect %s not found — exported as a plain reference"
                        .formatted(path.getPathWithType()));
            }
        }
        if (rewritten != null) {
            clipTag.putString("effect", rewritten);
        }
    }

    /** A pass node's fullscreen-graph source. Heuristic key ("graph" string) — only rewrites when
     *  the value actually resolves in the fullscreen library, so unrelated fields pass through. */
    private void rewriteFullscreenGraphRef(CompoundTag sourceTag) {
        var path = IResourcePath.parse(sourceTag.getString("graph"));
        if (path == null || path instanceof BuiltinPath) return;
        var instance = com.lowdragmc.photon.gui.editor.resource.FullscreenShaderGraphResource.INSTANCE
                .getResourceInstance();
        if (instance.getResource(path) == null) return;
        var rewritten = packLibraryResource(instance, path);
        if (rewritten != null) {
            sourceTag.putString("graph", rewritten);
        }
    }

    private void rewriteSubgraphRef(CompoundTag subgraphNode) {
        var path = IResourcePath.parse(subgraphNode.getString("externalPathString"));
        if (path == null) return;
        // mirror ShaderGraphRuntime.RESOLVER: function-graph library first, then shader graphs
        var rewritten = packLibraryResource(PhotonShaderFunctionGraphResource.INSTANCE.getResourceInstance(), path);
        if (rewritten == null) {
            rewritten = packLibraryResource(ShaderGraphResource.INSTANCE.getResourceInstance(), path);
        }
        if (rewritten != null) {
            subgraphNode.putString("externalPathString", rewritten);
        }
    }

    /**
     * Pack one library resource as a content-addressed file
     * ({@code assets/photon_fx/<kind>/<hash>.<kind>.nbt}, wrapper format of LDLib2's file providers)
     * and return the {@code file(assets/...)} path string to rewrite the reference to — or null when
     * the resource shouldn't / can't be packed (builtin, missing, cyclic); the original reference is
     * then left untouched. The payload is recursively packed first, so the hash covers the final
     * (rewritten) content and nested references travel too.
     */
    @Nullable
    private <T> String packLibraryResource(ResourceInstance<T> instance, @Nullable IResourcePath path) {
        if (path == null || path instanceof BuiltinPath) {
            return null; // builtin resolves on every install
        }
        var kind = instance.resource.getName();
        var memoKey = kind + "|" + path.getPathWithType();
        var memoized = packedLibraryPaths.get(memoKey);
        if (memoized != null) return memoized;
        if (!packingInProgress.add(memoKey)) {
            warnings.add("cyclic resource reference at %s (%s) — left as a plain reference".formatted(path.getPathWithType(), kind));
            return null;
        }
        try {
            var value = instance.getResource(path);
            if (value == null) {
                warnings.add("missing %s resource %s — exported as a plain reference".formatted(kind, path.getPathWithType()));
                return null;
            }
            var serialized = instance.resource.serializeResource(value, provider);
            if (serialized == null) {
                warnings.add("unserializable %s resource %s — exported as a plain reference".formatted(kind, path.getPathWithType()));
                return null;
            }
            var payload = serialized.copy(); // never mutate a library-cached tag
            if (payload instanceof CompoundTag compound) {
                walk(compound); // pack + rewrite everything the resource itself references
            }
            // the exact wrapper LDLib2's PackFileResourceProvider / FileResourceProvider read
            var wrapper = new CompoundTag();
            wrapper.put("data", payload);
            wrapper.putString("type", kind);
            var bytes = nbtBytes(wrapper);
            var location = ResourceLocation.fromNamespaceAndPath(LIBRARY_NAMESPACE,
                    kind + "/" + Long.toHexString(FXPacks.fnv1a64(bytes)) + instance.resource.getFileExtension());
            files.put(location, bytes);
            // file(assets/photon_fx/...) round-trips into a location-carrying FilePath → resolved by
            // the pack-file provider from the mounted pack; equal content ⇒ equal path ⇒ batching
            var rewritten = new FilePath(location).getPathWithType();
            packedLibraryPaths.put(memoKey, rewritten);
            return rewritten;
        } finally {
            packingInProgress.remove(memoKey);
        }
    }

    // ---- raw assets --------------------------------------------------------------------------------

    /** Pack a texture/obj under its original location (mods and user packs override it naturally). */
    private void packAsset(ResourceLocation location) {
        if (!handledAssets.add(location)) return;
        var bytes = readPackableAsset(location);
        if (bytes != null) {
            files.put(location, bytes);
        }
    }

    /** Pack a custom core shader: the json plus the vsh/fsh files it names, each under its location. */
    private void packCoreShader(@Nullable ResourceLocation shaderId) {
        if (shaderId == null) return;
        var jsonLocation = ResourceLocation.fromNamespaceAndPath(shaderId.getNamespace(),
                "shaders/core/" + shaderId.getPath() + ".json");
        if (!handledAssets.add(jsonLocation)) return;
        var jsonBytes = readPackableAsset(jsonLocation);
        if (jsonBytes == null) return;
        files.put(jsonLocation, jsonBytes);

        // the json names the program files: "vertex"/"fragment" are shader ids like "photon:particle"
        try {
            var json = JsonParser.parseString(new String(jsonBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            packProgramStage(json, "vertex", ".vsh");
            packProgramStage(json, "fragment", ".fsh");
        } catch (Exception e) {
            warnings.add("could not parse shader json %s (%s) — packing the json only".formatted(jsonLocation, e.getMessage()));
        }
    }

    /** Pack one program stage file when it is itself an author-local asset. */
    private void packProgramStage(com.google.gson.JsonObject json, String jsonKey, String extension) {
        if (!json.has(jsonKey)) return;
        var programId = ResourceLocation.tryParse(json.get(jsonKey).getAsString());
        if (programId == null) return;
        var fileLocation = ResourceLocation.fromNamespaceAndPath(programId.getNamespace(),
                "shaders/core/" + programId.getPath() + extension);
        if (!handledAssets.add(fileLocation)) return;
        var bytes = readPackableAsset(fileLocation);
        if (bytes != null) { // null: provided by a mod/vanilla on the recipient, or missing (warned)
            files.put(fileLocation, bytes);
        }
    }

    /**
     * Read an asset's bytes iff it should travel in the pack: missing → warning + null; provided by
     * vanilla or a mod jar → null (the recipient's game ships it); anything else (the ldlib2 editor
     * asset dir, resource packs, other fx packs) → bytes.
     */
    private byte @Nullable [] readPackableAsset(ResourceLocation location) {
        var resource = Minecraft.getInstance().getResourceManager().getResource(location).orElse(null);
        if (resource == null) {
            warnings.add("referenced asset %s does not resolve — the export will look wrong wherever it's missing"
                    .formatted(location));
            return null;
        }
        var packId = resource.sourcePackId();
        if ("vanilla".equals(packId) || packId.startsWith("mod")) {
            return null; // ships with the game / a mod — no need to carry it
        }
        try (var in = resource.open()) {
            return in.readAllBytes();
        } catch (IOException e) {
            warnings.add("failed to read asset %s (%s)".formatted(location, e.getMessage()));
            return null;
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static byte[] nbtBytes(CompoundTag tag) {
        try {
            var out = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(out));
            return out.toByteArray();
        } catch (IOException e) {
            return tag.toString().getBytes(StandardCharsets.UTF_8);
        }
    }
}
