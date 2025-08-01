package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.gameobject.IFXObject;
import org.joml.Vector3f;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote BlockEffect
 */
@OnlyIn(Dist.CLIENT)
public class BlockEffectExecutor extends FXEffectExecutor {
    public static Map<BlockPos, List<BlockEffectExecutor>> CACHE = new HashMap<>();
    public final BlockPos pos;
    @Setter
    private boolean checkState;
    // runtime
    private BlockState lastState;

    public BlockEffectExecutor(FX fx, Level level, BlockPos pos) {
        super(fx, level);
        this.pos = pos;
    }

    @Override
    public void updateFXObjectTick(IFXObject fxObject) {
        if (runtime != null && fxObject == runtime.root) {
            if (!level.isLoaded(pos) || lastState.getBlock() != level.getBlockState(pos).getBlock() || (checkState && level.getBlockState(pos) != lastState)) {
                runtime.destroy(forcedDeath);
                CACHE.computeIfAbsent(pos, p -> new ArrayList<>()).remove(this);
            }
        }
    }

    @Override
    public void start() {
        var effects = CACHE.computeIfAbsent(pos, p -> new ArrayList<>());
        if (!allowMulti) {
            var iter = effects.iterator();
            while (iter.hasNext()) {
                var effect = iter.next();
                boolean removed = false;
                if (effect.runtime != null && !effect.runtime.isAlive()) {
                    iter.remove();
                    removed = true;
                }
                if ((effect.fx.equals(fx) || Objects.equals(effect.fx.getFxLocation(), fx.getFxLocation())) && !removed) {
                    return;
                }
            }
        }
        this.runtime = fx.createRuntime();
        var root = this.runtime.getRoot();
        root.updatePos(new Vector3f(pos.getX(), pos.getY(), pos.getZ())
                .add(offset.x + 0.5f, offset.y + 0.5f, offset.z + 0.5f));
        root.updateRotation(rotation);
        root.updateScale(scale);
        this.runtime.emmit(this, delay);
        lastState = level.getBlockState(pos);
        effects.add(this);
    }

}
