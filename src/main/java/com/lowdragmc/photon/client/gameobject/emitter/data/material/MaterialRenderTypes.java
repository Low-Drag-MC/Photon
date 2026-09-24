package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.mojang.blaze3d.PrimitiveTopology;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.render.PhotonMaterialUniforms;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PhotonRenderTypes;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;

import java.util.List;
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
        // the fade is a property of the MATERIAL, but it compiles into the fragment stage, so it has to
        // reach the pipeline through the key — and it must agree with the uniforms, or the pipeline would
        // declare a sampler the drain was never asked to bind
        var key0 = pipelineKey.withSoftParticles(uniforms.usesSoftParticles());
        return CACHE.computeIfAbsent(new Key(texture, fragmentShader, key0, uniforms), key -> {
            var setup = RenderSetup.builder(PhotonPipelines.hdrParticle(key.fragmentShader(), key.pipelineKey()))
                    .withTexture("Sampler0", key.texture())
                    .useLightmap();
            // what the drain fills from this frame's capture; demand-driven, so a frame with no soft
            // material in it takes no depth copy at all
            var sceneSamplers = key.uniforms().usesSoftParticles()
                    ? List.of(PhotonShaderCompiler.SCENE_DEPTH) : List.<String>of();
            // placeholders; Photon binds the live captures
            sceneSamplers.forEach(name -> setup.withTexture(name, MissingTextureAtlasSprite.getLocation()));
            // no sortOnUpload(): sorting is RendererSetting.SortMode
            var renderType = RenderType.create("photon_hdr_particle", setup.createRenderSetup());
            PhotonMaterialUniforms.associate(renderType, key.uniforms());
            PhotonRenderTypes.registerDrawInfo(renderType, new PhotonRenderTypes.PhotonDrawInfo(
                    new PhotonRenderTypes.PhotonDrawInfo.Programs(
                            PhotonPipelines.hdrParticle(key.fragmentShader(), key.pipelineKey())),
                    new PhotonRenderTypes.PhotonDrawInfo.Bindings(
                            Map.of("Sampler0", key.texture()), sceneSamplers, null, null),
                    new PhotonRenderTypes.PhotonDrawInfo.InstancedRecipe(
                            key.pipelineKey(), key.fragmentShader(), null)));
            return renderType;
        });
    }

    /** The editor wireframe overlay for one primitive mode: unculled/undepth-tested lines over the
     *  same baked geometry (white texture, vertex colors carry through; discard disabled). */
    public static RenderType wireframe(PrimitiveTopology mode) {
        return hdrParticle(Photon.id("textures/particle/white.png"),
                PhotonPipelines.ParticlePipelineKey.wireframe(mode),
                new PhotonMaterialUniforms.Values(0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 0));
    }
}
