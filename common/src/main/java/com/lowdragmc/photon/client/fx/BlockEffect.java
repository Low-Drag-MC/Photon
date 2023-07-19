package com.lowdragmc.photon.client.fx;

import org.joml.Vector3f;
import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote BlockEffect
 */
@Environment(EnvType.CLIENT)
public class BlockEffect extends FXEffect {
    public static Map<BlockPos, List<BlockEffect>> CACHE = new HashMap<>();
    public final BlockPos pos;
    @Setter
    private boolean checkState;
    // runtime
    private BlockState lastState;

    public BlockEffect(FX fx, Level level, BlockPos pos) {
        super(fx, level);
        this.pos = pos;
    }

    @Override
    public boolean updateEmitter(IParticleEmitter emitter) {
        if (!level.isLoaded(pos) || lastState.getBlock() != level.getBlockState(pos).getBlock() || (checkState && level.getBlockState(pos) != lastState)) {
            emitter.remove(forcedDeath);
            return forcedDeath;
        }
        return false;
    }

    @Override
    public void start() {
        this.emitters.clear();
        this.emitters.addAll(fx.generateEmitters());
        if (this.emitters.isEmpty()) return;
        if (!allowMulti) {
            var effects = CACHE.computeIfAbsent(pos, p -> new ArrayList<>());
            var iter = effects.iterator();
            while (iter.hasNext()) {
                var effect = iter.next();
                boolean removed = false;
                if (effect.emitters.stream().noneMatch(e -> e.self().isAlive())) {
                    iter.remove();
                    removed = true;
                }
                if (effect.fx.equals(fx) && !removed) {
                    return;
                }
            }
            effects.add(this);
        }
        var realPos= new Vector3f(pos.getX(), pos.getY(), pos.getZ()).add((float) (xOffset + 0.5f), (float) (yOffset + 0.5f), (float) (zOffset + 0.5f));
        for (var emitter : emitters) {
            if (!emitter.isSubEmitter()) {
                emitter.reset();
                emitter.self().setDelay(delay);
                emitter.emmitToLevel(this, level, realPos.x, realPos.y, realPos.z, xRotation, yRotation, zRotation);
            }
        }
        lastState = level.getBlockState(pos);
    }

}
