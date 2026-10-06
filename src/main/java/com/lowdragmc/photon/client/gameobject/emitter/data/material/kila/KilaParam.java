package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

/**
 * Every drivable parameter and the vec4 slot it travels in from the vertex to the fragment stage.
 * ⚠️ MIRRORED IN {@code photon:kila.vsh} / {@code kila.fsh} ({@code kilaP<group>} and the {@code KILA_*} macros).
 */
public enum KilaParam {
    ALPHA(0, 0),
    INTENSITY(0, 1),
    MAIN_OFFSET_U(0, 2),
    MAIN_OFFSET_V(0, 3),
    MAIN_FRAME(1, 0),
    LAYER_STRENGTH(1, 1),
    LAYER_OFFSET_U(1, 2),
    LAYER_OFFSET_V(1, 3),
    MASK1_OFFSET_U(2, 0),
    MASK1_OFFSET_V(2, 1),
    MASK2_OFFSET_U(2, 2),
    MASK2_OFFSET_V(2, 3),
    DISSOLVE_PROGRESS(3, 0),
    DISSOLVE_OFFSET_U(3, 1),
    DISSOLVE_OFFSET_V(3, 2),
    DISSOLVE_EDGE(3, 3),
    DISTORT_STRENGTH(4, 0),
    RAMP_OFFSET(4, 1),
    FRESNEL_STRENGTH(4, 2),
    VERTEX_OFFSET_STRENGTH(4, 3),
    SCREEN_STRENGTH(5, 0),
    DISPERSION_AMOUNT(5, 1),
    TWIRL_STRENGTH(5, 2),
    WAVE_AMPLITUDE(5, 3),
    CONTACT_STRENGTH(6, 0),
    CONTACT_WIDTH(6, 1),
    /** the particle's random number, which a randomised flipbook picks its row and start from */
    FLIPBOOK_SEED(6, 2),
    LAYER2_STRENGTH(6, 3);

    public static final int GROUPS = 7;

    public final int group;
    public final int component;

    KilaParam(int group, int component) {
        this.group = group;
        this.component = component;
    }
}
