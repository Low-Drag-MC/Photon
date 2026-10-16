package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.LitParticles;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** The modules that are a handful of uniforms each. */
@OnlyIn(Dist.CLIENT)
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
            layout.define("KILA_CAMERA_FADE").vec4("KilaCameraFade", 0.5f, 2, 0, 0);
        }

        @Override
        public void apply(ShaderInstance shader, MaterialContext context, float[] scratch) {
            shader.safeGetUniform("KilaCameraFade").set(near, range, 0f, 0f);
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
            layout.define("KILA_LIT").vec4("LitParticleParams").ivec4("PhotonClusterInfo")
                    .vec4("PhotonClusterView", 0.5f, 1, 0, 0).ivec4("PhotonVisibilityInfo").mat4("PhotonViewInverse")
                    .sampler("PhotonLightData").sampler("PhotonClusterGrid").sampler("PhotonClusterIndices")
                    .sampler("PhotonVisibilityAtlas");
        }

        @Override
        public void apply(ShaderInstance shader, MaterialContext context, float[] scratch) {
            LitParticles.bind(shader, context, intensity);
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
            layout.define("KILA_COLOR_ADJUST").vec4("KilaColorAdjust", 0, 1, 1, 1).vec4("KilaPosterize");
        }

        @Override
        public void apply(ShaderInstance shader, MaterialContext context, float[] scratch) {
            shader.safeGetUniform("KilaColorAdjust").set((float) Math.toRadians(hue), saturation, contrast, brightness);
            shader.safeGetUniform("KilaPosterize").set((float) posterize, 0f, 0f, 0f);
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
            layout.define("KILA_PIXELATE").vec4("KilaPixelate", 16, 0, 0, 0);
        }

        @Override
        public void apply(ShaderInstance shader, MaterialContext context, float[] scratch) {
            shader.safeGetUniform("KilaPixelate").set((float) Math.max(pixels, 1), 0f, 0f, 0f);
        }

        @Override
        public void buildModuleConfigurator(ConfiguratorGroup group) {
            KilaUI.integer(group, "kila.pixelate.pixels", () -> pixels, v -> pixels = v, 16, 1, 1024);
        }
    }
}
