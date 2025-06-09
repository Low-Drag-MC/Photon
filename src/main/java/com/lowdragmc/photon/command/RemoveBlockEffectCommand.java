package com.lowdragmc.photon.command;

import com.lowdragmc.lowdraglib2.networking.IHandlerContext;
import com.lowdragmc.lowdraglib2.networking.IPacket;
import com.lowdragmc.photon.PhotonNetworking;
import com.lowdragmc.photon.client.fx.BlockEffect;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

public class RemoveBlockEffectCommand implements IPacket {
    protected BlockPos pos;
    @Setter
    protected boolean force;
    @Nullable
    @Setter
    protected ResourceLocation location;

    public static LiteralArgumentBuilder<CommandSourceStack> createServerCommand() {
        return Commands.literal("block")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> execute(c, false, false))
                        .then(Commands.argument("force", BoolArgumentType.bool())
                                .executes(c -> execute(c, true, false))
                                .then(Commands.argument("location", ResourceLocationArgument.id())
                                        .executes(c -> execute(c, true, true)))));
    }

    private static int execute(CommandContext<CommandSourceStack> context, boolean force, boolean location) throws CommandSyntaxException {
        var command = new RemoveBlockEffectCommand();
        command.pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        if (force) {
            command.setForce(BoolArgumentType.getBool(context, "force"));
        }
        if (location) {
            command.setLocation(ResourceLocationArgument.getId(context, "location"));
        }
        PhotonNetworking.NETWORK.sendToTrackingChunk(command, context.getSource().getLevel().getChunkAt(command.pos));
        return Command.SINGLE_SUCCESS;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(force);
        buf.writeBoolean(location != null);
        if (location != null) {
            buf.writeResourceLocation(location);
        }
    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        force = buf.readBoolean();
        if (buf.readBoolean()) {
            location = buf.readResourceLocation();
        }
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void execute(IHandlerContext handler) {
        var effects = BlockEffect.CACHE.get(pos);
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
    }
}
