package com.lowdragmc.photon.client.fx.fxpack;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.photon.Photon;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.fml.ModList;

import com.google.gson.JsonParser;
import com.lowdragmc.lowdraglib2.editor.resource.FilePath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.photon.client.fx.FX;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * FX packs ({@code .fxpack}): the distribution container for Photon effects. An {@code .fxpack} is
 * simply a <b>zip in standard resource-pack layout</b> — {@code assets/<ns>/fx/<name>.fx} definitions
 * (plain, references only) next to every asset they need (textures, obj models, core shaders, and the
 * content-addressed library files under {@code assets/photon_fx/...}), written by
 * {@link FXPackExporter}.
 * <p>
 * Loading: every {@code <gameDir>/photon/fxpacks/*.fxpack} is mounted as a hidden, always-on,
 * lowest-priority resource pack ({@link #repositorySource()}, registered via
 * {@code AddPackFindersEvent}). Being lowest priority, its assets only fill locations nothing else
 * provides — mods, user resource packs and the ldlib2 editor folder all override them, so users can
 * reskin a packed fx with an ordinary resource pack and an author's local files always win. Vanilla
 * serves everything lazily from the zip (no parsing, no memory copies at reload); the fx definitions
 * inside load through the normal {@code FXHelper} path by id ({@code <ns>:<name>}). Dropping a new
 * file in requires a resource reload (F3+T) to be discovered, like any resource pack.
 * <p>
 * Mods have three options, all equivalent at runtime: merge the layout straight into their
 * {@code assets/} (a mod jar already IS a resource pack), put an unzipped pack folder under
 * {@code fxpacks/<name>/} at the jar root (mounted natively), or drop the {@code .fxpack} file
 * itself under {@code fxpacks/} (extracted once into a content-addressed cache, then mounted).
 */
public final class FXPacks {
    public static final String SUFFIX = ".fxpack";

    private FXPacks() {
    }

    /** {@code <gameDir>/photon/fxpacks} — where users drop {@code .fxpack} files (and exports go). */
    public static File getFxPacksDir() {
        return new File(Platform.getGamePath().toFile(), "photon/fxpacks");
    }

    /** Directory inside a mod jar scanned for shipped fx packs ({@code .fxpack} files or unzipped folders). */
    public static final String MOD_FXPACKS_DIR = "fxpacks";

    /** Cache for {@code .fxpack} zips extracted out of mod jars (zip-in-zip can't be mounted directly). */
    private static File getModCacheDir() {
        return new File(Platform.getGamePath().toFile(), "photon/fxpack_cache");
    }

    /**
     * The repository source registered via {@code AddPackFindersEvent} (client resources). Re-runs on
     * every pack-repository reload, so newly dropped files appear after F3+T. Discovers:
     * <ul>
     *   <li>{@code <gameDir>/photon/fxpacks/*.fxpack} — user-installed packs, mounted directly;</li>
     *   <li>{@code fxpacks/} at each mod jar's root — {@code <name>/} folders mount natively
     *       ({@code PathPackResources} over the jar-internal path, zero extraction) and
     *       {@code *.fxpack} zips are extracted once into a content-addressed cache and mounted from
     *       there (a nested zip cannot be opened lazily).</li>
     * </ul>
     */
    public static RepositorySource repositorySource() {
        return consumer -> {
            mountFolderPacks(consumer);
            mountModPacks(consumer);
        };
    }

    private static void mountFolderPacks(Consumer<Pack> consumer) {
        var files = getFxPacksDir().listFiles(file -> file.isFile() && file.getName().endsWith(SUFFIX));
        if (files == null) return;
        for (var file : files) {
            consumer.accept(createPack("fxpack/" + file.getName(), file.getName(),
                    new FilePackResources.FileResourcesSupplier(file.toPath())));
            Photon.LOGGER.info("mounted fx pack {}", file.getName());
        }
    }

    /** Mount every fx pack shipped inside mod jars under {@code fxpacks/}; see {@link #repositorySource}. */
    private static void mountModPacks(Consumer<Pack> consumer) {
        var liveCacheFiles = new HashSet<String>();
        for (var modFileInfo : ModList.get().getModFiles()) {
            var modId = modFileInfo.getMods().isEmpty() ? modFileInfo.getFile().getFileName()
                    : modFileInfo.getMods().getFirst().getModId();
            try {
                // 26.1 FML: IModFile.findResource is gone — resolve the dir under the jar's content roots
                var packsDir = modFileInfo.getFile().getContents().getContentRoots().stream()
                        .map(root -> root.resolve(MOD_FXPACKS_DIR))
                        .filter(Files::isDirectory)
                        .findFirst().orElse(null);
                if (packsDir == null) continue;
                try (var entries = Files.list(packsDir)) {
                    for (var entry : entries.toList()) {
                        var name = stripSlash(entry.getFileName().toString());
                        var id = "fxpack/mod/" + modId + "/" + name;
                        if (Files.isDirectory(entry)) {
                            // unzipped pack folder: PathPackResources serves it straight out of the jar
                            consumer.accept(createPack(id, name,
                                    new PathPackResources.PathResourcesSupplier(entry)));
                            Photon.LOGGER.info("mounted fx pack {} from mod {}", name, modId);
                        } else if (name.endsWith(SUFFIX)) {
                            var cached = extractToCache(modId, name, entry);
                            liveCacheFiles.add(cached.getName());
                            consumer.accept(createPack(id, name,
                                    new FilePackResources.FileResourcesSupplier(cached.toPath())));
                            Photon.LOGGER.info("mounted fx pack {} from mod {} (cached)", name, modId);
                        }
                    }
                }
            } catch (Exception e) {
                Photon.LOGGER.warn("failed to mount fx packs of mod {}", modId, e);
            }
        }
        cleanCache(liveCacheFiles);
    }

    private static Pack createPack(String id, String displayName, Pack.ResourcesSupplier resources) {
        var location = new PackLocationInfo(id, Component.literal(displayName), PackSource.BUILT_IN, Optional.empty());
        // constructed directly (not readMetaAndCreate): always compatible, hidden from the pack
        // screen, pinned to the bottom = lowest priority (everything else overrides fx-pack assets)
        var metadata = new Pack.Metadata(Component.literal("Photon FX pack " + displayName),
                PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of(), true);
        return new Pack(location, resources, metadata, new PackSelectionConfig(true, Pack.Position.BOTTOM, true));
    }

    /** Copy a mod-shipped {@code .fxpack} out of the jar, content-addressed so it re-extracts only on change. */
    private static File extractToCache(String modId, String name, Path source) throws IOException {
        var bytes = Files.readAllBytes(source);
        var target = new File(getModCacheDir(), "%s-%s-%s%s".formatted(modId,
                name.substring(0, name.length() - SUFFIX.length()), Long.toHexString(fnv1a64(bytes)), SUFFIX));
        if (!target.isFile()) {
            target.getParentFile().mkdirs();
            Files.write(target.toPath(), bytes);
        }
        return target;
    }

    /** Drop cache files whose source mod pack disappeared or changed content. */
    private static void cleanCache(Set<String> liveCacheFiles) {
        var stale = getModCacheDir().listFiles(file -> file.isFile() && !liveCacheFiles.contains(file.getName()));
        if (stale == null) return;
        for (var file : stale) {
            if (file.delete()) {
                Photon.LOGGER.info("fxpack cache: dropped stale {}", file.getName());
            }
        }
    }

    /** FNV-1a 64, the content hash used across the fxpack system. */
    static long fnv1a64(byte[] bytes) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    // ---- pack file management (editor side) -----------------------------------------------------------

    /**
     * Whether the pack file is currently held open by another handle. A mounted pack that has served
     * at least one read keeps a {@code ZipFile} open until the next resource reload closes it — and
     * on Windows that blocks the rename-over-original jdk.zipfs commits edits with, so exports into /
     * removals from the file would fail. The rename-onto-itself probe is a cheap, side-effect-free
     * lock detector; callers should tell the user to reload resources (F3+T) and retry.
     */
    public static boolean isFileLocked(File file) {
        return file.isFile() && !file.renameTo(file);
    }

    /** The fx ids contained in a pack file ({@code assets/<ns>/fx/**.fx} → {@code <ns>:<name>}). */
    public static List<Identifier> listFx(File fxpackFile) throws IOException {
        var result = new ArrayList<Identifier>();
        try (var zip = FileSystems.newFileSystem(fxpackFile.toPath())) {
            for (var entry : listFxEntries(zip)) {
                result.add(entry.id());
            }
        }
        return result;
    }

    /** Remove one fx from a pack file, then garbage-collect resources nothing references anymore. */
    public static void removeFx(File fxpackFile, Identifier fxId) throws IOException {
        try (var zip = FileSystems.newFileSystem(fxpackFile.toPath())) {
            Files.deleteIfExists(zip.getPath("assets", fxId.getNamespace(), "fx", fxId.getPath() + FX.SUFFIX));
            gc(zip);
        }
    }

    private record FxEntry(Identifier id, Path path) {
    }

    private static List<FxEntry> listFxEntries(FileSystem zip) throws IOException {
        var result = new ArrayList<FxEntry>();
        var assets = zip.getPath("assets");
        if (!Files.isDirectory(assets)) return result;
        try (var namespaces = Files.list(assets)) {
            for (var namespaceDir : namespaces.toList()) {
                var namespace = stripSlash(namespaceDir.getFileName().toString());
                var fxDir = namespaceDir.resolve("fx");
                if (!Files.isDirectory(fxDir)) continue;
                try (var walk = Files.walk(fxDir)) {
                    for (var path : walk.filter(p -> p.toString().endsWith(FX.SUFFIX)).toList()) {
                        var name = stripSlash(fxDir.relativize(path).toString());
                        result.add(new FxEntry(Identifier.fromNamespaceAndPath(namespace,
                                name.substring(0, name.length() - FX.SUFFIX.length())), path));
                    }
                }
            }
        }
        return result;
    }

    private static String stripSlash(String name) {
        return name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    }

    /**
     * Mark-and-sweep over the pack: keep {@code pack.mcmeta}, every fx entry, and everything the fx
     * (transitively, through packed library {@code .nbt} files) still reference; delete the rest.
     * Run after every mutation so replaced/removed effects don't leave orphaned resources behind.
     * An {@code .fxpack} is machine-managed — hand-added extras belong in a real resource pack.
     * <p>
     * CONTRACT with {@link FXPackExporter}: gc runs right after every export, so every file the
     * exporter packs MUST be reachable through {@link #mark}'s reference patterns —
     * {@code file(assets/...)} strings, {@code .png}/{@code .obj} location strings, and the
     * {@code custom_shader} json→vsh/fsh chain. A new packed kind needs a matching mark pattern
     * here, or its files are swept immediately after being written.
     */
    static void gc(FileSystem zip) throws IOException {
        var referenced = new HashSet<String>(); // normalized "assets/<ns>/<path>" strings
        var visited = new HashSet<String>();
        for (var entry : listFxEntries(zip)) {
            referenced.add(entryKey(entry.id().getNamespace(), "fx/" + entry.id().getPath() + FX.SUFFIX));
            try (var in = Files.newInputStream(entry.path())) {
                mark(NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()), zip, referenced, visited);
            } catch (Exception e) {
                Photon.LOGGER.warn("fxpack gc: failed to read {} — keeping everything", entry.path(), e);
                return; // never sweep on a partial mark
            }
        }
        var assets = zip.getPath("assets");
        if (!Files.isDirectory(assets)) return;
        try (var walk = Files.walk(assets)) {
            for (var path : walk.filter(Files::isRegularFile).toList()) {
                var key = stripSlash(path.toString()).replaceFirst("^/", "");
                if (!referenced.contains(key)) {
                    Files.delete(path);
                    Photon.LOGGER.info("fxpack gc: removed orphaned {}", key);
                }
            }
        }
    }

    private static String entryKey(String namespace, String path) {
        return "assets/" + namespace + "/" + path;
    }

    private static String entryKey(Identifier location) {
        return entryKey(location.getNamespace(), location.getPath());
    }

    /** Collect every in-pack location a packed NBT references (recursing into packed library files). */
    private static void mark(CompoundTag tag, FileSystem zip, Set<String> referenced, Set<String> visited) {
        // custom shaders: the json names the vsh/fsh program files
        if ("custom_shader".equals(tag.getStringOr("type", "")) && tag.getCompound("data").isPresent()) {
            var shaderId = Identifier.tryParse(tag.getCompoundOrEmpty("data").getStringOr("shaderLocation", ""));
            if (shaderId != null) {
                markCoreShader(shaderId, zip, referenced);
            }
        }
        // render-graph pass sources carrying a hand-written shader (PassSource.CODEC shape) —
        // the exporter packs its json+vsh+fsh under their original locations
        if ("CUSTOM_SHADER".equals(tag.getStringOr("type", "")) && tag.getString("shader").isPresent()) {
            var shaderId = Identifier.tryParse(tag.getStringOr("shader", ""));
            if (shaderId != null) {
                markCoreShader(shaderId, zip, referenced);
            }
        }
        for (var key : tag.keySet()) {
            markChild(tag.get(key), zip, referenced, visited);
        }
    }

    private static void markChild(@Nullable Tag child, FileSystem zip,
                                  Set<String> referenced, Set<String> visited) {
        switch (child) {
            case CompoundTag compound -> mark(compound, zip, referenced, visited);
            case ListTag list -> list.forEach(tag -> markChild(tag, zip, referenced, visited));
            case StringTag string -> markString(string.asString().orElse(""), zip, referenced, visited);
            case null, default -> { }
        }
    }

    private static void markString(String value, FileSystem zip, Set<String> referenced, Set<String> visited) {
        if (value.endsWith(".png") || value.endsWith(".obj")) {
            var location = Identifier.tryParse(value);
            if (location != null) {
                referenced.add(entryKey(location));
            }
        } else if (value.startsWith("file(assets/") && value.endsWith(")")) {
            // a rewritten library reference — mark the file and recurse into it (graphs reference
            // subgraphs and textures of their own)
            if (!(IResourcePath.parse(value) instanceof FilePath filePath) || filePath.location == null) return;
            var key = entryKey(filePath.location);
            referenced.add(key);
            if (!visited.add(key)) return;
            var path = zip.getPath(key);
            if (!Files.isRegularFile(path)) return;
            try (var in = new DataInputStream(Files.newInputStream(path))) {
                var nested = NbtIo.read(in);
                if (nested != null) {
                    mark(nested, zip, referenced, visited);
                }
            } catch (IOException e) {
                Photon.LOGGER.warn("fxpack gc: failed to read library file {}", key, e);
            }
        }
    }

    private static void markCoreShader(Identifier shaderId, FileSystem zip, Set<String> referenced) {
        var jsonKey = entryKey(shaderId.getNamespace(), "shaders/core/" + shaderId.getPath() + ".json");
        referenced.add(jsonKey);
        var jsonPath = zip.getPath(jsonKey);
        if (!Files.isRegularFile(jsonPath)) return;
        try {
            var json = JsonParser.parseString(Files.readString(jsonPath, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var stage : new String[][]{{"vertex", ".vsh"}, {"fragment", ".fsh"}}) {
                if (!json.has(stage[0])) continue;
                var programId = Identifier.tryParse(json.get(stage[0]).getAsString());
                if (programId != null) {
                    referenced.add(entryKey(programId.getNamespace(), "shaders/core/" + programId.getPath() + stage[1]));
                }
            }
        } catch (Exception e) {
            Photon.LOGGER.warn("fxpack gc: failed to parse shader json {}", jsonKey, e);
        }
    }
}
