package com.lowdragmc.photon.client.fx.fxpack;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link FXPacks#rewrite} against a pack held open the way a mounted one is: vanilla's {@code FilePackResources}
 * keeps a {@link ZipFile} on it from the reload that mounted it until the next one.
 */
class FXPacksRewriteTest {
    @TempDir
    Path dir;

    @Test
    void createsThePackAndSweepsWhatNothingReferences() throws IOException {
        var pack = dir.resolve("new.fxpack").toFile();
        FXPacks.rewrite(pack, zip -> {
            write(zip.getPath("pack.mcmeta"), "{}".getBytes());
            write(zip.getPath("assets/new/fx/a.fx"), emptyFx());
            write(zip.getPath("assets/new/textures/orphan.png"), new byte[]{1});
        }, () -> {});

        assertEquals(List.of("assets/new/fx/a.fx", "pack.mcmeta"), entries(pack));
        assertNoLeftovers();
    }

    @Test
    void replacesAHeldPackOnceReleased() throws IOException {
        var pack = createPack("held.fxpack", "assets/held/fx/a.fx");
        var held = new ZipFile(pack);
        var releases = new AtomicInteger();
        FXPacks.rewrite(pack, zip -> write(zip.getPath("assets/held/fx/b.fx"), emptyFx()), () -> {
            releases.incrementAndGet();
            close(held);
        });

        assertEquals(List.of("assets/held/fx/a.fx", "assets/held/fx/b.fx", "pack.mcmeta"), entries(pack));
        // before the move, so Windows lets it happen, and after it, so a holder elsewhere reads the new file
        assertEquals(2, releases.get());
        assertNoLeftovers();
    }

    @Test
    void aFailedEditLeavesThePackAsItWas() throws IOException {
        var pack = createPack("failing.fxpack", "assets/failing/fx/a.fx");
        var thrown = assertThrows(IOException.class, () -> FXPacks.rewrite(pack, zip -> {
            write(zip.getPath("assets/failing/fx/b.fx"), emptyFx());
            throw new IOException("edit failed");
        }, () -> {}));

        assertEquals("edit failed", thrown.getMessage());
        assertEquals(List.of("assets/failing/fx/a.fx", "pack.mcmeta"), entries(pack));
        assertNoLeftovers();
    }

    @Test
    void aPackNobodyReleasesIsReportedInUse() throws IOException {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "only Windows refuses to replace an open file");
        var pack = createPack("locked.fxpack", "assets/locked/fx/a.fx");
        try (var ignored = new ZipFile(pack)) {
            assertThrows(FXPacks.PackInUseException.class, () -> FXPacks.rewrite(pack,
                    zip -> write(zip.getPath("assets/locked/fx/b.fx"), emptyFx()), () -> {}));
        }

        assertEquals(List.of("assets/locked/fx/a.fx", "pack.mcmeta"), entries(pack));
        assertNoLeftovers();
    }

    @Test
    void aReadOnlyPackIsNotReportedInUse() throws IOException {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "only Windows refuses to replace a read-only file");
        var pack = createPack("readonly.fxpack", "assets/readonly/fx/a.fx");
        assertTrue(pack.setWritable(false));
        try {
            var thrown = assertThrows(IOException.class, () -> FXPacks.rewrite(pack,
                    zip -> write(zip.getPath("assets/readonly/fx/b.fx"), emptyFx()), () -> {}));
            assertFalse(thrown instanceof FXPacks.PackInUseException, thrown.toString());
        } finally {
            assertTrue(pack.setWritable(true));
        }

        assertEquals(List.of("assets/readonly/fx/a.fx", "pack.mcmeta"), entries(pack));
        assertNoLeftovers();
    }

    @Test
    void removingFromAPackThatIsGoneFailsInsteadOfCreatingOne() {
        var pack = dir.resolve("gone.fxpack").toFile();
        assertThrows(NoSuchFileException.class,
                () -> FXPacks.removeFx(pack, Identifier.fromNamespaceAndPath("gone", "a")));
        assertFalse(pack.exists());
    }

    /** JDK 25 no longer asks Windows when a file is renamed onto itself, so the probe renames it aside and back. */
    @Test
    void aHeldPackIsLockedAndTheProbeLeavesItWhereItWas() throws IOException {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "only Windows refuses to move an open file");
        var pack = createPack("probed.fxpack", "assets/probed/fx/a.fx");
        try (var ignored = new ZipFile(pack)) {
            assertTrue(FXPacks.isFileLocked(pack));
        }
        assertFalse(FXPacks.isFileLocked(pack));
        assertEquals(List.of("assets/probed/fx/a.fx", "pack.mcmeta"), entries(pack));
        assertNoLeftovers();
    }

    @Test
    void aProbeLeftBehindIsPutBack() throws IOException {
        var pack = createPack("stranded.fxpack", "assets/stranded/fx/a.fx");
        var probe = dir.resolve("stranded.fxpack.lockprobe");
        Files.move(pack.toPath(), probe);
        FXPacks.deleteLeftovers(dir.toFile());
        assertEquals(List.of("assets/stranded/fx/a.fx", "pack.mcmeta"), entries(pack));
        assertNoLeftovers();
    }

    @Test
    void aReadOnlyCopyLeftBehindIsDeleted() throws IOException {
        var pack = createPack("crashed.fxpack", "assets/crashed/fx/a.fx");
        var copy = dir.resolve("crashed.fxpack" + FXPacks.COPY_SUFFIX);
        Files.copy(pack.toPath(), copy);
        assertTrue(copy.toFile().setWritable(false));
        FXPacks.deleteLeftovers(dir.toFile());
        assertNoLeftovers();
    }

    @Test
    void aProbeBesideANewerPackIsLeftAlone() throws IOException {
        var pack = createPack("rewritten.fxpack", "assets/rewritten/fx/new.fx");
        var probe = dir.resolve("rewritten.fxpack.lockprobe");
        Files.copy(createPack("old.fxpack", "assets/rewritten/fx/old.fx").toPath(), probe);
        FXPacks.deleteLeftovers(dir.toFile());
        assertEquals(List.of("assets/rewritten/fx/new.fx", "pack.mcmeta"), entries(pack));
        assertTrue(Files.isRegularFile(probe), "an older copy is the author's to throw away");
    }

    private File createPack(String name, String fxEntry) throws IOException {
        var pack = dir.resolve(name);
        try (var zip = FileSystems.newFileSystem(pack, Map.of("create", "true"))) {
            write(zip.getPath("pack.mcmeta"), "{}".getBytes());
            write(zip.getPath(fxEntry), emptyFx());
        }
        return pack.toFile();
    }

    private static void write(Path path, byte[] bytes) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, bytes);
    }

    /** gc reads every fx entry, so they have to be real (compressed) NBT. */
    private static byte[] emptyFx() throws IOException {
        var out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(new CompoundTag(), out);
        return out.toByteArray();
    }

    private static List<String> entries(File pack) throws IOException {
        try (var zip = new ZipFile(pack)) {
            return zip.stream().filter(entry -> !entry.isDirectory()).map(entry -> entry.getName()).sorted().toList();
        }
    }

    private void assertNoLeftovers() throws IOException {
        try (var files = Files.list(dir)) {
            var leftovers = files.map(path -> path.getFileName().toString())
                    .filter(name -> !name.endsWith(FXPacks.SUFFIX)).toList();
            assertEquals(List.of(), leftovers);
        }
    }

    private static void close(ZipFile zip) {
        try {
            zip.close();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
