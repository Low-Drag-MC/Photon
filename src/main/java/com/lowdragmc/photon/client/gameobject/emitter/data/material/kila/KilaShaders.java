package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.client.shader.LDProgramDefineManager;
import com.lowdragmc.lowdraglib2.client.shader.LDShaderInstance;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The compiled KilaMaterial programs, one per (layout, render path), shared by every material with that
 * layout. Each material stages its own uniforms before its draw, so sharing never leaks values.
 */
@OnlyIn(Dist.CLIENT)
public final class KilaShaders {
    /** A compiled variant, with the texture unit of each of its layout's physical samplers. */
    public record Variant(LDShaderInstance shader, int[] textureUnits) {
    }

    private static final Map<String, Variant> CACHE = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();
    /** Bumped by {@link #clearAll}, so per-layout caches know their programs were closed. */
    private static int generation;

    public static int generation() {
        return generation;
    }

    private KilaShaders() {
    }

    @Nullable
    public static Variant get(KilaLayout layout, MaterialContext context) {
        RenderSystem.assertOnRenderThread();
        var key = layout.key + "#" + context.getVariantKey();
        var variant = CACHE.get(key);
        if (variant != null || FAILED.contains(key)) return variant;
        var defines = new LinkedHashSet<>(layout.defines);
        defines.addAll(context.getShaderDefines());
        var id = Photon.id("kila/" + Integer.toHexString(key.hashCode()));
        try {
            var shader = LDShaderInstance.create(new JsonProvider(id, layout.json()), id, DefaultVertexFormat.BLOCK, defines);
            if (shader == null) throw new IllegalStateException("no shader json served for " + id);
            var names = shader.getShaderInstanceAccessor().getSamplerNames();
            var units = new int[layout.textures.size()];
            for (var texture : layout.textures) {
                units[texture.index()] = names.indexOf(texture.name());
            }
            variant = new Variant(shader, units);
            CACHE.put(key, variant);
        } catch (Throwable e) {
            Photon.LOGGER.error("Failed to compile KilaMaterial variant {}", key, e);
            FAILED.add(key);
        } finally {
            // create() only clears them on success; a failed compile would leak them into every later one
            defines.forEach(LDProgramDefineManager::removeProgramDefine);
        }
        return variant;
    }

    public static int compiledCount() {
        return CACHE.size();
    }

    /** Every variant is rebuilt from the fresh sources after a resource reload. */
    public static void clearAll() {
        CACHE.values().forEach(variant -> variant.shader().close());
        CACHE.clear();
        FAILED.clear();
        generation++;
    }

    /** Serves the variant's generated JSON; the programs and their imports come from the resource manager. */
    private record JsonProvider(ResourceLocation json, byte[] bytes, PackResources pack) implements ResourceProvider {
        JsonProvider(ResourceLocation id, String json) {
            this(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "shaders/core/" + id.getPath() + ".json"),
                    json.getBytes(StandardCharsets.UTF_8), new GeneratedPack(id.toString()));
        }

        @Override
        public Optional<Resource> getResource(ResourceLocation location) {
            if (location.equals(json)) {
                return Optional.of(new Resource(pack, () -> new ByteArrayInputStream(bytes)));
            }
            return Minecraft.getInstance().getResourceManager().getResource(location);
        }
    }

    /** Just enough of a pack for {@code Resource#sourcePackId()} while the JSON is read. */
    private record GeneratedPack(String id) implements PackResources {
        @Nullable
        @Override
        public IoSupplier<InputStream> getRootResource(String... elements) {
            return null;
        }

        @Nullable
        @Override
        public IoSupplier<InputStream> getResource(PackType packType, ResourceLocation location) {
            return null;
        }

        @Override
        public void listResources(PackType packType, String namespace, String path, ResourceOutput resourceOutput) {
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            return Set.of();
        }

        @Nullable
        @Override
        public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) {
            return null;
        }

        @Override
        public PackLocationInfo location() {
            return new PackLocationInfo(id, Component.literal(id), PackSource.BUILT_IN, Optional.empty());
        }

        @Override
        public void close() {
        }
    }
}
