package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.photon.client.fx.IEffectExecutor;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

/**
 * @author KilaBash
 * @date 2023/7/17
 * @implNote EditorEffect
 */
public class FXProjectEffectExecutor implements IEffectExecutor {
    @Getter
    public final Level level;
    @Getter @Setter
    private long seed = 0;
    @Getter
    public final RandomSource randomSource = RandomSource.create(seed);

    public FXProjectEffectExecutor(Level level) {
        this.level = level;
    }

    public void reset() {
        randomSource.setSeed(seed);
    }

    /** Editor previews feed the isolated scene stack — never the world's post-effect chain. */
    @Override
    public com.lowdragmc.photon.client.postfx.runtime.PostEffectStack postEffectSink() {
        return com.lowdragmc.photon.client.postfx.runtime.PostEffectStack.EDITOR_SCENE;
    }
}
