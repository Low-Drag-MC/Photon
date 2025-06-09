package com.lowdragmc.photon.client;

import com.lowdragmc.lowdraglib2.client.shader.Shaders;
import com.lowdragmc.lowdraglib2.client.shader.management.Shader;
import com.lowdragmc.lowdraglib2.client.shader.management.ShaderProgram;
import com.lowdragmc.photon.Photon;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class PhotonShaders {
    private static Shader CATMULL_ROM;
    private static ShaderProgram CATMULL_ROM_PROGRAM;

    public static void init() {
        CATMULL_ROM = Shaders.load(Shader.ShaderType.COMPUTE, Photon.id("catmull_rom"));
    }

    public static ShaderProgram getCatmullRomProgram() {
        if (CATMULL_ROM_PROGRAM == null) {
            CATMULL_ROM_PROGRAM = new ShaderProgram();
            CATMULL_ROM_PROGRAM.attach(CATMULL_ROM);
        }
        return CATMULL_ROM_PROGRAM;
    }

}
