package com.lowdragmc.photon.command;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.EntityEffectExecutor;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class RemoveBlockEffectCommand implements CustomPacketPayload {
    public static final ResourceLocation ID = Photon.id("remove_block_effect_command");
    public static final Type<RemoveBlockEffectCommand> TYPE = new Type<>(ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, RemoveBlockEffectCommand> CODEC = StreamCodec.ofMember(RemoveBlockEffectCommand::encode, RemoveBlockEffectCommand::decodePacket);

    protected BlockPos pos;
    @Setter
    protected boolean force;
    @Nullable
    @Setter
    protected ResourceLocation location;

    @Override
    @Nonnull
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

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
        PacketDistributor.sendToPlayersTrackingChunk(context.getSource().getLevel(), new ChunkPos(command.pos), command);
        return Command.SINGLE_SUCCESS;
    }

    public void encode(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(force);
        buf.writeBoolean(location != null);
        if (location != null) {
            buf.writeResourceLocation(location);
        }
    }

    public void decode(RegistryFriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        force = buf.readBoolean();
        if (buf.readBoolean()) {
            location = buf.readResourceLocation();
        }
    }

    public static RemoveBlockEffectCommand decodePacket(RegistryFriendlyByteBuf buf) {
        var packet = new RemoveBlockEffectCommand();
        packet.decode(buf);
        return packet;
    }

    public static void execute(RemoveBlockEffectCommand packet, IPayloadContext context) {
        if (LDLib2.isClient()) {
            Client.execute(packet, context);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static class Client {
        public static void execute(RemoveBlockEffectCommand packet, IPayloadContext context) {
            var effects = BlockEffectExecutor.CACHE.get(packet.pos);
            if (effects == null) return;
            var iter = effects.iterator();
            while (iter.hasNext()) {
                var effect = iter.next();
                if (packet.location == null || packet.location.equals(effect.getFx().getFxLocation())) {
                    iter.remove();
                    var runtime = effect.getRuntime();
                    if (runtime != null && runtime.isAlive()) {
                        runtime.destroy(packet.force);
                    }
                }
            }
        }
    }

}
