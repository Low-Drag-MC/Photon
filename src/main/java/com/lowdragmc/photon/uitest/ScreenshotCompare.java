package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.photon.Photon;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Reads a scenario's own screenshots back off disk so a check can assert on them. The harness writes
 * captures for a human to look at; a regression that only shows up in the picture needs a number.
 *
 * <p>Files are named {@code <step>_<name>.png} under {@code build/ldlib2-uitest/screenshots/<scenario>},
 * so a capture is found by suffix and the newest match wins.</p>
 */
@OnlyIn(Dist.CLIENT)
final class ScreenshotCompare {

    private final int width;
    private final int height;
    /** Packed RGB, row-major. */
    private final int[] pixels;

    private ScreenshotCompare(BufferedImage image) {
        this.width = image.getWidth();
        this.height = image.getHeight();
        this.pixels = image.getRGB(0, 0, width, height, null, 0, width);
    }

    @Nullable
    static ScreenshotCompare load(TestContext ctx, String name) {
        // ⚠️ ctx.outDir(), not a relative path: the client's working directory is runs/client, so
        // "build/ldlib2-uitest" from here resolves under that and finds nothing
        var root = ctx.outDir().resolve("screenshots").toFile();
        var file = find(root, name);
        if (file == null) {
            Photon.LOGGER.warn("[uitest] no capture named {} under {}", name, root);
            return null;
        }
        try {
            var image = ImageIO.read(file);
            return image == null ? null : new ScreenshotCompare(image);
        } catch (IOException e) {
            Photon.LOGGER.warn("[uitest] could not read the capture {}", file, e);
            return null;
        }
    }

    @Nullable
    private static File find(File root, String name) {
        var directories = root.listFiles(File::isDirectory);
        if (directories == null) return null;
        File newest = null;
        for (var directory : directories) {
            var matches = directory.listFiles(f -> f.getName().endsWith(name + ".png"));
            if (matches == null) continue;
            var best = Arrays.stream(matches).max(Comparator.comparingLong(File::lastModified));
            if (best.isPresent() && (newest == null || best.get().lastModified() > newest.lastModified())) {
                newest = best.get();
            }
        }
        return newest;
    }

    boolean sameSizeAs(ScreenshotCompare other) {
        return width == other.width && height == other.height;
    }

    String describe() {
        return width + "x" + height;
    }

    /**
     * ⚠️ Always pass the scene rectangle. The editor draws a live stats box — playback time, CPU time,
     * FPS — inside the scene view, and a whole-window diff measures that counter ticking rather than
     * anything rendered. An earlier version of this compared two FPS readouts and reported agreement.
     *
     * @param threshold per-channel difference, 0..255, below which two pixels count as the same
     */
    Diff diff(ScreenshotCompare other, int threshold, Region region) {
        int count = 0;
        int minX = width, minY = height, maxX = -1, maxY = -1;
        int x0 = Math.max(0, region.left()), x1 = Math.min(width, region.right());
        int y0 = Math.max(0, region.top()), y1 = Math.min(height, region.bottom());
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int a = pixels[y * width + x];
                int b = other.pixels[y * width + x];
                int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
                int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
                int db = Math.abs((a & 0xFF) - (b & 0xFF));
                if (Math.max(dr, Math.max(dg, db)) <= threshold) continue;
                count++;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        int boxWidth = maxX < 0 ? 0 : maxX - minX + 1;
        int boxHeight = maxY < 0 ? 0 : maxY - minY + 1;
        var box = maxX < 0 ? "none"
                : "x[%d..%d] y[%d..%d] %dx%d".formatted(minX, maxX, minY, maxY, boxWidth, boxHeight);
        long area = Math.max(1L, (long) (x1 - x0) * (y1 - y0));
        return new Diff(count, (double) count / area, box, boxWidth, boxHeight);
    }

    record Diff(int count, double fraction, String box, int width, int height) {
    }

    /** A window rectangle, in pixels. */
    record Region(int left, int top, int right, int bottom) {
    }
}
