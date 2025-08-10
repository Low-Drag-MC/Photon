package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.photon.Photon;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@Accessors(chain = true)
@Data(staticConstructor = "of")
public class MaterialContext {
    public final static MaterialContext NORMAL = new MaterialContext();
    public final static MaterialContext PREVIEW = new MaterialContext().setRenderingPreview(true);
    public final static MaterialContext PARTICLE_INSTANCE = new MaterialContext().setShaderDefine("PARTICLE_INSTANCE");
    public final static MaterialContext PARTICLE_MODEL_INSTANCE = new MaterialContext().setShaderDefine("PARTICLE_MODEL_INSTANCE");

    private String shaderDefine = "";
    private boolean isRenderingPreview;

    public boolean isUsingShaderPack() {
        return Photon.isUsingShaderPack();
    }

}
