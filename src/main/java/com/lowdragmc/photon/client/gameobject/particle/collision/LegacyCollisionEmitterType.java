package com.lowdragmc.photon.client.gameobject.particle.collision;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.gameobject.FXObjectType;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import net.minecraft.nbt.CompoundTag;

public final class LegacyCollisionEmitterType {
    private LegacyCollisionEmitterType() {}

    @LDLRegisterClient(name = "collision_emitter", registry = "photon:fx_object")
    public static final FXObjectType TYPE = new FXObjectType() {
        @Override public IFXObject create() { return new ParticleEmitter(); }
        @Override public IGuiTexture icon() { return ParticleEmitter.ICON; }
        @Override public int version() { return 3; }
        @Override public CompoundTag fixData(CompoundTag data, int oldVersion) { return CollisionDataFix.migrate(data); }
    };
}
