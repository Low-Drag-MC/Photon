package com.lowdragmc.photon.command;

import com.lowdragmc.lowdraglib.networking.IHandlerContext;
import com.lowdragmc.photon.PhotonNetworking;
import com.lowdragmc.photon.client.fx.BlockEffect;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote BlockEffectCommand
 */
public class BlockEffectCommand extends EffectCommand {
    @Setter
    protected BlockPos pos;
    @Setter
    protected boolean checkState;

    public BlockEffectCommand() {
        super();
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createServerCommand() {
        return Commands.literal("block")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> execute(c, 0))
                        .then(Commands.argument("offset", Vec3Argument.vec3(false))
                                .executes(c -> execute(c, 1))
                                .then(Commands.argument("delay", IntegerArgumentType.integer(0))
                                        .executes(c -> execute(c, 2))
                                        .then(Commands.argument("force death", BoolArgumentType.bool())
                                                .executes(c -> execute(c, 3))
                                                .then(Commands.argument("allow multi", BoolArgumentType.bool())
                                                        .executes(c -> execute(c, 4))
                                                        .then(Commands.argument("check state", BoolArgumentType.bool())
                                                                .executes(c -> execute(c, 5))))))));
    }

    private static int execute(CommandContext<CommandSourceStack> context, int feature) throws CommandSyntaxException {
        var command = new BlockEffectCommand();
        command.setLocation(ResourceLocationArgument.getId(context, "location"));
        command.setPos(BlockPosArgument.getLoadedBlockPos(context, "pos"));
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
        if (feature >= 5) {
            command.setCheckState(BoolArgumentType.getBool(context, "check state"));
        }
        PhotonNetworking.NETWORK.sendToTrackingChunk(command, context.getSource().getLevel().getChunkAt(command.pos));
        return Command.SINGLE_SUCCESS;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        super.encode(buf);
        buf.writeBlockPos(pos);
        buf.writeBoolean(checkState);
    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        super.decode(buf);
        pos = buf.readBlockPos();
        checkState = buf.readBoolean();
    }

    @Override
    @Environment(EnvType.CLIENT)
    public void execute(IHandlerContext handler) {
        if (handler.getLevel().isLoaded(pos)) {
            var fx = FXHelper.getFX(location);
            if (fx != null) {
                var effect = new BlockEffect(fx, handler.getLevel(), pos);
                effect.setOffset(offset.x, offset.y, offset.z);
                effect.setDelay(delay);
                effect.setForcedDeath(forcedDeath);
                effect.setAllowMulti(allowMulti);
                effect.setCheckState(checkState);
                effect.start();
            }
        }
    }
}
