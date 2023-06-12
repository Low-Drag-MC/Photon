package com.lowdragmc.photon.command;

import com.lowdragmc.lowdraglib.networking.IPacket;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * @author KilaBash
 * @date 2023/6/12
 * @implNote EffectCommand
 */
public abstract class EffectCommand implements IPacket {
    private final static Map<ResourceLocation, CompoundTag> CACHE = new HashMap<>();
    public static final SimpleCommandExceptionType ERROR_LOAD_FX_FILE = new SimpleCommandExceptionType(Component.translatable("cant load the fx file"));
    public static final String FX_PATH = "fx/";

    @Setter
    protected ResourceLocation location;
    @Setter
    @Nullable
    protected CompoundTag data;
    @Setter
    protected Vec3 offset = Vec3.ZERO;
    @Setter
    protected int delay;
    @Setter
    protected boolean forcedDeath;
    @Setter
    protected boolean allowMulti;

    public static int clearCache() {
        var count = CACHE.size();
        CACHE.clear();
        return count;
    }

    protected static CompoundTag loadData(ResourceLocation fxLocation) throws CommandSyntaxException {
        var data = CACHE.computeIfAbsent(fxLocation, location -> {
            ResourceLocation resourceLocation = new ResourceLocation(fxLocation.getNamespace(), FX_PATH + fxLocation.getPath() + ".fx");
            try (var inputStream = Minecraft.getInstance().getResourceManager().open(resourceLocation)) {
                return NbtIo.readCompressed(inputStream);
            } catch (Exception ignored) {
                return null;
            }
        });
        if (data == null) {
            throw ERROR_LOAD_FX_FILE.create();
        }
        return data;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(location);
        buf.writeNbt(data);
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
        data = buf.readNbt();
        offset = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        delay = buf.readVarInt();
        forcedDeath = buf.readBoolean();
        allowMulti = buf.readBoolean();
    }

}
