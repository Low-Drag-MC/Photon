package com.lowdragmc.photon.uitest;

import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.compat.iris.IrisBridge;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.light.LightDebug;
import com.lowdragmc.photon.client.light.dev.LightDemoScene;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Light settings, game options and the shader pack for a scenario. {@code ConfigValue.set} never writes
 * the file, and {@link #restore()} puts the user's values back, so a test run leaves things as it found them.
 */
final class LightTestConfig {
    private static final Map<ModConfigSpec.ConfigValue<?>, Object> ORIGINAL = new LinkedHashMap<>();
    private static final Map<OptionInstance<?>, Object> ORIGINAL_OPTIONS = new LinkedHashMap<>();
    @Nullable
    private static IrisBridge.ShaderPackSelection originalPack;

    private LightTestConfig() {
    }

    static <T> void set(ModConfigSpec.ConfigValue<T> value, T newValue) {
        ORIGINAL.putIfAbsent(value, value.get());
        value.set(newValue);
    }

    static <T> void set(OptionInstance<T> option, T newValue) {
        ORIGINAL_OPTIONS.putIfAbsent(option, option.get());
        option.set(newValue);
    }

    /**
     * Whether {@code shaderpacks/} has this pack, copied in from {@code run/} or {@code runs/client} first: a UI test
     * starts from an empty game directory.
     */
    static boolean hasShaderPack(String pack) {
        var packs = Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks");
        var target = packs.resolve(pack);
        if (Files.isRegularFile(target)) return true;
        var runs = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize().getParent();
        if (runs == null) return false;
        for (var dir : new String[]{"client", "../run"}) {
            var source = runs.resolve(dir).resolve("shaderpacks").resolve(pack).normalize();
            if (!Files.isRegularFile(source)) continue;
            try {
                Files.createDirectories(packs);
                Files.copy(source, target);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    /** Pins the volumetric settings to their defaults, whatever the developer's config says. */
    static void volumetricDefaults() {
        var config = PhotonConfig.INSTANCE;
        set(config.volumetricLights, true);
        set(config.volumetricDensity, 1.0);
        set(config.volumetricForwardScattering, 0.3);
        set(config.volumetricSamples, 4);
        set(config.volumetricWithShaderPacks, true);
    }

    /** {@code null} turns shaders off. */
    static boolean selectShaderPack(@Nullable String pack) {
        if (originalPack == null) originalPack = IrisCompat.shaderPackSelection();
        return IrisCompat.selectShaderPack(pack);
    }

    @SuppressWarnings("unchecked")
    static void restore() {
        ORIGINAL.forEach((value, original) -> ((ModConfigSpec.ConfigValue<Object>) value).set(original));
        ORIGINAL.clear();
        ORIGINAL_OPTIONS.forEach((option, original) -> ((OptionInstance<Object>) option).set(original));
        ORIGINAL_OPTIONS.clear();
        if (originalPack != null) {
            IrisCompat.restoreShaderPack(originalPack);
            originalPack = null;
        }
        LightDebug.reset();
        LightDemoScene.frozenSeconds = -1f;
        LightDemoScene.volumetric = 0f;
        LightDemoScene.fogDensity = 0f;
    }
}
