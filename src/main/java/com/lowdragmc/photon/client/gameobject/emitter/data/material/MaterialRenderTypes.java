package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.render.PhotonMaterialUniforms;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PhotonRenderTypes;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The material layer's RenderType factory for the standard (non-custom) HDR particle programs —
 * texture / sprite / pixel-art materials and the editor wireframe overlay.
 * <p>
 * Unlike custom-shader materials — whose RenderTypes are per-instance and owned by the material
 * (each carries a live uniform buffer a timeline can drive) — these are keyed purely by VALUE
 * (texture + fragment stage + pipeline state + the {@code PhotonMaterial} values), so identical
 * material configs across emitters share one RenderType and merge into a single draw. This is the
 * 1.21 {@code static Map<String, ShaderInstance>} equivalent: a shared, leak-free value cache
 * (nothing per-instance to release). The compiled pipeline is deduped in {@link PhotonPipelines};
 * the drain's DrawInfo index lives in {@link PhotonRenderTypes}.
 */
public final class MaterialRenderTypes {

    private record Key(Identifier texture, Identifier fragmentShader,
                       PhotonPipelines.ParticlePipelineKey pipelineKey,
                       PhotonMaterialUniforms.Values uniforms) {
    }

    private static final Map<Key, RenderType> CACHE = new ConcurrentHashMap<>();

    private MaterialRenderTypes() {
    }

    public static RenderType hdrParticle(Identifier texture, PhotonPipelines.ParticlePipelineKey pipelineKey) {
        return hdrParticle(texture, pipelineKey, PhotonMaterialUniforms.Values.DEFAULT);
    }

    public static RenderType hdrParticle(Identifier texture, PhotonPipelines.ParticlePipelineKey pipelineKey,
                                         PhotonMaterialUniforms.Values uniforms) {
        return hdrParticle(texture, Photon.id("core/hdr_particle"), pipelineKey, uniforms);
    }

    /** D1: the 1.21 three-program structure — the fragment stage is selected per material
     *  (hdr_particle / pixel_hdr_particle / sprite_hdr_particle share the PhotonMaterial block). */
    public static RenderType hdrParticle(Identifier texture, Identifier fragmentShader,
                                         PhotonPipelines.ParticlePipelineKey pipelineKey,
                                         PhotonMaterialUniforms.Values uniforms) {
        return CACHE.computeIfAbsent(new Key(texture, fragmentShader, pipelineKey, uniforms), key -> {
            var setup = RenderSetup.builder(PhotonPipelines.hdrParticle(key.fragmentShader(), key.pipelineKey()))
                    .withTexture("Sampler0", key.texture())
                    .useLightmap();
            if (key.pipelineKey().blend() != null
                    && key.pipelineKey().mode() == VertexFormat.Mode.QUADS) {
                // D3: translucent quads sort back-to-front at upload (vertices are camera-relative,
                // so vanilla's origin-distance sort is exactly the camera distance); quad-only —
                // strips/triangles keep their emit order
                setup.sortOnUpload();
            }
            var renderType = RenderType.create("photon_hdr_particle", setup.createRenderSetup());
            PhotonMaterialUniforms.associate(renderType, key.uniforms());
            var bloomKey = new PhotonPipelines.ParticlePipelineKey(
                    key.pipelineKey().blend(), key.pipelineKey().blendEquation(),
                    key.pipelineKey().cull(), key.pipelineKey().depthTest(),
                    false, key.pipelineKey().mode(), key.pipelineKey().wireframe());
            PhotonRenderTypes.registerDrawInfo(renderType, new PhotonRenderTypes.PhotonDrawInfo(
                    PhotonPipelines.hdrParticle(key.fragmentShader(), key.pipelineKey()),
                    PhotonPipelines.hdrParticle(key.fragmentShader(), bloomKey),
                    Map.of("Sampler0", key.texture()),
                    key.pipelineKey(), key.fragmentShader(), null, java.util.List.of(), null, null));
            return renderType;
        });
    }

    /** The editor wireframe overlay for one primitive mode: unculled/undepth-tested lines over the
     *  same baked geometry (white texture, vertex colors carry through; discard disabled). */
    public static RenderType wireframe(VertexFormat.Mode mode) {
        return hdrParticle(Photon.id("textures/particle/white.png"),
                PhotonPipelines.ParticlePipelineKey.wireframe(mode),
                new PhotonMaterialUniforms.Values(0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 0));
    }
}
