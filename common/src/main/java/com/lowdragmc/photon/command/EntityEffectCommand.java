package com.lowdragmc.photon.command;

import com.lowdragmc.lowdraglib.networking.IHandlerContext;
import com.lowdragmc.photon.PhotonNetworking;
import com.lowdragmc.photon.client.fx.EntityEffect;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import lombok.NoArgsConstructor;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote EntityEffectCommand
 */
@NoArgsConstructor
public class EntityEffectCommand extends EffectCommand {

    @Setter
    protected List<Entity> entities;
    // client
    private int[] ids = new int[0];

    public static LiteralArgumentBuilder<CommandSourceStack> createServerCommand() {
        return Commands.literal("entity")
                .then(Commands.argument("entities", EntityArgument.entities())
                        .executes(c -> execute(c, 0))
                        .then(Commands.argument("offset", Vec3Argument.vec3(false))
                                .executes(c -> execute(c, 1))
                                .then(Commands.argument("delay", IntegerArgumentType.integer(0))
                                        .executes(c -> execute(c, 2))
                                        .then(Commands.argument("force death", BoolArgumentType.bool())
                                                .executes(c -> execute(c, 3))
                                                .then(Commands.argument("allow multi", BoolArgumentType.bool())
                                                        .executes(c -> execute(c, 4)))))));
    }

    private static int execute(CommandContext<CommandSourceStack> context, int feature) throws CommandSyntaxException {
        var command = new EntityEffectCommand();
        command.setLocation(ResourceLocationArgument.getId(context, "location"));
        command.setEntities(EntityArgument.getEntities(context, "entities").stream().map(e -> (Entity) e).toList());
        if (feature >= 1) {
            command.setOffset(Vec3Argument.getVec3(context, "offset"));
        }
        if (feature >= 2) {
            command.setDelay(IntegerArgumentType.getInteger(context, "delay"));
        }
        if (feature >= 3) {
            command.setForcedDeath(BoolArgumentType.getBool(context, "force death"));
        }
        if (feature >= 4) {
            command.setAllowMulti(BoolArgumentType.getBool(context, "allow multi"));
        }
        PhotonNetworking.NETWORK.sendToAll(command);
        return Command.SINGLE_SUCCESS;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        super.encode(buf);
        buf.writeVarInt(entities.size());
        for (Entity entity : entities) {
            buf.writeVarInt(entity.getId());
        }
    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        super.decode(buf);
        ids = new int[buf.readVarInt()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = buf.readVarInt();
        }
    }

    @Override
    @Environment(EnvType.CLIENT)
    public void execute(IHandlerContext handler) {
        var level = handler.getLevel();
        var fx = FXHelper.getFX(location);
        if (fx != null) {
            for (var id : ids) {
                var entity = level.getEntity(id);
                if (entity != null) {
                    var effect = new EntityEffect(fx, level, entity);
                    effect.setOffset(offset.x, offset.y, offset.z);
                    effect.setDelay(delay);
                    effect.setForcedDeath(forcedDeath);
                    effect.setAllowMulti(allowMulti);
                    effect.start();
                }
            }
        }
    }
}
