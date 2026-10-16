package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.kilagraph.rendertype.compiler.KGSamplerGl;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector2f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;

import java.util.Objects;

/** One texture a module samples, with the uv chain every slot runs (see {@code kila_uv} in {@code photon:kila_lib.glsl}). */
public class KilaTexture implements IPersistedSerializable {
    /** How a colour slot turns a texel into colour + alpha. ⚠️ ordinals MIRRORED IN {@code kila_color_mode}. */
    public enum ColorMode {
        RGBA,
        RED_AS_ALPHA,
        ALPHA_ONLY,
        LUMINANCE_AS_ALPHA
    }

    /** The one channel a scalar slot (mask, noise) reads. */
    public enum Channel {
        R, G, B, A, LUMINANCE;

        public void weights(float[] out) {
            out[0] = this == R ? 1 : this == LUMINANCE ? 0.2126f : 0;
            out[1] = this == G ? 1 : this == LUMINANCE ? 0.7152f : 0;
            out[2] = this == B ? 1 : this == LUMINANCE ? 0.0722f : 0;
            out[3] = this == A ? 1 : 0;
        }
    }

    public enum Wrap {
        REPEAT, CLAMP, MIRROR,
        /** Clamp, and transparent outside the texture — a slash or beam that slides off instead of smearing. */
        CLIP;

        int gl() {
            return switch (this) {
                case REPEAT -> GL11.GL_REPEAT;
                case MIRROR -> GL14.GL_MIRRORED_REPEAT;
                default -> GL12.GL_CLAMP_TO_EDGE;
            };
        }
    }

    /** Where the uv chain starts. A world plane tiles once per block, so a texture lines up across particles. */
    public enum UvSource {
        MESH(0), SCREEN(FLAG_SCREEN), WORLD_XZ(FLAG_WORLD_XZ), WORLD_XY(FLAG_WORLD_XY), WORLD_ZY(FLAG_WORLD_ZY);

        public final int flag;

        UvSource(int flag) {
            this.flag = flag;
        }

        public boolean isWorld() {
            return this == WORLD_XZ || this == WORLD_XY || this == WORLD_ZY;
        }
    }

    /** A noise computed in the shader instead of the texture: no sampler, nothing to tile. ⚠️ ordinals MIRRORED IN {@code kila_noise}. */
    public enum Noise {
        TEXTURE, SIMPLE, GRADIENT, VORONOI
    }

    // ⚠️ MIRRORED IN photon:kila_lib.glsl (KILA_UV_*)
    public static final int FLAG_POLAR = 1;
    public static final int FLAG_SCREEN = 2;
    public static final int FLAG_CLIP_U = 4;
    public static final int FLAG_CLIP_V = 8;
    public static final int FLAG_WORLD_XZ = 16;
    public static final int FLAG_WORLD_XY = 32;
    public static final int FLAG_WORLD_ZY = 64;

    @Persisted
    public ResourceLocation texture;
    @Persisted
    public ColorMode colorMode = ColorMode.RGBA;
    @Persisted
    public Channel channel = Channel.R;
    @Persisted
    public Vector2f tiling = new Vector2f(1, 1);
    @Persisted
    public Vector2f offset = new Vector2f(0, 0);
    /** uv per second */
    @Persisted
    public Vector2f scroll = new Vector2f(0, 0);
    /** degrees about the uv centre */
    @Persisted
    public float rotation;
    /** degrees per second */
    @Persisted
    public float rotationSpeed;
    @Persisted
    public boolean polar;
    @Persisted
    public Vector2f polarCenter = new Vector2f(0.5f, 0.5f);
    @Persisted
    public float polarRadial = 1;
    @Persisted
    public float polarAngular = 1;
    @Persisted
    public UvSource uvSource = UvSource.MESH;
    @Persisted
    public Noise noise = Noise.TEXTURE;
    /** cells across one uv unit */
    @Persisted
    public float noiseScale = 8;
    /** how fast voronoi cells drift */
    @Persisted
    public float noiseMotion = 1;
    @Persisted
    public Wrap wrapU = Wrap.REPEAT;
    @Persisted
    public Wrap wrapV = Wrap.REPEAT;
    @Persisted
    public boolean nearest;

