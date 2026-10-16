package com.lowdragmc.photon.client.light;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Debug state only the dev tools set ({@code /photonlight}, uitests); all off in normal play. */
@OnlyIn(Dist.CLIENT)
public final class LightDebug {
    /** 0 off, 1 light only, 2 normals, 3 shadow mask, 4 estimated albedo, 5 lights per cluster, 6 the haze alone. */
    public static int view;
    /** A small unlit cube at every light. */
    public static boolean markers;

    private LightDebug() {
    }

    public static void reset() {
        view = 0;
        markers = false;
    }
}
