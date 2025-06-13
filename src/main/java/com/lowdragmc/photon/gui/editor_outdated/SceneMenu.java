package com.lowdragmc.photon.gui.editor_outdated;

import com.lowdragmc.lowdraglib2.gui.editor.Icons;
import com.lowdragmc.lowdraglib2.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib2.gui.editor.ui.menu.MenuTab;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib2.utils.BlockInfo;
import com.lowdragmc.lowdraglib2.utils.TrackedDummyWorld;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.Set;

/**
 * @author KilaBash
 * @date 2023/06/06
 * @implNote SceneMenu
 */
@LDLRegister(name = "scene", group = "editor.fx", priority = 99)
@Getter
public class SceneMenu extends MenuTab {
    private int range = 3;
    private boolean usingRealWorld = false;

    protected TreeBuilder.Menu createMenu() {
        var viewMenu = TreeBuilder.Menu.start();
        viewMenu.branch("range", menu -> {
            for (int r : new int[]{1, 3, 5}) {
                menu.leaf(this.range == r ? Icons.CHECK : IGuiTexture.EMPTY, "%d×%d×%d".formatted(r, r, r), () -> setRange(r));
            }
        });
        viewMenu.crossLine();
        viewMenu.leaf(usingRealWorld ? Icons.CHECK : IGuiTexture.EMPTY, "photon.gui.editor.menu.scene.real_world", () -> setUsingRealWorld(!usingRealWorld));
        return viewMenu;
    }

    protected void updateScene() {
        if (editor instanceof FXEditor fxEditor && fxEditor.getTabPages().focus instanceof ParticleScenePanel panel) {
            panel.resetScene();
        }
    }

    public void setRange(int range) {
        this.range = range;
        updateScene();
    }

    public void setUsingRealWorld(boolean usingRealWorld) {
        this.usingRealWorld = usingRealWorld;
        updateScene();
    }

    public Set<BlockPos> createScene(TrackedDummyWorld level) {
        Set<BlockPos> plane = new HashSet<>();
        if (usingRealWorld) {
            var world = Minecraft.getInstance().level;
            var playerPos = Minecraft.getInstance().player.getOnPos();
            for (int x = -range; x <= range; x++) {
                for (int y = -range; y <= range; y++) {
                    for (int z = -range; z <= range; z++) {
                        var state = world.getBlockState(new BlockPos(x, y, z).offset(playerPos));
                        if (state.getBlock() != Blocks.AIR) {
                            plane.add(new BlockPos(x, y, z));
                            level.addBlock(new BlockPos(x, y, z), BlockInfo.fromBlockState(state));
                        }
                    }
                }
            }
        } else {
            int i = 0;
            for (int x = -range; x <= range; x++) {
                for (int z = -range; z <= range; z++) {
                    plane.add(new BlockPos(x, 0, z));
                    level.addBlock(new BlockPos(x, 0, z), BlockInfo.fromBlock(i % 2 == 0 ? Blocks.GRAY_CONCRETE : Blocks.LIGHT_GRAY_CONCRETE));
                    i++;
                }
            }
        }
        return plane;
    }

    @Override
    public CompoundTag serializeNBT() {
        var tag = new CompoundTag();
        tag.putInt("range", range);
        tag.putBoolean("usingRealWorld", usingRealWorld);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        range = nbt.getInt("range");
        usingRealWorld = nbt.getBoolean("usingRealWorld");
    }

}
