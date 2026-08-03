package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;

/**
 * Photon's own copy of Minecraft's {@code Globals} uniform block, substituted for the engine's while
 * Photon draws for a view that runs on its own clock.
 * <p>
 * <b>Why substitution rather than a new uniform.</b> A shader asks for {@code GameTime}; which clock that
 * means is the HOST's business, not the shader author's — 1.21 said exactly this by making
 * {@code setShaderGameTime} a host call. Rewriting shaders to read some Photon-specific name would push a
 * semantic decision into every shader and into the format converter, and would still miss KilaGraph's
 * {@code KG_McGlobals} node (which binds this very buffer). Swapping the buffer at
 * {@code RenderSystem.setGlobalSettingsUniform} — the single place everything resolves it from,
 * {@code bindDefaultUniforms} and KilaGraph alike — costs no shader change at all.
 * <p>
 * <b>Scope.</b> Only around Photon's own draws, and only while a view published a clock
 * ({@link PhotonTime}); in the world nothing is substituted and the engine's own buffer stays bound, so
 * world rendering is bit-for-bit unchanged. {@link #substitute()}/{@link #restore()} must bracket in a
 * {@code finally} — leaving Photon's buffer bound would give the rest of the frame a frozen clock.
 * <p>
 * The contents come from {@code GlobalSettingsUniformMixin}, which records the arguments the engine
 * passes each frame — so every field except the clock is the engine's own value, never a guess.
 * Render thread only.
 */
public final class PhotonGlobals {

    /** Exactly the fields we re-emit — the engine's own values for everything the clock isn't. */
    private record Inputs(int width, int height, double glintAlpha,
                          int menuBlurRadius, Vec3 cameraPos, boolean useRgss) {
    }

    @Nullable
    private static Inputs lastInputs;
    @Nullable
    private static GpuBuffer buffer;
    @Nullable
    private static ByteBuffer staging;
    /** The engine's buffer, held while ours is bound. */
    @Nullable
    private static GpuBuffer displaced;
    /** What the current contents were built from — a paused timeline re-binds the same bytes every
     *  frame, and that is precisely the state this class exists to serve. */
    @Nullable
    private static Inputs uploadedInputs;
    private static float uploadedTime = Float.NaN;

    private PhotonGlobals() {
    }

    /** Called from the mixin with the engine's own per-frame inputs. */
    public static void capture(int width, int height, double glintAlpha,
                               int menuBlurRadius, Vec3 cameraPos, boolean useRgss) {
        lastInputs = new Inputs(width, height, glintAlpha, menuBlurRadius, cameraPos, useRgss);
    }

    /**
     * Bind Photon's copy — carrying the current view's clock — in place of the engine's, if this view
     * published one. Returns whether it did, so the caller knows a {@link #restore()} is owed; a
     * {@code false} means everything stays exactly as the engine left it.
     */
    public static boolean substitute() {
        RenderSystem.assertOnRenderThread();
        var inputs = lastInputs;
        if (inputs == null || !PhotonTime.hasOverride()) {
            return false;
        }
        var current = RenderSystem.getGlobalSettingsUniform();
        if (current == null) {
            return false; // nothing bound yet this session — nothing to substitute for
        }
        displaced = current;
        RenderSystem.setGlobalSettingsUniform(upload(inputs));
        return true;
    }

    /** Put the engine's buffer back. Idempotent. */
    public static void restore() {
        if (displaced != null) {
            RenderSystem.setGlobalSettingsUniform(displaced);
            displaced = null;
        }
    }

    /** Rewrite our copy: the engine's own values, with {@code GameTime} on the view's clock. */
    private static GpuBuffer upload(Inputs inputs) {
        if (buffer == null) {
            buffer = RenderSystem.getDevice().createBuffer(() -> "Photon Globals UBO",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, GlobalSettingsUniform.UBO_SIZE);
        }
        if (staging == null) {
            staging = MemoryUtil.memCalloc(GlobalSettingsUniform.UBO_SIZE);
        }
        var time = PhotonTime.dayFraction();
        if (inputs.equals(uploadedInputs) && time == uploadedTime) {
            return buffer;
        }
        uploadedInputs = inputs;
        uploadedTime = time;
        int cameraX = Mth.floor(inputs.cameraPos().x);
        int cameraY = Mth.floor(inputs.cameraPos().y);
        int cameraZ = Mth.floor(inputs.cameraPos().z);
        var bytes = staging;
        bytes.clear();
        {
            // member order mirrors GlobalSettingsUniform.update / assets/minecraft/shaders/include/globals.glsl
            Std140Builder.intoBuffer(bytes)
                    .putIVec3(cameraX, cameraY, cameraZ)
                    .putVec3((float) (cameraX - inputs.cameraPos().x),
                            (float) (cameraY - inputs.cameraPos().y),
                            (float) (cameraZ - inputs.cameraPos().z))
                    .putVec2(inputs.width(), inputs.height())
                    .putFloat((float) inputs.glintAlpha())
                    .putFloat(time) // the ONE field this whole class exists to change
                    .putInt(inputs.menuBlurRadius())
                    .putInt(inputs.useRgss() ? 1 : 0);
            bytes.position(GlobalSettingsUniform.UBO_SIZE).rewind();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), bytes);
        }
        return buffer;
    }
}
