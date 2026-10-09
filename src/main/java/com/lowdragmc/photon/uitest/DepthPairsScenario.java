package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.render.PhotonDepthPairs;
import com.lowdragmc.photon.client.render.PhotonFramebufferBlit;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/**
 * A pass must use the depth texture it attaches, even when GL reused a closed texture's name (26.1 caches framebuffers
 * by that name — what made {@code kila_gallery} lose a page after {@code kila_export}). Checks {@link PhotonDepthPairs}
 * and logs what the bare colour view does.
 */
@LDLRegisterClient(name = "photon_depth_pairs", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class DepthPairsScenario implements UIScenario {
    private static final int SIZE = 4;
    private static final float BEFORE = 0.75f;
    private static final float CLEARED = 1f;

    private record Round(GpuTexture depth, GpuBuffer readback, AtomicBoolean done, List<AutoCloseable> owned)
            implements AutoCloseable {
        @Override
        public void close() {
            readback.close();
            depth.close();
            for (var closeable : owned) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("photon", "render");
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("through PhotonDepthPairs", ctx -> start(ctx, "pairs", PhotonDepthPairs::color))
                .waitUntil("its depth is read back", ctx -> ctx.<Round>get("pairs").done().get())
                .step("the pass cleared the depth texture it attached", ctx -> {
                    float depth = finish(ctx, "pairs");
                    ctx.check("the reused name's new texture was cleared", depth == CLEARED, CLEARED, depth);
                })
                .step("the bare colour view", ctx -> start(ctx, "bare", (color, depth) -> color))
                .waitUntil("its depth is read back", ctx -> ctx.<Round>get("bare").done().get())
                .step("what the bare view did", ctx -> {
                    float depth = finish(ctx, "bare");
                    // not a check: this is the engine's behaviour, the reason PhotonDepthPairs exists
                    ctx.log("bare colour view: the new texture holds " + depth + (depth == BEFORE
                            ? " — the pass cleared the closed texture behind the reused name" : ""));
                })
                .teardown("free what a failed round left", ctx -> {
                    for (var name : new String[]{"pairs", "bare"}) {
                        Round round = ctx.get(name);
                        if (round != null) round.close();
                        ctx.state().remove(name);
                    }
                });
    }

    /**
     * Draw with a depth texture, close it, take the next texture GL gives the same name, and open a pass on it that
     * clears its depth to {@link #CLEARED} — through {@code colorFor}.
     */
    private static void start(TestContext ctx, String name,
                              BiFunction<GpuTextureView, GpuTextureView, GpuTextureView> colorFor) {
        var device = RenderSystem.getDevice();
        var encoder = device.createCommandEncoder();
        var color = device.createTexture(() -> "depth pairs colour",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, TextureFormat.RGBA8, SIZE, SIZE, 1, 1);
        var colorView = device.createTextureView(color);
        var first = depth("depth pairs first");
        var firstView = device.createTextureView(first);
        clearThrough(colorFor.apply(colorView, firstView), firstView, 0.25);
        int reused = PhotonFramebufferBlit.glId(first);
        firstView.close();
        first.close();

        // GL hands the freed name out again; take textures until one has it
        GpuTexture second = null;
        var others = new ArrayList<GpuTexture>();
        for (int i = 0; i < 64 && second == null; i++) {
            var candidate = depth("depth pairs second");
            if (PhotonFramebufferBlit.glId(candidate) == reused) second = candidate;
            else others.add(candidate);
        }
        others.forEach(GpuTexture::close);
        if (second == null) {
            colorView.close();
            color.close();
        }
        ctx.require("GL gave a new texture the closed one's name", second != null);
        encoder.clearDepthTexture(second, BEFORE);
        var secondView = device.createTextureView(second);
        clearThrough(colorFor.apply(colorView, secondView), secondView, CLEARED);

        var readback = device.createBuffer(() -> "depth pairs readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) SIZE * SIZE * 4);
        var done = new AtomicBoolean();
        encoder.copyTextureToBuffer(second, readback, 0, () -> done.set(true), 0);
        ctx.put(name, new Round(second, readback, done, List.of(secondView, colorView, color)));
    }

    private static GpuTexture depth(String label) {
        return RenderSystem.getDevice().createTexture(() -> label,
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
                TextureFormat.DEPTH32, SIZE, SIZE, 1, 1);
    }

    /** Open a pass on {@code color} + {@code depth} that clears the depth, and draw nothing. */
    private static void clearThrough(GpuTextureView color, GpuTextureView depth, double value) {
        try (var ignored = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "depth pairs",
                color, OptionalInt.of(0), depth, OptionalDouble.of(value))) {
            // the clear happens as the pass opens
        }
    }

    /** The depth the read-back texture holds at its first texel; frees the round. */
    private static float finish(TestContext ctx, String name) {
        try (var round = ctx.<Round>get(name);
             var view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(round.readback(), true, false)) {
            return view.data().getFloat(0);
        } finally {
            ctx.state().remove(name);
        }
    }
}
