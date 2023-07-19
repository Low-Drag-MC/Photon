package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib.gui.editor.Icons;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.ui.menu.MenuTab;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import com.lowdragmc.lowdraglib.utils.TrackedDummyWorld;
import com.lowdragmc.photon.Photon;
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
@LDLRegister(name = "scene", group = "editor.particle", priority = 99)
public class SceneMenu extends MenuTab {

    @Getter
    private int range = 3;
    @Getter
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
        if (editor instanceof ParticleEditor particleEditor) {
            particleEditor.getParticleScene().resetScene();
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
                    level.addBlock(new BlockPos(x, 0, z), BlockInfo.fromBlock(i % 2 == 0 ? Blocks.SAND : Blocks.STONE));
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
