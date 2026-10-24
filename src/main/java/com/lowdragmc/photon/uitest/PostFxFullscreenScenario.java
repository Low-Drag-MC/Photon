package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.postfx.PhotonPostFX;
import net.minecraft.client.CloudStatus;

/**
 * Post effects cover the whole frame through both pass kinds (#66: a fullscreen graph drew only the top-right
 * quadrant): invert (a graph) inverts every quadrant, grayscale (a core shader) greys every one.
 */
@LDLRegisterClient(name = "photon_postfx_fullscreen", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class PostFxFullscreenScenario implements UIScenario {
    /** Per channel, how far a quadrant's mean and its inverted mean may miss adding up to 255. */
    private static final double TOLERANCE = 20;
    private static final String[] QUADRANTS = {"top left", "top right", "bottom left", "bottom right"};

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("photon", "render", "postfx");
    }

    @Override
    public void define(ScenarioBuilder s) {
        // nothing may move between the shots, and the GUI is not post-processed
        s.server("no animals", sc -> NoMobs.claim(sc.player()))
                .step("a still frame", ctx -> {
                    LightTestConfig.set(ctx.mc().options.cloudStatus(), CloudStatus.OFF);
                    LightTestConfig.set(ctx.mc().options.bobView(), false);
                    HiddenGui.hide(ctx.mc());
                })
                .frames(20)
                .screenshot("plain")
                .step("invert", ctx -> PhotonPostFX.setTestEffect(PhotonPostFX.parsePath("invert"), 1f))
                .frames(20)
                .screenshot("inverted")
                .step("every quadrant is inverted", PostFxFullscreenScenario::check)
                .step("grayscale", ctx -> PhotonPostFX.setTestEffect(PhotonPostFX.parsePath("grayscale"), 1f))
                .frames(20)
                .screenshot("grayscale")
                .step("every quadrant is grey", PostFxFullscreenScenario::checkGrey)
                .teardown("effect off", ctx -> {
                    PhotonPostFX.clearTestEffect();
                    HiddenGui.restore(ctx.mc());
                    LightTestConfig.restore();
                })
                .teardownServer("animals back", sc -> NoMobs.release(sc.player()));
    }

    private static ScreenshotCompare.Region quadrant(ScreenshotCompare.Region whole, int i) {
        int midX = whole.right() / 2, midY = whole.bottom() / 2;
        int left = i % 2 == 0 ? 0 : midX, top = i < 2 ? 0 : midY;
        return new ScreenshotCompare.Region(left, top, left + midX, top + midY);
    }

    private static void checkGrey(TestContext ctx) {
        var plain = ScreenshotCompare.load(ctx, "plain");
        var grey = ScreenshotCompare.load(ctx, "grayscale");
        ctx.require("both shots exist", plain != null && grey != null && plain.sameSizeAs(grey));
        for (int i = 0; i < 4; i++) {
            var region = quadrant(grey.whole(), i);
            var a = plain.mean(region);
            var b = grey.mean(region);
            double spread = Math.max(Math.abs(b[0] - b[1]), Math.max(Math.abs(b[1] - b[2]), Math.abs(b[0] - b[2])));
            // not black: an undrawn quadrant is (0, 0, 0)
            ctx.check("the " + QUADRANTS[i] + " quadrant is grey", spread < 3 && b[0] > (a[0] + a[1] + a[2]) / 6,
                    "r = g = b, lit", "plain %.0f %.0f %.0f, grayscale %.0f %.0f %.0f"
                            .formatted(a[0], a[1], a[2], b[0], b[1], b[2]));
        }
    }

    private static void check(TestContext ctx) {
        var plain = ScreenshotCompare.load(ctx, "plain");
        var inverted = ScreenshotCompare.load(ctx, "inverted");
        ctx.require("both shots exist", plain != null && inverted != null && plain.sameSizeAs(inverted));
        for (int i = 0; i < 4; i++) {
            var region = quadrant(plain.whole(), i);
            var a = plain.mean(region);
            var b = inverted.mean(region);
            double miss = 0;
            for (int c = 0; c < 3; c++) miss = Math.max(miss, Math.abs(a[c] + b[c] - 255));
            ctx.check("the " + QUADRANTS[i] + " quadrant is inverted", miss < TOLERANCE, "within " + TOLERANCE,
                    "plain %.0f %.0f %.0f, inverted %.0f %.0f %.0f".formatted(a[0], a[1], a[2], b[0], b[1], b[2]));
        }
    }
}
