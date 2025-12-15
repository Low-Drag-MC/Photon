package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.nio.FloatBuffer;
import java.util.Collection;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL33.glVertexAttribDivisor;

@OnlyIn(Dist.CLIENT)
public abstract class AdditionalGPUDataSetting extends ToggleGroup {

    public interface DataProvider {
        int getSize();
        void upload(IParticle particle, FloatBuffer buffer, float partialTicks);
    }

    public abstract Collection<? extends DataProvider> getDataProviders();

    public boolean hasCustomData() {
        return !getDataProviders().isEmpty();
    }

    public int getCustomDataSize() {
        return getDataProviders().stream().map(DataProvider::getSize).reduce(0, Integer::sum);
    }

    public void instanceDataLayout(int offset, int attribIndex, int stride) {
        for (var dataProvider : getDataProviders()) {
            var size =  dataProvider.getSize();
            glVertexAttribPointer(attribIndex, size, GL_FLOAT, false, stride, offset);
            glEnableVertexAttribArray(attribIndex);
            glVertexAttribDivisor(attribIndex, 1);
            offset += size * Float.BYTES;
            attribIndex++;
        }
    }

    public void uploadData(IParticle particle, FloatBuffer buffer, float partialTicks) {
        getDataProviders().forEach(dataProvider -> dataProvider.upload(particle, buffer, partialTicks));
    }

    protected void onConfiguratorUpdate() {
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        super.buildConfigurator(father);
        father.addEventListener(Configurator.CHANGE_EVENT, e -> onConfiguratorUpdate());
    }

}
