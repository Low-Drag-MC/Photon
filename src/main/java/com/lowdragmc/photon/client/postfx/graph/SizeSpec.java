package com.lowdragmc.photon.client.postfx.graph;

/**
 * How a pass's output render target is sized:
 * <ul>
 *   <li>{@link Mode#SCREEN_RELATIVE} — the effect chain input (screen) size × {@code scale};</li>
 *   <li>{@link Mode#INPUT_RELATIVE} — an earlier resource's resolved size × {@code scale}
 *       ({@code inputResource} indexes the effect's resource list; ×0.5 chains build the manually
 *       unrolled bloom pyramid);</li>
 *   <li>{@link Mode#ABSOLUTE} — fixed {@code width}×{@code height} pixels.</li>
 * </ul>
 */
public record SizeSpec(Mode mode, float scale, int inputResource, int width, int height) {

    public enum Mode { SCREEN_RELATIVE, INPUT_RELATIVE, ABSOLUTE }

    public static SizeSpec screen(float scale) {
        return new SizeSpec(Mode.SCREEN_RELATIVE, scale, -1, 0, 0);
    }

    public static SizeSpec relativeTo(int inputResource, float scale) {
        return new SizeSpec(Mode.INPUT_RELATIVE, scale, inputResource, 0, 0);
    }

    public static SizeSpec absolute(int width, int height) {
        return new SizeSpec(Mode.ABSOLUTE, 1f, -1, width, height);
    }
}
