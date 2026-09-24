package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom-shader sources after {@link CustomShaderAdapter}, served by {@code ShaderManagerMixin} under
 * {@code photon:adapted/<hash>}. Unchanged pairs keep their own ids.
 */
public final class PhotonShaderSources {

    private static final String PREFIX = "adapted/";

    private record Key(Identifier id, ShaderType type) {
    }

    private static final Map<Key, String> SOURCES = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private PhotonShaderSources() {
    }

    public record Adapted(Identifier vertex, Identifier fragment, Map<Integer, String> tailInputs) {
    }

    public static boolean isAdapted(Identifier id) {
        return id.getNamespace().equals(Photon.MOD_ID) && id.getPath().startsWith(PREFIX);
    }

    @Nullable
    public static String get(Identifier id, ShaderType type) {
        return SOURCES.get(new Key(id, type));
    }

    /** Falls back to the original ids when a source is missing, so the compile reports it as usual. */
    public static Adapted adapt(Identifier vertex, Identifier fragment, int providedLocations,
                                Set<Integer> tailLocations, Set<String> defines) {
        var shaders = Minecraft.getInstance().getShaderManager();
        var vertexSource = shaders.getShader(vertex, ShaderType.VERTEX);
        var fragmentSource = shaders.getShader(fragment, ShaderType.FRAGMENT);
        if (vertexSource == null || fragmentSource == null) {
            return new Adapted(vertex, fragment, Map.of());
        }
        var result = CustomShaderAdapter.adapt(vertexSource, fragmentSource, providedLocations, tailLocations,
                defines);
        if (!result.changed()) {
            return new Adapted(vertex, fragment, result.tailInputs());
        }
        var id = Photon.id(PREFIX + hash(result.vertex() + "\u0000" + result.fragment()));
        SOURCES.put(new Key(id, ShaderType.VERTEX), result.vertex());
        SOURCES.put(new Key(id, ShaderType.FRAGMENT), result.fragment());
        if (REPORTED.add(vertex + "|" + fragment + "|" + String.join(",", result.notes()))) {
            Photon.LOGGER.info("custom shader {} + {} adapted for 26.2 ({})", vertex, fragment,
                    String.join("; ", result.notes()));
        }
        return new Adapted(id, id, result.tailInputs());
    }

    private static String hash(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
