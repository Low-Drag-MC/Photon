package com.lowdragmc.photon.command;

import lombok.Setter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * @author KilaBash
 * @date 2023/6/12
 * @implNote EffectCommand
 */
public abstract class EffectCommand implements CustomPacketPayload {
    @Setter
    protected ResourceLocation location;
    @Setter
    protected Vec3 offset = Vec3.ZERO;
    @Setter
    protected Vec3 rotation = Vec3.ZERO;
    @Setter
    protected Vec3 scale = new Vec3(1, 1, 1);
    @Setter
    protected int delay;
    @Setter
    protected boolean forcedDeath;
    @Setter
    protected boolean allowMulti;

    public void encode(RegistryFriendlyByteBuf buf) {
        buf.writeResourceLocation(location);
        buf.writeDouble(offset.x);
        buf.writeDouble(offset.y);
        buf.writeDouble(offset.z);
        buf.writeDouble(rotation.x);
        buf.writeDouble(rotation.y);
        buf.writeDouble(rotation.z);
        buf.writeDouble(scale.x);
        buf.writeDouble(scale.y);
        buf.writeDouble(scale.z);
        buf.writeVarInt(delay);
        buf.writeBoolean(forcedDeath);
        buf.writeBoolean(allowMulti);
    }

    public void decode(RegistryFriendlyByteBuf buf) {
        location = buf.readResourceLocation();
        offset = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        rotation = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        scale = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        delay = buf.readVarInt();
        forcedDeath = buf.readBoolean();
        allowMulti = buf.readBoolean();
    }

}
