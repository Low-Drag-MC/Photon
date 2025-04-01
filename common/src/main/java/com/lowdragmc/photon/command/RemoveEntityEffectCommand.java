package com.lowdragmc.photon.command;

import com.lowdragmc.lowdraglib.networking.IHandlerContext;
import com.lowdragmc.lowdraglib.networking.IPacket;
import com.lowdragmc.photon.PhotonNetworking;
import com.lowdragmc.photon.client.fx.EntityEffect;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.List;

public class RemoveEntityEffectCommand implements IPacket {
    @Setter
    protected List<Entity> entities;
    // client
    private int[] ids = new int[0];
    @Setter
    protected boolean force;
    @Nullable
    @Setter
    protected ResourceLocation location;

    public static LiteralArgumentBuilder<CommandSourceStack> createServerCommand() {
        return Commands.literal("entity")
                .then(Commands.argument("entities", EntityArgument.entities())
                        .executes(c -> execute(c, false, false))
                        .then(Commands.argument("force", BoolArgumentType.bool())
                                .executes(c -> execute(c, true, false))
                                .then(Commands.argument("location", ResourceLocationArgument.id())
                                        .executes(c -> execute(c, true, true)))));
    }

    private static int execute(CommandContext<CommandSourceStack> context, boolean force, boolean location) throws CommandSyntaxException {
        var command = new RemoveEntityEffectCommand();
        command.setEntities(EntityArgument.getEntities(context, "entities").stream().map(e -> (Entity) e).toList());
        if (force) {
            command.setForce(BoolArgumentType.getBool(context, "force"));
        }
        if (location) {
            command.setLocation(ResourceLocationArgument.getId(context, "location"));
        }
        PhotonNetworking.NETWORK.sendToAll(command);
        return Command.SINGLE_SUCCESS;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entities.size());
        for (Entity entity : entities) {
            buf.writeVarInt(entity.getId());
        }
        buf.writeBoolean(location != null);
        if (location != null) {
            buf.writeResourceLocation(location);
        }
    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        ids = new int[buf.readVarInt()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = buf.readVarInt();
        }
        if (buf.readBoolean()) {
            location = buf.readResourceLocation();
        }
    }

    @Override
    @Environment(EnvType.CLIENT)
    public void execute(IHandlerContext handler) {
        for (var id : ids) {
            var entity = handler.getLevel().getEntity(id);
            if (entity != null) {
                var effects = EntityEffect.CACHE.get(entity);
                if (effects == null) return;
                var iter = effects.iterator();
                while (iter.hasNext()) {
                    var effect = iter.next();
                    if (location == null || location.equals(effect.getFx().getFxLocation())) {
                        iter.remove();
                        var runtime = effect.getRuntime();
                        if (runtime != null && runtime.isAlive()) {
                            runtime.destroy(force);
                        }
                    }
                }
                if (effects.isEmpty()) {
                    EntityEffect.CACHE.remove(entity);
                }
            }
        }
    }
}
