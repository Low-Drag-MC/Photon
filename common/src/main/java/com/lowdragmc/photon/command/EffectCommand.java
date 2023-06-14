package com.lowdragmc.photon.command;

import com.lowdragmc.lowdraglib.networking.IPacket;
import lombok.Setter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * @author KilaBash
 * @date 2023/6/12
 * @implNote EffectCommand
 */
public abstract class EffectCommand implements IPacket {
    @Setter
    protected ResourceLocation location;
    @Setter
    protected Vec3 offset = Vec3.ZERO;
    @Setter
    protected int delay;
    @Setter
    protected boolean forcedDeath;
    @Setter
    protected boolean allowMulti;

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(location);
        buf.writeDouble(offset.x);
        buf.writeDouble(offset.y);
        buf.writeDouble(offset.z);
        buf.writeVarInt(delay);
        buf.writeBoolean(forcedDeath);
        buf.writeBoolean(allowMulti);
    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        location = buf.readResourceLocation();
        offset = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        delay = buf.readVarInt();
        forcedDeath = buf.readBoolean();
        allowMulti = buf.readBoolean();
    }

}
