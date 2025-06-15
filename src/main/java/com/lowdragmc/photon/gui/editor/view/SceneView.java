package com.lowdragmc.photon.gui.editor.view;

import com.lowdragmc.lowdraglib2.editor.ui.View;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.SceneEditor;
import com.lowdragmc.lowdraglib2.editor_outdated.Icons;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.gui.editor.FXProjectEffect;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

public class SceneView extends View {
    public final SceneEditor sceneEditor = new SceneEditor();
    public final TrackedDummyWorld level = new TrackedDummyWorld();
    public final PhotonParticleManager particleManager = new PhotonParticleManager();
    public final FXProjectEffect effect = new FXProjectEffect(level);

    public SceneView() {
        super("editor.scene", Icons.CAMERA);
        this.getLayout().setWidthPercent(100.0F);
        this.getLayout().setHeightPercent(100.0F);
        level.setParticleManager(particleManager);

        sceneEditor.layout(layout -> {
            layout.setWidthPercent(100);
            layout.setFlex(1);
        });
        sceneEditor.scene
                .createScene(level)
                .setTickWorld(true)
                .useCacheBuffer();
        this.addChild(sceneEditor);
    }

    public void clearScene() {
        level.clear();
        clearParticles();
    }

    public void clearParticles() {
        particleManager.clearAllParticles();
    }

    public void loadScene() {
        clearScene();
        var i = 0;
        for (int x = -5; x < 6; x++) {
            for (int z = -5; z < 6; z++) {
                var blockState = (i % 2 == 0 ? Blocks.GRAY_CONCRETE : Blocks.LIGHT_GRAY_CONCRETE).defaultBlockState();
                level.setBlockAndUpdate(new BlockPos(x, 0, z), blockState);
                i++;
            }
        }
        sceneEditor.scene.setRenderedCore(level.getFilledBlocks().longStream().mapToObj(BlockPos::of).toList());
    }

}
