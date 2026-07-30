package com.lowdragmc.photon.core.mixins;

import com.lowdragmc.photon.client.render.IPhotonFXCollector;
import com.lowdragmc.photon.client.render.PhotonViewSettings;
import com.lowdragmc.photon.client.render.PhotonWorldRenderState;
import net.minecraft.client.renderer.SubmitNodeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.List;

/**
 * Gives every vanilla submit-node storage a Photon FX bucket, so the world ({@code LevelRenderer}'s
 * storage) and every LDLib2 scene ({@code WorldSceneRenderer}'s own instance) are
 * {@link IPhotonFXCollector}s for free — the view identity Photon needs is the storage instance the
 * engine already threads into {@code ParticleGroupRenderState.submit}.
 * <p>
 * Deliberately the only mixin this design needs: third parties implement {@link IPhotonFXCollector}
 * on their own collector instead. The bucket is untouched by {@code clearSubmitNodes()} —
 * {@code PhotonWorldRenderState.drain} clears what it consumes.
 */
@Mixin(SubmitNodeStorage.class)
public class SubmitNodeStorageMixin implements IPhotonFXCollector {

    @Unique
    private final List<BakeTask> photon$tasks = new ArrayList<>();

    @Unique
    private final List<PhotonWorldRenderState.DrawJob> photon$baked =
            new ArrayList<>();

    @Unique
    private PhotonViewSettings photon$settings = PhotonViewSettings.DEFAULT;

    @Override
    public List<BakeTask> photonFXTasks() {
        return photon$tasks;
    }

    @Override
    public List<PhotonWorldRenderState.DrawJob> photonFXBaked() {
        return photon$baked;
    }

    @Override
    public PhotonViewSettings photonViewSettings() {
        return photon$settings;
    }

    @Override
    public void photonViewSettings(PhotonViewSettings settings) {
        this.photon$settings = settings;
    }
}
