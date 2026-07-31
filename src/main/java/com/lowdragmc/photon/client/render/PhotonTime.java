package com.lowdragmc.photon.client.render;

import net.minecraft.client.Minecraft;

/**
 * The clock Photon's shaders run on.
 * <p>
 * In the world it is the game's own, so nothing changes there. In the EDITOR it is the timeline's —
 * which is the point: 1.21 called {@code RenderSystem.setShaderGameTime(getRealTime(), isPlaying ?
 * partialTicks : 0)}, so time-driven shaders followed the playhead and pausing froze them. 26.1 removed
 * that call, so Photon publishes the clock here instead and the consumers read it:
 * {@link PhotonGlobals} (which substitutes {@code Globals.GameTime} for Photon's draws) and
 * KilaGraph's own {@code KG_Globals} (through its host time override).
 * <p>
 * <b>Shaders are not involved.</b> They keep reading {@code GameTime}; which clock that names is the
 * host's decision, not theirs — so no shader and no format conversion changes.
 * <p>
 * The override is scoped to one view's render ({@code PhotonParticleManager.render} → {@code afterRender}),
 * the same scope the viewport/screen-size overrides use. Render thread only.
 */
public final class PhotonTime {

    private static final float TICKS_PER_DAY = 24000f;

    /** The view-scoped clock in ticks, or negative when the world's own time applies. */
    private static float overrideTicks = -1f;

    private PhotonTime() {
    }

    /** Publish a view's own clock (the editor timeline's, already frozen when it is paused). */
    public static void setOverride(float ticks) {
        overrideTicks = Math.max(0f, ticks);
    }

    public static void clearOverride() {
        overrideTicks = -1f;
    }

    /** Whether a view published its own clock — i.e. whether anything needs substituting at all. */
    public static boolean hasOverride() {
        return overrideTicks >= 0f;
    }

    /** Ticks on the current clock — the timeline's while a view publishes one, else the world's. */
    public static float ticks() {
        if (overrideTicks >= 0f) {
            return overrideTicks;
        }
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null) {
            return 0f;
        }
        return (level.getGameTime() % (long) TICKS_PER_DAY)
                + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }

    /** The normalized day fraction {@code Globals.GameTime} carries, on the current clock. */
    public static float dayFraction() {
        return (ticks() % TICKS_PER_DAY) / TICKS_PER_DAY;
    }
}
