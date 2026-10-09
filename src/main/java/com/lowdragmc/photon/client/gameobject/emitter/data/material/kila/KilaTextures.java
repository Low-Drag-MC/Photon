package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.photon.Photon;
import net.minecraft.resources.Identifier;

import java.util.List;

/** The textures shipped for KilaMaterial; every module starts on one. */
public final class KilaTextures {
    public static final Identifier WHITE = tex("white");
    public static final Identifier SOFT_CIRCLE = tex("soft_circle");
    public static final Identifier RING = tex("ring");
    public static final Identifier GLOW = tex("glow");
    public static final Identifier NOISE_PERLIN = tex("noise_perlin");
    public static final Identifier NOISE_CLOUD = tex("noise_cloud");
    public static final Identifier NOISE_CELLS = tex("noise_cells");
    public static final Identifier NOISE_FLOW = tex("noise_flow");
    public static final Identifier FLOW_SWIRL = tex("flow_swirl");
    public static final Identifier GRADIENT_LINEAR = tex("gradient_linear");
    public static final Identifier GRADIENT_RADIAL = tex("gradient_radial");
    public static final Identifier TRAIL = tex("trail");
    public static final Identifier SLASH = tex("slash");
    public static final Identifier RUNE_CIRCLE = tex("rune_circle");
    public static final Identifier SPARK = tex("spark");
    public static final Identifier NORMAL_NOISE = tex("normal_noise");
    public static final Identifier MATCAP_GLASS = tex("matcap_glass");
    /** six-way lightmaps of one smoke puff, Unity's layout: right, top, back + alpha */
    public static final Identifier SMOKE6_POS = tex("smoke6_pos");
    /** left, bottom, front */
    public static final Identifier SMOKE6_NEG = tex("smoke6_neg");
    /** a 4 x 4 flipbook of a puff turning... */
    public static final Identifier SMOKE_FLIP = tex("smoke_flip");
    /** ...and its motion vectors: rg = 0.5 + 0.5 * the step to the next frame, in uv of one frame */
    public static final Identifier SMOKE_FLIP_MV = tex("smoke_flip_mv");
    /** an 8 x 4 flipbook: a puff per row billowing out and thinning, and its motion vectors */
    public static final Identifier SMOKE_BURST = tex("smoke_burst");
    public static final Identifier SMOKE_BURST_MV = tex("smoke_burst_mv");

    public static final List<Identifier> ALL = List.of(WHITE, SOFT_CIRCLE, RING, GLOW, NOISE_PERLIN, NOISE_CLOUD,
            NOISE_CELLS, NOISE_FLOW, FLOW_SWIRL, GRADIENT_LINEAR, GRADIENT_RADIAL, TRAIL, SLASH, RUNE_CIRCLE, SPARK,
            NORMAL_NOISE, MATCAP_GLASS, SMOKE6_POS, SMOKE6_NEG, SMOKE_FLIP, SMOKE_FLIP_MV, SMOKE_BURST, SMOKE_BURST_MV);

    private KilaTextures() {
    }

    /** The file name without the folder and the extension: {@code soft_circle}. */
    public static String nameOf(Identifier location) {
        var path = location.getPath();
        var name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
    }

    private static Identifier tex(String name) {
        return Photon.id("textures/kila/" + name + ".png");
    }
}
