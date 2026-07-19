package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.gameobject.IFXObject;
import org.joml.Vector3f;
import lombok.Setter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Plays an FX anchored to a block position (centered on the block, plus the configured offset).
 * The FX is destroyed when the chunk unloads, the block changes, or — with {@code checkState} —
 * the exact block state changes. All accesses run on the client tick/render thread.
 */
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
        if (runtime == null || fxObject != runtime.root) {
            return;
        }
        if (!level.isLoaded(pos) || lastState.getBlock() != level.getBlockState(pos).getBlock() || (checkState && level.getBlockState(pos) != lastState)) {
            // anchor gone: stop the FX (force drops remnants immediately) and retire right away
            runtime.destroy(forcedDeath);
            retire(CACHE, pos);
        } else if (runtimeEnded()) {
            // self-evict finished runtimes instead of lingering until the next same-key start()
            retire(CACHE, pos);
        }
    }

    @Override
    public void start() {
        var effects = CACHE.computeIfAbsent(pos, p -> new ArrayList<>());
        if (shouldSkipStart(effects)) {
            return;
        }
        resetFinishedNotification();
        this.runtime = fx.createRuntime();
        var root = this.runtime.getRoot();
        root.updatePos(new Vector3f(pos.getX(), pos.getY(), pos.getZ())
                .add(offset.x + 0.5f, offset.y + 0.5f, offset.z + 0.5f));
        root.updateRotation(rotation);
        root.updateScale(scale);
        this.runtime.emit(this, delay);
        lastState = level.getBlockState(pos);
        effects.add(this);
    }

}
