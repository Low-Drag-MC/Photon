package com.lowdragmc.photon.gui.editor_outdated;

import com.lowdragmc.lowdraglib2.client.scene.ParticleManager;
import com.lowdragmc.lowdraglib2.gui.editor.ui.sceneeditor.SceneEditorWidget;
import com.lowdragmc.photon.client.PhotonParticleManager;
import lombok.Getter;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote ParticleScene
 */
@Getter
public class ParticleScene extends SceneEditorWidget {
    protected final PhotonParticleManager particleManager = new PhotonParticleManager();

    public ParticleScene(int x, int y, int width, int height) {
        super(x, y, width, height, null);
    }

    @Override
    protected ParticleManager createParticleManager() {
        return particleManager;
    }

}
