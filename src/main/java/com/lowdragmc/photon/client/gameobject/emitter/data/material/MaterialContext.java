package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.photon.Photon;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.Set;

@Getter
@Setter
@Accessors(chain = true)
@Data(staticConstructor = "of")
public class MaterialContext {
    public final static MaterialContext NORMAL = new MaterialContext();
    public final static MaterialContext PREVIEW = new MaterialContext().setRenderingPreview(true);
    public final static MaterialContext PARTICLE_INSTANCE = new MaterialContext().setShaderDefine("PARTICLE_INSTANCE");
    public final static MaterialContext PARTICLE_MODEL_INSTANCE = new MaterialContext().setShaderDefine("PARTICLE_MODEL_INSTANCE");
    public final static MaterialContext TRAIL_INSTANCE = new MaterialContext().setShaderDefine("TRAIL_INSTANCE");
    public final static MaterialContext BEAM_INSTANCE = new MaterialContext().setShaderDefine("BEAM_INSTANCE");
    public final static MaterialContext ARA_TRAIL_INSTANCE = new MaterialContext().setShaderDefine("ARA_TRAIL_INSTANCE");
    public final static MaterialContext ARA_TRAIL_TUBE_INSTANCE = new MaterialContext().setShaderDefine("ARA_TRAIL_TUBE_INSTANCE");
    /**
     * Model instancing with the mesh tangent uploaded as vertex data — the emitter's {@code Tangent}
     * renderer setting. It belongs on the context rather than on each material because the VAO layout is
     * shared by every material drawing the pass (including the mask and wireframe sub-passes), so they
     * must all compile against the same attribute layout. Picked in
     * {@code ParticleConfig.RenderPass.drawInstanced}.
     */
    public final static MaterialContext PARTICLE_MODEL_INSTANCE_TANGENT =
            new MaterialContext().setShaderDefine("PARTICLE_MODEL_INSTANCE").setTangent(true);
    /** Model instancing where the pose comes from a baked table, one frame per particle. */
    public final static MaterialContext PARTICLE_MODEL_INSTANCE_VAT =
            new MaterialContext().setShaderDefine("PARTICLE_MODEL_INSTANCE").setVat(true);
    public final static MaterialContext PARTICLE_MODEL_INSTANCE_VAT_TANGENT =
            new MaterialContext().setShaderDefine("PARTICLE_MODEL_INSTANCE").setVat(true).setTangent(true);

    /** CPU tile quads with per-particle records bound — MIRRORED IN {@code photon:particle.glsl}. */
    public static final String CPU_DATA_DEFINE = "PHOTON_CPU_DATA";
    /** The CPU path when a material reads particle data: same vertices as {@link #NORMAL}, plus records. */
    public final static MaterialContext PARTICLE_CPU_DATA = new MaterialContext().setShaderDefine(CPU_DATA_DEFINE);

    /** Extra define enabling the mesh tangent attribute — MIRRORED IN {@code photon:particle.glsl}. */
    public static final String TANGENT_DEFINE = "PHOTON_TANGENT";
    /** Extra define making the position come from the baked pose table — MIRRORED IN the same file. */
    public static final String VAT_DEFINE = "PHOTON_VAT";

    private String shaderDefine = "";
    private boolean tangent;
    private boolean vat;
    private boolean isRenderingPreview;

    /** Every {@code #define} this draw needs, for the shader builders. */
    public Set<String> getShaderDefines() {
        var defines = new java.util.LinkedHashSet<String>();
        if (!shaderDefine.isEmpty()) defines.add(shaderDefine);
        if (tangent) defines.add(TANGENT_DEFINE);
        if (vat) defines.add(VAT_DEFINE);
        return Set.copyOf(defines);
    }

    /** Stable cache key for {@link #getShaderDefines()} (a {@code Set} is not an ordered key). */
    public String getVariantKey() {
        var key = new StringBuilder(shaderDefine);
        if (tangent) key.append("+").append(TANGENT_DEFINE);
        if (vat) key.append("+").append(VAT_DEFINE);
        return key.toString();
    }

    public boolean isUsingShaderPack() {
        return Photon.isUsingShaderPack();
    }

}
