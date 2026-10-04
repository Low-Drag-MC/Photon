package com.lowdragmc.photon;

import com.lowdragmc.photon.client.compat.iris.IrisCompositeMode;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.FXCompositeMode;
import com.lowdragmc.photon.client.light.ShadowMode;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class PhotonConfig {
    public static final PhotonConfig INSTANCE;
    public static final ModConfigSpec CONFIG_SPEC;

    static {
        Pair<PhotonConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(PhotonConfig::new);
        //Store the resulting values
        INSTANCE = pair.getLeft();
        CONFIG_SPEC = pair.getRight();
    }

    public enum BloomMode {
        ADD,
        SCATTER
    }


    public final ModConfigSpec.ConfigValue<Boolean> enableBloom;
    public final ModConfigSpec.ConfigValue<Integer> bloomMipLevel;
//    public final ModConfigSpec.ConfigValue<BloomMode> bloomMode;
    public final ModConfigSpec.ConfigValue<Double> bloomThreshold;
    public final ModConfigSpec.ConfigValue<Double> bloomIntensity;
    public final ModConfigSpec.ConfigValue<Boolean> enableBloomWithIrisShader;
    /** How Photon hands its FX image back to a shader pack — see {@code IrisCompositeMode}.
     *  AUTO picks per pack; the others are escape hatches for a pack we get wrong. */
    public final ModConfigSpec.ConfigValue<IrisCompositeMode> irisCompositeMode;
    /** Resolve the translucent Photon queue through the pack's {@code gbuffers_particles_translucent}
     *  program instead of {@code gbuffers_particles}. Off by default: on NeoForge 1.21.1 Iris does not
     *  split the vanilla particle pass at all, so that program is not what the engine itself uses. */
    public final ModConfigSpec.ConfigValue<Boolean> irisUseTranslucentParticleProgram;
    /** Default merge strategy for translucent FX — see {@code FXCompositeMode}. {@code LATE} keeps
     *  clouds from painting over FX and water from slicing them; {@code VANILLA} is the pre-2.2.2
     *  behaviour, kept as an escape hatch. Individual emitters may override it. */
    public final ModConfigSpec.ConfigValue<FXCompositeMode> fxCompositeMode;
    /** Master switch for the custom post-processing chain (requests are dropped when off). */
    public final ModConfigSpec.ConfigValue<Boolean> enableCustomEffects;
    /** The custom effect chain under a shader pack. Deliberately a NEW key rather than a new default
     *  for {@code enable_custom_effects_with_iris_shader}: that option gated a fallback that could
     *  not work, so every existing config has it persisted as false, and the option now means
     *  something else entirely (run after the pack's composite/final passes, on the finished frame). */
    public final ModConfigSpec.ConfigValue<Boolean> enableCustomEffectsWithShaderPack;
    /** VRAM cap for the pooled post-processing render targets (free targets evict oldest-first). */
    public final ModConfigSpec.ConfigValue<Integer> postFxPoolBudgetMB;

    public final ModConfigSpec.ConfigValue<Boolean> dynamicLights;
    public final ModConfigSpec.ConfigValue<ShadowMode> lightShadowMode;
    public final ModConfigSpec.ConfigValue<Boolean> lightSoftShadows;
    public final ModConfigSpec.ConfigValue<Integer> lightShadowedLights;
    public final ModConfigSpec.ConfigValue<Double> lightResolution;
    public final ModConfigSpec.ConfigValue<Integer> lightContactShadowSteps;
    public final ModConfigSpec.ConfigValue<Double> lightContactShadowLength;
    public final ModConfigSpec.ConfigValue<Integer> lightScreenShadowSteps;
    public final ModConfigSpec.ConfigValue<Double> lightScreenShadowThickness;
    public final ModConfigSpec.ConfigValue<Double> lightScreenShadowDistance;
    public final ModConfigSpec.ConfigValue<Double> lightVoxelBudgetMs;
    public final ModConfigSpec.ConfigValue<Double> lightAlbedoMax;
    public final ModConfigSpec.ConfigValue<Double> lightShaderPackAmbient;

    private PhotonConfig(ModConfigSpec.Builder builder) {
        enableBloom = builder.define("enable_bloom", true);
        bloomMipLevel = builder.defineInRange("bloom_mip_level", 5, 2, 10);
//        bloomMode = builder.defineEnum("bloom_mode", BloomMode.SCATTER, BloomMode.values());
        bloomThreshold = builder.defineInRange("bloom_threshold", 1.001, 0, 10d);
        bloomIntensity = builder.defineInRange("bloom_intensity", 0.7, 0, 1);

        // Only consulted when the FX layer is composited into the pack's own colour target, where
        // the pack's bloom chain will also process it — leaving this on means both apply. It is
        // ignored (and Photon's bloom always runs) when the layer is held back to after the pack's
        // passes, because nothing else would ever bloom those pixels.
        enableBloomWithIrisShader = builder.define("enable_bloom_with_iris_shader", true);
        irisCompositeMode = builder.defineEnum("iris_composite_mode", IrisCompositeMode.AUTO,
                IrisCompositeMode.values());
        irisUseTranslucentParticleProgram = builder.define("iris_use_translucent_particle_program", false);

        // INHERIT is deliberately not an acceptable value here: it only means something per emitter.
        fxCompositeMode = builder.defineEnum("fx_composite_mode", FXCompositeMode.LATE,
                FXCompositeMode.VANILLA, FXCompositeMode.LATE);

        enableCustomEffects = builder.define("enable_custom_effects", true);
        enableCustomEffectsWithShaderPack = builder.define("enable_custom_effects_with_shader_pack", true);
        postFxPoolBudgetMB = builder.defineInRange("postfx_pool_budget_mb", 256, 16, 4096);

        builder.push("dynamic_lights");
        dynamicLights = light(builder, "enabled").define("enabled", true);
        lightShadowMode = light(builder, "shadow_mode").defineEnum("shadow_mode", ShadowMode.VOXEL);
        lightSoftShadows = light(builder, "soft_shadows").define("soft_shadows", true);
        lightShadowedLights = light(builder, "shadowed_lights").defineInRange("shadowed_lights", 8, 0, 32);
        lightResolution = light(builder, "resolution").defineInRange("resolution", 0.5, 0.25, 1.0);
        lightContactShadowSteps = light(builder, "contact_shadow_steps").defineInRange("contact_shadow_steps", 6, 0, 32);
        lightContactShadowLength = light(builder, "contact_shadow_length").defineInRange("contact_shadow_length", 0.8, 0.1, 4.0);
        lightScreenShadowSteps = light(builder, "screen_shadow_steps").defineInRange("screen_shadow_steps", 24, 1, 128);
        lightScreenShadowThickness = light(builder, "screen_shadow_thickness").defineInRange("screen_shadow_thickness", 1.0, 0.05, 16.0);
        lightScreenShadowDistance = light(builder, "screen_shadow_distance").defineInRange("screen_shadow_distance", 32.0, 1.0, 128.0);
        lightVoxelBudgetMs = light(builder, "voxel_budget_ms").defineInRange("voxel_budget_ms", 2.0, 0.1, 20.0);
        lightAlbedoMax = light(builder, "albedo_max").defineInRange("albedo_max", 0.85, 0.1, 1.0);
        lightShaderPackAmbient = light(builder, "shader_pack_ambient").defineInRange("shader_pack_ambient", 0.06, 0.001, 1.0);
        builder.pop();
    }

    // the screen derives value keys from the leaf name alone, which other sections may reuse
    private static ModConfigSpec.Builder light(ModConfigSpec.Builder builder, String key) {
        return builder.translation("photon.configuration.dynamic_lights." + key);
    }
}
