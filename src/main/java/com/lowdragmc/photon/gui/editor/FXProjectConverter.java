package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.editor.resource.FilePath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.fxpack.FXPackExporter;
import com.lowdragmc.photon.client.fx.fxpack.FXPacks;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns {@code .fx} definitions back into {@link FXProject}s, from a loose file or from inside an {@code .fxpack}.
 * <p>
 * The library resources a packed definition points at ({@code assets/photon_fx/...}) are copied next to the
 * project, into {@code <project>_resources/}, and the references are repointed at the copies. Inside the pack
 * they are shared with its other effects and swept once nothing in the pack uses them, so a project reading them
 * there would break as soon as its effect is removed from the pack, and editing them would mean editing the pack.
 * Raw assets (textures, models, shaders) stay where they are, addressed by location like everywhere else.
 */
@OnlyIn(Dist.CLIENT)
public final class FXProjectConverter {
    private FXProjectConverter() {
    }

    /**
     * @param warnings the references that could not be resolved and were left as they were
     */
    public record Result(FXProject project, List<String> warnings) {
    }

    /** Converts an {@code .fx} file and saves the project; packed references are read from the loaded packs. */
    public static Result convertFile(File fxFile, File projectFile) throws Exception {
        CompoundTag tag;
        try (var in = Files.newInputStream(fxFile.toPath())) {
            tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
        return convert(tag, projectFile, FXProjectConverter::readLoaded);
    }

    /** Converts one fx of a pack file and saves the project, reading only the file itself, mounted or not. */
    public static Result convertPacked(File packFile, ResourceLocation fxId, File projectFile) throws Exception {
        try (var zip = FileSystems.newFileSystem(packFile.toPath())) {
            CompoundTag tag;
            try (var in = Files.newInputStream(FXPacks.entryPath(zip, FXHelper.fileLocation(fxId)))) {
                tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            }
            return convert(tag, projectFile, location -> {
                var entry = FXPacks.entryPath(zip, location);
                return Files.isRegularFile(entry) ? Files.readAllBytes(entry) : null;
            });
        }
    }

    private static Result convert(CompoundTag fxTag, File projectFile, LibrarySource source) throws Exception {
        var tag = FXHelper.applyVersionFixes(fxTag);
        var unpacker = new Unpacker(source, resourceDirectory(projectFile));
        if (tag.contains("resources", Tag.TAG_COMPOUND)) {
            unpacker.warnings.add("the embedded 'resources' section of an old export was dropped, nothing reads it anymore");
        }
        // file-format stamps, not project data: left in, the project would read as modified once opened
        tag.remove("version");
        tag.remove("resources");
        unpacker.rewrite(tag);
        var project = new FXProject();
        project.getFx().deserializeNBT(Platform.getFrozenRegistry(), tag);
        // what opening it does anyway (FXRuntime parents every object whose parent it can't find to its root):
        // saved without it, a definition that never was in the editor reads as modified once it is opened
        var objects = project.getFx().getFxData().objects();
        var ids = new HashSet<UUID>();
        ids.add(FXRuntime.ROOT_UUID);
        objects.forEach(object -> ids.add(object.id()));
        for (var object : objects) {
            if (!ids.contains(object.transform()._getInternalParentID())) {
                object.transform()._setInternalParentID(FXRuntime.ROOT_UUID);
            }
        }
        Files.createDirectories(projectFile.getAbsoluteFile().getParentFile().toPath());
        FXProject.TYPE.saveProjectToFile(project, projectFile);
        return new Result(project, List.copyOf(unpacker.warnings));
    }

    private static File resourceDirectory(File projectFile) {
        return new File(projectFile.getAbsoluteFile().getParentFile(),
                FXFileActions.baseName(projectFile, FXProject.TYPE.getSuffix()) + "_resources");
    }

    @FunctionalInterface
    private interface LibrarySource {
        byte @Nullable [] read(ResourceLocation location) throws IOException;
    }

    /** The first loaded pack carrying the file, which is where LDLib2's pack-file provider reads it from too. */
    private static byte @Nullable [] readLoaded(ResourceLocation location) throws IOException {
        for (var pack : Minecraft.getInstance().getResourceManager().listPacks().toList()) {
            var resource = pack.getResource(PackType.CLIENT_RESOURCES, location);
            if (resource == null) {
                resource = pack.getResource(PackType.SERVER_DATA, location);
            }
            if (resource != null) {
                try (var in = resource.get()) {
                    return in.readAllBytes();
                }
            }
        }
        return null;
    }

    private static final class Unpacker {
        private final LibrarySource source;
        private final File directory;
        private final Map<ResourceLocation, String> unpacked = new HashMap<>();
        private final Set<ResourceLocation> inProgress = new HashSet<>();
        private final List<String> warnings = new ArrayList<>();

        private Unpacker(LibrarySource source, File directory) {
            this.source = source;
            this.directory = directory;
        }

        private void rewrite(CompoundTag tag) throws IOException {
            for (var key : List.copyOf(tag.getAllKeys())) {
                if (tag.get(key) instanceof StringTag string) {
                    var local = unpackReference(string.getAsString());
                    if (local != null) {
                        tag.putString(key, local);
                    }
                } else {
                    rewriteChild(tag.get(key));
                }
            }
        }

        private void rewriteChild(@Nullable Tag child) throws IOException {
            switch (child) {
                case CompoundTag compound -> rewrite(compound);
                case ListTag list -> {
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i) instanceof StringTag string) {
                            var local = unpackReference(string.getAsString());
                            if (local != null) {
                                list.set(i, StringTag.valueOf(local));
                            }
                        } else {
                            rewriteChild(list.get(i));
                        }
                    }
                }
                case null, default -> {
                }
            }
        }

        /** The reference to a local copy replacing a packed library reference, or null for any other string. */
        @Nullable
        private String unpackReference(String value) throws IOException {
            if (!value.startsWith("file(")
                    || !(IResourcePath.parse(value) instanceof FilePath path)
                    || path.location == null
                    || !FXPackExporter.LIBRARY_NAMESPACE.equals(path.location.getNamespace())) {
                return null;
            }
            return unpack(path.location);
        }

        @Nullable
        private String unpack(ResourceLocation location) throws IOException {
            var done = unpacked.get(location);
            if (done != null) return done;
            if (!inProgress.add(location)) return null; // a cycle: the inner reference stays packed
            try {
                var bytes = source.read(location);
                if (bytes == null) {
                    warnings.add("library resource %s is missing, the reference to it was kept".formatted(location));
                    return null;
                }
                var tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));
                // graphs reference subgraphs and textures of their own
                rewrite(tag);
                var path = location.getPath();
                var file = new File(directory, path.substring(path.lastIndexOf('/') + 1));
                Files.createDirectories(directory.toPath());
                NbtIo.write(tag, file.toPath());
                var reference = new FilePath(file).getPathWithType();
                unpacked.put(location, reference);
                return reference;
            } finally {
                inProgress.remove(location);
            }
        }
    }
}
