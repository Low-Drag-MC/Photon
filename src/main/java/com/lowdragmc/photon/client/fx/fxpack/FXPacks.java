package com.lowdragmc.photon.client.fx.fxpack;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.photon.Photon;
import net.minecraft.client.Minecraft;
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
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.core.mixins.accessor.FilePackResourcesAccessor;
import com.lowdragmc.photon.core.mixins.accessor.SharedZipFileAccessAccessor;
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
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
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
 * file in requires a resource reload (F3+T) to be discovered, like any resource pack; the editor's
 * edits of a pack that is already mounted ({@link #rewrite}) are served right away.
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
        deleteLeftovers(getFxPacksDir());
        var files = getFxPacksDir().listFiles(file -> file.isFile() && file.getName().endsWith(SUFFIX));
        if (files == null) return;
        for (var file : files) {
            consumer.accept(createPack(folderPackId(file), file.getName(),
                    new FilePackResources.FileResourcesSupplier(file.toPath())));
            Photon.LOGGER.info("mounted fx pack {}", file.getName());
        }
    }

    private static String folderPackId(File packFile) {
        return "fxpack/" + packFile.getName();
    }

    /**
     * In {@code folder}: puts back a pack a {@link #isFileLocked} probe could not move back, and deletes the copies a
     * {@link #rewrite} left behind when the game died mid-edit, and the zip-fs temp files of one.
     */
    static void deleteLeftovers(File folder) {
        var probes = folder.listFiles(file -> file.isFile() && file.getName().endsWith(SUFFIX + LOCK_PROBE_SUFFIX));
        if (probes != null) {
            for (var probe : probes) {
                var name = probe.getName();
                var pack = new File(probe.getParentFile(), name.substring(0, name.length() - LOCK_PROBE_SUFFIX.length()));
                if (pack.exists()) {
                    // the pack was written again since: the probe is an older copy of it
                    Photon.LOGGER.warn("fxpacks: {} has a newer {}, left as it is", probe.getName(), pack.getName());
                } else if (probe.renameTo(pack)) {
                    Photon.LOGGER.info("fxpacks: put back {} from its lock probe", pack.getName());
                }
            }
        }
        var leftovers = folder.listFiles(file -> file.isFile() && (file.getName().endsWith(SUFFIX + COPY_SUFFIX)
                || file.getName().startsWith("zipfstmp") && file.getName().endsWith(".tmp")));
        if (leftovers == null) return;
        for (var file : leftovers) {
            // a copy an older build left read-only (Windows copied the flag) would never go otherwise
            file.setWritable(true);
            if (file.delete()) {
                Photon.LOGGER.info("fxpacks: deleted leftover {}", file.getName());
            }
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

    /** Suffix of the private copy {@link #rewrite} edits; not {@link #SUFFIX}, so it is never mounted. */
    static final String COPY_SUFFIX = ".editing";
    private static final String LOCK_PROBE_SUFFIX = ".lockprobe";
    private static final int REPLACE_ATTEMPTS = 3;

    /** A pack file another program holds open, so it cannot be replaced. */
    public static final class PackInUseException extends IOException {
        PackInUseException(File packFile, IOException cause) {
            super("fx pack " + packFile + " is in use", cause);
        }
    }

    /** One edit of a pack's zip file system, run by {@link #rewrite}. */
    @FunctionalInterface
    interface ZipEdit {
        void apply(FileSystem zip) throws IOException;
    }

    /**
     * Whether the pack file is held open by another handle: on Windows an open handle refuses a rename. Probed by
     * renaming it next to itself and straight back. 1.21 renamed it onto itself, which has no side effects, but
     * JDK 25 answers a rename onto the same path without asking the file system, so that probe always says free.
     * For diagnostics only: while the file is moved away, a read on another thread fails and marks the pack
     * unreadable until it is released.
     */
    public static boolean isFileLocked(File file) {
        if (!file.isFile()) return false;
        var path = file.toPath();
        var probe = path.resolveSibling(file.getName() + LOCK_PROBE_SUFFIX);
        try {
            Files.move(path, probe, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            return true;
        }
        try {
            Files.move(probe, path, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // put back by deleteLeftovers at the next pack scan
            Photon.LOGGER.error("fxpacks: could not move {} back from its lock probe", file, e);
        }
        return false;
    }

    /** Whether the running game has this pack file mounted, i.e. serves its fx without a resource reload. */
    public static boolean isMounted(File packFile) {
        return !livePacks(packFile).isEmpty();
    }

    /** Whether a resource reload mounts this file: only {@code .fxpack}s directly in {@link #getFxPacksDir()} are. */
    public static boolean isLoadable(File packFile) {
        var folder = packFile.getAbsoluteFile().getParentFile();
        return packFile.getName().endsWith(SUFFIX) && folder != null && identity(folder).equals(identity(getFxPacksDir()));
    }

    /** How the running game resolves an fx of this pack — what a command playing it would load. */
    public static Resolution resolve(File packFile, Identifier fxId) {
        var resource = Minecraft.getInstance().getResourceManager().getResource(FXHelper.fileLocation(fxId)).orElse(null);
        if (resource == null) {
            return new Resolution(Resolution.Status.NOT_MOUNTED, null);
        }
        var source = resource.sourcePackId();
        var live = livePacks(packFile).stream().anyMatch(pack -> pack.packId().equals(source));
        return new Resolution(live ? Resolution.Status.LIVE : Resolution.Status.SHADOWED, source);
    }

    /**
     * @param sourcePackId the pack the game loads the fx from, null when nothing provides it
     */
    public record Resolution(Status status, @Nullable String sourcePackId) {
        public enum Status {
            /** loaded from this pack */
            LIVE,
            /** a pack of higher priority provides the same id, so that one is loaded */
            SHADOWED,
            /** not loadable until a resource reload mounts the pack */
            NOT_MOUNTED
        }
    }

    /**
     * The resource manager's packs reading this file. Its {@code ZipFile} stays open from the reload that
     * mounted the pack until the next one: on Windows that refuses any replacement of the file, elsewhere
     * it keeps serving the replaced one.
     */
    private static List<FilePackResources> livePacks(File packFile) {
        var target = identity(packFile);
        return Minecraft.getInstance().getResourceManager().listPacks()
                .filter(pack -> pack instanceof FilePackResources)
                .map(FilePackResources.class::cast)
                .filter(pack -> identity(zipAccess(pack).getFile()).equals(target))
                .toList();
    }

    /**
     * Closes the resource manager's handle on the file; vanilla reopens it on the next read. A read on
     * another thread that raced a replacement and failed to reopen it has marked it failed, which vanilla
     * never retries, so that mark goes too.
     */
    static void release(File packFile) {
        for (var pack : livePacks(packFile)) {
            pack.close();
            zipAccess(pack).setFailedToLoad(false);
        }
    }

    private static SharedZipFileAccessAccessor zipAccess(FilePackResources pack) {
        return (SharedZipFileAccessAccessor) ((FilePackResourcesAccessor) pack).getZipFileAccess();
    }

    private static Path identity(File file) {
        try {
            return file.getCanonicalFile().toPath();
        } catch (IOException e) {
            return file.getAbsoluteFile().toPath().normalize();
        }
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

    /**
     * Remove one fx from a pack file, then garbage-collect resources nothing references anymore. Render
     * thread only, see {@link #rewrite}.
     */
    public static void removeFx(File fxpackFile, Identifier fxId) throws IOException {
        if (!fxpackFile.isFile()) {
            throw new NoSuchFileException(fxpackFile.toString()); // a rewrite would create an empty pack there
        }
        rewrite(fxpackFile, zip -> Files.deleteIfExists(entryPath(zip, FXHelper.fileLocation(fxId))));
        FXHelper.invalidate(List.of(fxId));
    }

    /** Where a resource lives inside a pack's zip file system. */
    public static Path entryPath(FileSystem zip, Identifier location) {
        return zip.getPath("assets", location.getNamespace(), location.getPath());
    }

    /**
     * Rewrites a pack file: {@code edit} and the {@link #gc} after it run on a private copy, which then
     * replaces the pack in one move, so a failed edit leaves the pack as it was. The resource manager's
     * handle is released around the move, and since vanilla reopens a closed pack on its next read, a
     * mounted pack serves the new content straight away — no resource reload. Render thread only: the
     * release closes a {@code ZipFile} the game reads from.
     *
     * @throws PackInUseException when the file stays locked, e.g. by another program
     */
    static void rewrite(File packFile, ZipEdit edit) throws IOException {
        rewrite(packFile, edit, () -> release(packFile));
    }

    static void rewrite(File packFile, ZipEdit edit, Runnable release) throws IOException {
        var pack = packFile.toPath();
        var copy = pack.resolveSibling(packFile.getName() + COPY_SUFFIX);
        Files.deleteIfExists(copy);
        try {
            if (Files.exists(pack)) {
                Files.copy(pack, copy);
                // Windows copies the read-only flag too, and a read-only copy could be neither edited nor deleted
                copy.toFile().setWritable(true);
            }
            try (var zip = FileSystems.newFileSystem(copy, Map.of("create", "true"))) {
                edit.apply(zip);
                gc(zip);
            }
            replace(copy, pack, release);
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    private static void replace(Path copy, Path pack, Runnable release) throws IOException {
        for (int attempt = 1; ; attempt++) {
            release.run();
            try {
                move(copy, pack);
                break;
            } catch (IOException e) {
                // a read on another thread can reopen the pack between the release and the move
                if (attempt == REPLACE_ATTEMPTS) {
                    // what Windows says to a move over a file another handle holds open — and over a read-only one
                    throw e instanceof AccessDeniedException && Files.isWritable(pack)
                            ? new PackInUseException(pack.toFile(), e) : e;
                }
                LockSupport.parkNanos(20_000_000L);
            }
        }
        // whatever read the old file before the move still holds it on systems that allow the move anyway
        release.run();
    }

    private static void move(Path copy, Path pack) throws IOException {
        try {
            Files.move(copy, pack, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(copy, pack, StandardCopyOption.REPLACE_EXISTING);
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
     * {@link #rewrite} runs it after every edit, so replaced/removed effects don't leave orphaned
     * resources behind.
     * An {@code .fxpack} is machine-managed — hand-added extras belong in a real resource pack.
     * <p>
     * CONTRACT with {@link FXPackExporter}: gc runs right after every export, so every file the
     * exporter packs MUST be reachable through {@link #mark}'s reference patterns —
     * {@code file(assets/...)} strings, {@code .png}/{@code .obj}/{@code .glb}/{@code .gltf} location strings, and the
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

    /**
     * Whether a plain string in an fx tree is a raw asset location the pack has to carry: a texture or a
     * runtime-parsed model file ({@code IModelSource}'s file-backed implementations address their file by
     * a location that KEEPS its extension, which is exactly what makes them recognisable here). Model
     * {@code .json} is deliberately absent — those are addressed without an extension and resolve through
     * the bakery, so there is nothing to match on.
     */
    static boolean isPackableAsset(String value) {
        return value.endsWith(".png") || value.endsWith(".obj")
                || value.endsWith(".glb") || value.endsWith(".gltf");
    }

    private static void markString(String value, FileSystem zip, Set<String> referenced, Set<String> visited) {
        if (isPackableAsset(value)) {
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
