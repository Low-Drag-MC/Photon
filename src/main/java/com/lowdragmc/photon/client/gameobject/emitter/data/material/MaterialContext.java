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

    /** Extra define enabling the mesh tangent attribute — MIRRORED IN {@code photon:particle.glsl}. */
    public static final String TANGENT_DEFINE = "PHOTON_TANGENT";

    private String shaderDefine = "";
    private boolean tangent;
    private boolean isRenderingPreview;

    /** Every {@code #define} this draw needs, for the shader builders. */
    public Set<String> getShaderDefines() {
        if (shaderDefine.isEmpty()) {
            return tangent ? Set.of(TANGENT_DEFINE) : Set.of();
        }
        return tangent ? Set.of(shaderDefine, TANGENT_DEFINE) : Set.of(shaderDefine);
    }

    /** Stable cache key for {@link #getShaderDefines()} (a {@code Set} is not an ordered key). */
    public String getVariantKey() {
        return tangent ? shaderDefine + "+" + TANGENT_DEFINE : shaderDefine;
    }

    public boolean isUsingShaderPack() {
        return Photon.isUsingShaderPack();
    }

}
