package com.lowdragmc.photon.client.gameobject.particle.renderer;

import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GLCapabilities;

/**
 * The one instancing entry point that is not in the context Minecraft asks for.
 *
 * <p>Minecraft creates a <b>3.2 core, forward-compatible</b> context, and on Windows the NVIDIA
 * driver hands back exactly that ("3.2.0 NVIDIA …"). LWJGL then loads no OpenGL 3.3 function
 * pointers at all — {@code GLCapabilities.glVertexAttribDivisor} is {@code 0} — so a call through
 * {@code GL33.glVertexAttribDivisor} aborts the JVM with "No context is current or a function that is
 * not available in the current context was called". The same driver does expose
 * {@code GL_ARB_instanced_arrays}, whose {@code glVertexAttribDivisorARB} is the identical entry
 * point under the extension's name, so a GPU-instanced emitter works everywhere once the call goes
 * through whichever of the two the context has.
 *
 * <p>Decided once per context (the capabilities object is per thread and per context) and cached on
 * it by identity, which is what makes this free per attribute.
 */
public final class GlInstancing {

    private static GLCapabilities decidedFor;
    private static boolean useArb;

    private GlInstancing() {
    }

    /** {@code glVertexAttribDivisor}, through the core function when the context has it and the ARB one otherwise. */
    public static void vertexAttribDivisor(int index, int divisor) {
        GLCapabilities caps = GL.getCapabilities();
        if (caps != decidedFor) {
            if (caps.OpenGL33) {
                useArb = false;
            } else if (caps.GL_ARB_instanced_arrays) {
                useArb = true;
            } else {
                throw new IllegalStateException("GPU instancing needs OpenGL 3.3 or GL_ARB_instanced_arrays; this context has neither");
            }
            decidedFor = caps;
        }
        if (useArb) {
            ARBInstancedArrays.glVertexAttribDivisorARB(index, divisor);
        } else {
            GL33C.glVertexAttribDivisor(index, divisor);
        }
    }
}
