package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib.client.scene.ParticleManager;
import com.lowdragmc.lowdraglib.gui.editor.ui.ConfigPanel;
import com.lowdragmc.lowdraglib.gui.editor.ui.MenuPanel;
import com.lowdragmc.lowdraglib.gui.widget.SceneWidget;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import com.lowdragmc.lowdraglib.utils.TrackedDummyWorld;
import lombok.Getter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.Set;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote ParticleScene
 */
public class ParticleScene extends SceneWidget {
    @Getter
    protected final TrackedDummyWorld level = new TrackedDummyWorld();

    @Getter
    @Environment(EnvType.CLIENT)
    protected final ParticleManager particleManager = new ParticleManager();

    public ParticleScene(ParticleEditor editor) {
        super(0, MenuPanel.HEIGHT, editor.getSize().getWidth() - ConfigPanel.WIDTH, editor.getSize().height - MenuPanel.HEIGHT, null);
        setRenderFacing(false);
        setRenderSelect(false);
        useCacheBuffer();
        resetScene();
    }

    @Override
    protected ParticleManager createParticleManager() {
        return particleManager;
    }

    public void resetScene() {
        this.level.clear();
        createScene(level);
        Set<BlockPos> plane = new HashSet<>();
        for (int x = -1; x < 2; x++) {
            for (int z = -1; z < 2; z++) {
                plane.add(new BlockPos(x, 0, z));
                level.addBlock(new BlockPos(x, 0, z), BlockInfo.fromBlock(Blocks.GRASS_BLOCK));
            }
        }
        plane.add(new BlockPos(0, 6, 0));
        setRenderedCore(plane, null);
    }

}
