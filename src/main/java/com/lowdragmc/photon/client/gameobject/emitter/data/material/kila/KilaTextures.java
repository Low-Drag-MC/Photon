package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.photon.Photon;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** The textures shipped for KilaMaterial; every module starts on one. */
public final class KilaTextures {
    public static final ResourceLocation WHITE = tex("white");
    public static final ResourceLocation SOFT_CIRCLE = tex("soft_circle");
    public static final ResourceLocation RING = tex("ring");
    public static final ResourceLocation GLOW = tex("glow");
    public static final ResourceLocation NOISE_PERLIN = tex("noise_perlin");
    public static final ResourceLocation NOISE_CLOUD = tex("noise_cloud");
    public static final ResourceLocation NOISE_CELLS = tex("noise_cells");
    public static final ResourceLocation NOISE_FLOW = tex("noise_flow");
    public static final ResourceLocation FLOW_SWIRL = tex("flow_swirl");
    public static final ResourceLocation GRADIENT_LINEAR = tex("gradient_linear");
    public static final ResourceLocation GRADIENT_RADIAL = tex("gradient_radial");
    public static final ResourceLocation TRAIL = tex("trail");
    public static final ResourceLocation SLASH = tex("slash");
    public static final ResourceLocation RUNE_CIRCLE = tex("rune_circle");
    public static final ResourceLocation SPARK = tex("spark");
    public static final ResourceLocation NORMAL_NOISE = tex("normal_noise");
    public static final ResourceLocation MATCAP_GLASS = tex("matcap_glass");
    /** six-way lightmaps of one smoke puff, Unity's layout: right, top, back + alpha */
    public static final ResourceLocation SMOKE6_POS = tex("smoke6_pos");
    /** left, bottom, front */
    public static final ResourceLocation SMOKE6_NEG = tex("smoke6_neg");
    /** a 4 x 4 flipbook of a puff turning... */
    public static final ResourceLocation SMOKE_FLIP = tex("smoke_flip");
    /** ...and its motion vectors: rg = 0.5 + 0.5 * the step to the next frame, in uv of one frame */
    public static final ResourceLocation SMOKE_FLIP_MV = tex("smoke_flip_mv");
    /** an 8 x 4 flipbook: a puff per row billowing out and thinning, and its motion vectors */
    public static final ResourceLocation SMOKE_BURST = tex("smoke_burst");
    public static final ResourceLocation SMOKE_BURST_MV = tex("smoke_burst_mv");

    public static final List<ResourceLocation> ALL = List.of(WHITE, SOFT_CIRCLE, RING, GLOW, NOISE_PERLIN, NOISE_CLOUD,
            NOISE_CELLS, NOISE_FLOW, FLOW_SWIRL, GRADIENT_LINEAR, GRADIENT_RADIAL, TRAIL, SLASH, RUNE_CIRCLE, SPARK,
            NORMAL_NOISE, MATCAP_GLASS, SMOKE6_POS, SMOKE6_NEG, SMOKE_FLIP, SMOKE_FLIP_MV, SMOKE_BURST, SMOKE_BURST_MV);

    private KilaTextures() {
    }

    /** The file name without the folder and the extension: {@code soft_circle}. */
    public static String nameOf(ResourceLocation location) {
        var path = location.getPath();
        var name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
    }

    private static ResourceLocation tex(String name) {
        return Photon.id("textures/kila/" + name + ".png");
    }
}