    public KilaTexture() {
        this(KilaTextures.WHITE);
    }

    public KilaTexture(ResourceLocation texture) {
        this.texture = texture;
    }

    public ResourceLocation getTexture() {
        if (texture == null) texture = KilaTextures.WHITE;
        return texture;
    }

    public int flags() {
        int flags = 0;
        if (polar) flags |= FLAG_POLAR;
        flags |= uvSource.flag;
        if (wrapU == Wrap.CLIP) flags |= FLAG_CLIP_U;
        if (wrapV == Wrap.CLIP) flags |= FLAG_CLIP_V;
        return flags;
    }

    /** Wrap inside one flipbook cell, u in bits 0-1 and v in 2-3: 0 repeat, 1 clamp, 2 mirror. */
    public int cellWrap() {
        return cellWrap(wrapU) | cellWrap(wrapV) << 2;
    }

    private static int cellWrap(Wrap wrap) {
        return switch (wrap) {
            case REPEAT -> 0;
            case MIRROR -> 2;
            default -> 1;
        };
    }

    public boolean isProcedural() {
        return noise != Noise.TEXTURE;
    }

    public boolean usesWorld() {
        return uvSource.isWorld();
    }

    /** Uploads {@code <prefix>ST}, {@code <prefix>Anim} and {@code <prefix>Polar}. */
    public void uploadUv(ShaderInstance shader, String prefix) {
        shader.safeGetUniform(prefix + "ST").set(tiling.x, tiling.y, offset.x, offset.y);
        shader.safeGetUniform(prefix + "Anim").set(scroll.x, scroll.y,
                (float) Math.toRadians(rotation), (float) Math.toRadians(rotationSpeed));
        shader.safeGetUniform(prefix + "Polar").set(polarCenter.x, polarCenter.y, polarRadial, polarAngular);
    }

    public void uploadChannel(ShaderInstance shader, String name, float[] scratch) {
        channel.weights(scratch);
        shader.safeGetUniform(name).set(scratch[0], scratch[1], scratch[2], scratch[3]);
    }

    /** {@code <prefix>Noise} for a procedural slot, else {@code <prefix>Channel}. */
    public void uploadSource(ShaderInstance shader, String prefix, float[] scratch) {
        if (isProcedural()) {
            shader.safeGetUniform(prefix + "Noise").set((float) noise.ordinal(), noiseScale, noiseMotion, 0f);
        } else {
            uploadChannel(shader, prefix + "Channel", scratch);
        }
    }

    public KGSamplerGl.GlSampler glSampler() {
        int filter = nearest ? GL11.GL_NEAREST : GL11.GL_LINEAR;
        return new KGSamplerGl.GlSampler(filter, filter, wrapU.gl(), wrapV.gl());
    }

    /** Two slots with equal keys can share one sampler. */
    public boolean sameSampler(KilaTexture other) {
        return Objects.equals(getTexture(), other.getTexture()) && nearest == other.nearest
                && wrapU.gl() == other.wrapU.gl() && wrapV.gl() == other.wrapV.gl();
    }

    /** What of this slot changes the program variant. Allocation-free: it runs on every draw. */
    public int structureHash() {
        int hash = (isProcedural() ? 1 : 0) | (usesWorld() ? 2 : 0);
        if (isProcedural()) return hash;
        hash = hash * 31 + getTexture().hashCode();
        hash = hash * 31 + (nearest ? 1 : 0);
        hash = hash * 31 + wrapU.gl();
        return hash * 31 + wrapV.gl();
    }

    public KilaTexture tiling(float u, float v) {
        tiling.set(u, v);
        return this;
    }

    public KilaTexture scroll(float u, float v) {
        scroll.set(u, v);
        return this;
    }

    public KilaTexture noise(Noise noise, float scale) {
        this.noise = noise;
        noiseScale = scale;
        return this;
    }

    public KilaTexture wrap(Wrap u, Wrap v) {
        wrapU = u;
        wrapV = v;
        return this;
    }
}
