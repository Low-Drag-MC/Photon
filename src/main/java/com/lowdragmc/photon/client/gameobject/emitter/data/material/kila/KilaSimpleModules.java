package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;

/** The modules that are a handful of uniforms each. */
public final class KilaSimpleModules {
    private KilaSimpleModules() {
    }

    /** Fades the particle out as the camera comes close, so it never covers the screen. */
    public static class CameraFade extends KilaModule {
        @Persisted
        public float near = 0.5f;
        @Persisted
        public float range = 2;

        @Override
        public String id() {
            return "camera_fade";
        }

        @Override
        public Category category() {
            return Category.ALPHA;
        }

        @Override
        public void declare(KilaLayout.Builder layout) {
            // x = distance where the fade starts, y = its length
            layout.define("KILA_CAMERA_FADE").vec4("KilaCameraFade", 0.5f, 2, 0, 0);
        }

        @Override
        public void apply(KilaValues values, boolean preview, float[] scratch) {
            values.set("KilaCameraFade", near, range, 0f, 0f);
        }

        @Override
        public void buildModuleConfigurator(ConfiguratorGroup group) {
            KilaUI.number(group, "kila.camera_fade.near", () -> near, v -> near = v, 0.5f, 0, 64);
            KilaUI.number(group, "kila.camera_fade.range", () -> range, v -> range = v, 2, 0.01f, 64);
        }
    }

    /** Dynamic lights light the particle — the same as {@code TextureMaterial}'s Lit Particles. */
    public static class Lit extends KilaModule {
        @Persisted
        public float intensity = 1;

        @Override
        public String id() {
            return "lit";
        }

        @Override
        public Category category() {
            return Category.LIGHTING;
        }

        @Override
        public void declare(KilaLayout.Builder layout) {
            // x = how strongly the lights reach it
            layout.lights().vec4("KilaLit", 1, 0, 0, 0);
        }

        @Override
        public void apply(KilaValues values, boolean preview, float[] scratch) {
            values.set("KilaLit", intensity, 0f, 0f, 0f);
        }

        @Override
        public void buildModuleConfigurator(ConfiguratorGroup group) {
            KilaUI.number(group, "kila.lit.intensity", () -> intensity, v -> intensity = v, 1, 0, 16);
        }
    }

    public static class ColorAdjust extends KilaModule {
        /** degrees */
        @Persisted
        public float hue;
        @Persisted
        public float saturation = 1;
        @Persisted
        public float contrast = 1;
        @Persisted
        public float brightness = 1;
        /** levels per channel; below 2 is off */
        @Persisted
        public int posterize;

        @Override
        public String id() {
            return "color_adjust";
        }

        @Override
        public Category category() {
            return Category.COLOR;
        }

        @Override
        public void declare(KilaLayout.Builder layout) {
            layout.define("KILA_COLOR_ADJUST")
                    // x = hue shift (radians), y = saturation, z = contrast, w = brightness
                    .vec4("KilaColorAdjust", 0, 1, 1, 1)
                    // x = levels per channel (below 2 = off)
                    .vec4("KilaPosterize");
        }

        @Override
        public void apply(KilaValues values, boolean preview, float[] scratch) {
            values.set("KilaColorAdjust", (float) Math.toRadians(hue), saturation, contrast, brightness);
            values.set("KilaPosterize", (float) posterize, 0f, 0f, 0f);
        }

        @Override
        public void buildModuleConfigurator(ConfiguratorGroup group) {
            KilaUI.number(group, "kila.color_adjust.hue", () -> hue, v -> hue = v, 0, -180, 180);
            KilaUI.number(group, "kila.color_adjust.saturation", () -> saturation, v -> saturation = v, 1, 0, 4);
            KilaUI.number(group, "kila.color_adjust.contrast", () -> contrast, v -> contrast = v, 1, 0, 4);
            KilaUI.number(group, "kila.color_adjust.brightness", () -> brightness, v -> brightness = v, 1, 0, 16);
            KilaUI.integer(group, "kila.color_adjust.posterize", () -> posterize, v -> posterize = v, 0, 0, 64);
        }
    }

    /** Snaps the mesh uv to a grid — Photon's pixel-art look. */
    public static class Pixelate extends KilaModule {
        @Persisted
        public int pixels = 16;

        @Override
        public String id() {
            return "pixelate";
        }

        @Override
        public Category category() {
            return Category.TEXTURE;
        }

        @Override
        public void declare(KilaLayout.Builder layout) {
            // x = pixels across one uv unit
            layout.define("KILA_PIXELATE").vec4("KilaPixelate", 16, 0, 0, 0);
        }

        @Override
        public void apply(KilaValues values, boolean preview, float[] scratch) {
            values.set("KilaPixelate", (float) Math.max(pixels, 1), 0f, 0f, 0f);
        }

        @Override
        public void buildModuleConfigurator(ConfiguratorGroup group) {
            KilaUI.integer(group, "kila.pixelate.pixels", () -> pixels, v -> pixels = v, 16, 1, 1024);
        }
    }
}
