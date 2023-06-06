package com.lowdragmc.photon.client.fx;

import com.lowdragmc.lowdraglib.utils.Vector3;
import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote FXEffect
 */
@Environment(EnvType.CLIENT)
public class BlockEffect implements IFXEffect {
    public static Map<BlockPos, List<BlockEffect>> CACHE = new HashMap<>();
    @Getter
    public final ResourceLocation fx;
    @Getter
    public final List<IParticleEmitter> emitters;
    public final Level level;
    public final BlockPos pos;
    @Setter
    private double xOffset, yOffset, zOffset;
    @Setter
    private int delay;
    @Setter
    private boolean forcedDeath;
    @Setter
    private boolean allowMulti;
    @Setter
    private boolean checkState;
    // runtime
    private BlockState lastState;

    public BlockEffect(ResourceLocation fx, Level level, BlockPos pos, List<IParticleEmitter> emitters) {
        this.fx = fx;
        this.emitters = emitters;
        this.level = level;
        this.pos = pos;
    }

    @Override
    public void setOffset(double x, double y, double z) {
        this.xOffset = x;
        this.yOffset = y;
        this.zOffset = z;
    }

    @Override
    public boolean updateEmitter(IParticleEmitter emitter) {
        var state = level.getBlockState(pos);
        if (lastState.getBlock() != state.getBlock() || (state != lastState && checkState)) {
            emitter.self().remove();
            if (forcedDeath) {
                emitter.getParticles().clear();
                return true;
            }
        }
        return false;
    }

    @Override
    public void start() {
        if (pos == null) return;
        var realPos= new Vector3(pos).add(xOffset + 0.5, yOffset + 0.5, zOffset + 0.5);
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
        for (var emitter : emitters) {
            emitter.reset();
            emitter.self().setDelay(delay);
            emitter.setFXEffect(this);
            emitter.emmitToLevel(level, realPos.x, realPos.y, realPos.z);
        }
        lastState = level.getBlockState(pos);
    }

}
