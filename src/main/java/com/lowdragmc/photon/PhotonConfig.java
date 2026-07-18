package com.lowdragmc.photon;

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
    public final ModConfigSpec.ConfigValue<Boolean> irisShaderCompatibleMode;
    /** Master switch for the custom post-processing chain (requests are dropped when off). */
    public final ModConfigSpec.ConfigValue<Boolean> enableCustomEffects;
    /** R3: the standalone (no-particle) fallback under Iris shader packs — the framebuffer
     *  discovery outside the particle draw is unverified there, so it's opt-in. */
    public final ModConfigSpec.ConfigValue<Boolean> enableCustomEffectsWithIrisShader;
    /** VRAM cap for the pooled post-processing render targets (free targets evict oldest-first). */
    public final ModConfigSpec.ConfigValue<Integer> postFxPoolBudgetMB;

    private PhotonConfig(ModConfigSpec.Builder builder) {
        enableBloom = builder.define("enable_bloom", true);
        bloomMipLevel = builder.defineInRange("bloom_mip_level", 5, 2, 10);
//        bloomMode = builder.defineEnum("bloom_mode", BloomMode.SCATTER, BloomMode.values());
        bloomThreshold = builder.defineInRange("bloom_threshold", 1, 0, 10d);
        bloomIntensity = builder.defineInRange("bloom_intensity", 0.7, 0, 1);

        enableBloomWithIrisShader = builder.define("enable_bloom_with_iris_shader", true);
        irisShaderCompatibleMode = builder.define("iris_shader_compatible_mode", true);

        enableCustomEffects = builder.define("enable_custom_effects", true);
        enableCustomEffectsWithIrisShader = builder.define("enable_custom_effects_with_iris_shader", false);
        postFxPoolBudgetMB = builder.defineInRange("postfx_pool_budget_mb", 256, 16, 4096);
    }
}
