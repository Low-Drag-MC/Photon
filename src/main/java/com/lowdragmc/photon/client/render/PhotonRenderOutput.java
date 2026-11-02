package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;

import javax.annotation.Nullable;
import java.util.ArrayDeque;

/**
 * The surface Photon draws into right now: an editor scene's target while one is pushed ({@link #push}), otherwise the
 * game's main target. 26.3 has no global output override, so whoever hands Photon a surface names it. Render thread.
 */
public final class PhotonRenderOutput {

    private record Output(GpuTextureView color, @Nullable GpuTextureView depth) {
    }

    private static final ArrayDeque<Output> STACK = new ArrayDeque<>();

    private PhotonRenderOutput() {
    }

    /** Ends a {@link #push}; closing it twice is harmless. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /** Photon draws into {@code color} (and tests against {@code depth}) until the scope closes. Nests. */
    public static Scope push(GpuTextureView color, @Nullable GpuTextureView depth) {
        var output = new Output(color, depth);
        STACK.push(output);
        return new Scope() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) return;
                closed = true;
                STACK.remove(output);
            }
        };
    }

    /** The game's own frame: its main render target. */
    public static Scope world() {
        var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        return push(main.getColorTextureView(), main.getDepthTextureView());
    }

    /** The colour attachment being drawn into, or null when the main target has none. */
    @Nullable
    public static GpuTextureView color() {
        var top = STACK.peek();
        return top != null ? top.color() : Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView();
    }

    /** The depth attachment being drawn into, or null when the target has none. */
    @Nullable
    public static GpuTextureView depth() {
        var top = STACK.peek();
        return top != null ? top.depth() : Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView();
    }

    /**
     * The engine's render-type scissor clamped to the target; a box outside the render area is rejected. Check
     * {@link #isEmpty} before drawing.
     */
    public static ScissorState scissor(int width, int height) {
        var clip = new ScissorState(RenderSystem.getScissorStateForRenderTypeDraws());
        if (!clip.enabled()) {
            return clip;
        }
        int x0 = Math.max(0, clip.x());
        int y0 = Math.max(0, clip.y());
        int x1 = Math.min(width, clip.x() + clip.width());
        int y1 = Math.min(height, clip.y() + clip.height());
        clip.enable(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
        return clip;
    }

    public static boolean isEmpty(ScissorState clip) {
        return clip.enabled() && (clip.width() <= 0 || clip.height() <= 0);
    }
}
