package com.lowdragmc.photon.command;

import com.lowdragmc.lowdraglib.networking.IHandlerContext;
import com.lowdragmc.lowdraglib.networking.IPacket;
import com.lowdragmc.lowdraglib.networking.LDLNetworking;
import com.lowdragmc.photon.client.fx.BlockEffect;
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
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote BlockEffectCommand
 */
@NoArgsConstructor
public class BlockEffectCommand implements IPacket {

    @Setter
    private ResourceLocation fx;
    @Setter
    private BlockPos pos;
    @Setter
    private Vec3 offset = Vec3.ZERO;
    @Setter
    private int delay;
    @Setter
    private boolean forcedDeath;
    @Setter
    private boolean allowMulti;
    @Setter
    private boolean checkState;

    public static LiteralArgumentBuilder<CommandSourceStack> createCommand() {
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
        command.setFx(ResourceLocationArgument.getId(context, "location"));
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
        LDLNetworking.NETWORK.sendToTrackingChunk(command, context.getSource().getLevel().getChunkAt(command.pos));
        return Command.SINGLE_SUCCESS;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(fx);
        buf.writeBlockPos(pos);
        buf.writeDouble(offset.x);
        buf.writeDouble(offset.y);
        buf.writeDouble(offset.z);
        buf.writeVarInt(delay);
        buf.writeBoolean(forcedDeath);
        buf.writeBoolean(allowMulti);
        buf.writeBoolean(checkState);
    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        fx = buf.readResourceLocation();
        pos = buf.readBlockPos();
        offset = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        delay = buf.readVarInt();
        forcedDeath = buf.readBoolean();
        allowMulti = buf.readBoolean();
        checkState = buf.readBoolean();
    }

    @Override
    @Environment(EnvType.CLIENT)
    public void execute(IHandlerContext handler) {
        if (handler.getLevel().isLoaded(pos)) {
            var list = FXHelper.getFX(fx);
            if (list.size() > 0) {
                var effect = new BlockEffect(fx, handler.getLevel(), pos, list);
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
