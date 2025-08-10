package com.lowdragmc.photon.client;

import com.lowdragmc.lowdraglib2.client.shader.LDLibShaders;
import com.lowdragmc.lowdraglib2.client.shader.management.Shader;
import com.lowdragmc.lowdraglib2.client.shader.management.ShaderProgram;
import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import lombok.Getter;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;

@OnlyIn(Dist.CLIENT)
public class PhotonShaders {
    private static Shader CATMULL_ROM;
    private static ShaderProgram CATMULL_ROM_PROGRAM;
    @Getter
    private static ShaderInstance HDRParticleShader;
    @Getter
    private static ShaderInstance spriteHDRParticleShader;
    @Getter
    private static ShaderInstance pixelHDRParticleShader;
    @Getter
    private static ShaderInstance brightPassShader;
    @Getter
    private static ShaderInstance downSamplingShader;
    @Getter
    private static ShaderInstance upSamplingShader;
//    @Getter
//    private static ShaderInstance separableBlurShader;
//    @Getter
//    private static ShaderInstance bloomAddPassShader;
//    @Getter
//    private static ShaderInstance bloomScatterPassShader;
    @Getter
    private static ShaderInstance bloomFinalScatterPassShader;

    public static void init() {
        if (LDLibShaders.supportComputeShader()) {
            CATMULL_ROM = LDLibShaders.load(Shader.ShaderType.COMPUTE, Photon.id("catmull_rom"));
        }
    }

    public static ShaderProgram getCatmullRomProgram() {
        if (CATMULL_ROM_PROGRAM == null) {
            CATMULL_ROM_PROGRAM = new ShaderProgram();
            CATMULL_ROM_PROGRAM.attach(CATMULL_ROM);
        }
        return CATMULL_ROM_PROGRAM;
    }

    public static void registerShaders(RegisterShadersEvent registerShadersEvent) {
        var resourceProvider = registerShadersEvent.getResourceProvider();
        try {
            registerShadersEvent.registerShader(new ShaderInstance(resourceProvider,
                            Photon.id("hdr_particle"), DefaultVertexFormat.BLOCK),
                    shaderInstance -> HDRParticleShader = shaderInstance);
            registerShadersEvent.registerShader(new ShaderInstance(resourceProvider,
                            Photon.id("sprite_hdr_particle"), DefaultVertexFormat.BLOCK),
                    shaderInstance -> spriteHDRParticleShader = shaderInstance);
            registerShadersEvent.registerShader(new ShaderInstance(resourceProvider,
                            Photon.id("pixel_hdr_particle"), DefaultVertexFormat.BLOCK),
                    shaderInstance -> pixelHDRParticleShader = shaderInstance);
            registerShadersEvent.registerShader(new ShaderInstance(resourceProvider,
                            Photon.id("bright_pass"), DefaultVertexFormat.POSITION),
                    shaderInstance -> brightPassShader = shaderInstance);
            registerShadersEvent.registerShader(new ShaderInstance(resourceProvider,
                            Photon.id("down_sampling"), DefaultVertexFormat.POSITION),
                    shaderInstance -> downSamplingShader = shaderInstance);
            registerShadersEvent.registerShader(new ShaderInstance(resourceProvider,
                            Photon.id("up_sampling"), DefaultVertexFormat.POSITION),
                    shaderInstance -> upSamplingShader = shaderInstance);
//            registerShadersEvent.registerShader(new ShaderInstance(resourceProvider,
//                            Photon.id("separable_blur"), DefaultVertexFormat.POSITION),
//                    shaderInstance -> separableBlurShader = shaderInstance);
//            registerShadersEvent.registerShader(new ShaderInstance(resourceProvider,
//                            Photon.id("bloom_add_pass"), DefaultVertexFormat.POSITION),
//                    shaderInstance -> bloomAddPassShader = shaderInstance);
//            registerShadersEvent.registerShader(new ShaderInstance(resourceProvider,
//                            Photon.id("bloom_scatter_pass"), DefaultVertexFormat.POSITION),
//                    shaderInstance -> bloomScatterPassShader = shaderInstance);
            registerShadersEvent.registerShader(new ShaderInstance(resourceProvider,
                            Photon.id("bloom_final_scatter_pass"), DefaultVertexFormat.POSITION),
                    shaderInstance -> bloomFinalScatterPassShader = shaderInstance);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
