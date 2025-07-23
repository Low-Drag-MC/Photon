package com.lowdragmc.photon.command;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nonnull;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote BlockEffectCommand
 */
public class BlockEffectCommand extends EffectCommand {
    public static final ResourceLocation ID = Photon.id("block_effect_command");
    public static final Type<BlockEffectCommand> TYPE = new Type<>(ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, BlockEffectCommand> CODEC = StreamCodec.ofMember(BlockEffectCommand::encode, BlockEffectCommand::decodePacket);

    @Setter
    protected BlockPos pos;
    @Setter
    protected boolean checkState;

    public BlockEffectCommand() {
        super();
    }

    @Override
    @Nonnull
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createServerCommand() {
        return Commands.literal("block")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> execute(c, false, false, false, false, false, false, false))
                        .then(Commands.argument("offset", Vec3Argument.vec3(false))
                                .executes(c -> execute(c, true, false, false, false, false, false, false))
                                .then(Commands.argument("rotation", Vec3Argument.vec3(false))
                                        .executes(c -> execute(c, true, true, false, false, false, false, false))
                                        .then(Commands.argument("scale", Vec3Argument.vec3(false))
                                                .executes(c -> execute(c, true, true, true, false, false, false, false))
                                                .then(Commands.argument("delay", IntegerArgumentType.integer(0))
                                                        .executes(c -> execute(c, true, true, true, true, false, false, false))
                                                        .then(Commands.argument("force death", BoolArgumentType.bool())
                                                                .executes(c -> execute(c, true, true, true, true, true, false, false))
                                                                .then(Commands.argument("allow multi", BoolArgumentType.bool())
                                                                        .executes(c -> execute(c, true, true, true, true, true, true, false))
                                                                        .then(Commands.argument("check state", BoolArgumentType.bool())
                                                                                .executes(c -> execute(c, true, true, true, true, true, true, true)))))))
                                        .then(Commands.argument("delay", IntegerArgumentType.integer(0))
                                                .executes(c -> execute(c, true, true, false, true, false, false, false))
                                                .then(Commands.argument("force death", BoolArgumentType.bool())
                                                        .executes(c -> execute(c, true, true, false, true, true, false, false))
                                                        .then(Commands.argument("allow multi", BoolArgumentType.bool())
                                                                .executes(c -> execute(c, true, true, false, true, true, true, false))
                                                                .then(Commands.argument("check state", BoolArgumentType.bool())
                                                                        .executes(c -> execute(c, true, true, false, true, true, true, true))))))
                                )));
    }

    private static int execute(CommandContext<CommandSourceStack> context,
                               boolean offset,
                               boolean rotation,
                               boolean scale,
                               boolean delay,
                               boolean forceDeath,
                               boolean allowMulti,
                               boolean checkState) throws CommandSyntaxException {
        var command = new BlockEffectCommand();
        command.setLocation(ResourceLocationArgument.getId(context, "location"));
        command.setPos(BlockPosArgument.getLoadedBlockPos(context, "pos"));
        if (offset) {
            command.setOffset(Vec3Argument.getVec3(context, "offset"));
        }
        if (rotation) {
            command.setRotation(Vec3Argument.getVec3(context, "rotation"));
        }
        if (scale) {
            command.setScale(Vec3Argument.getVec3(context, "scale"));
        }
        if (delay) {
            command.setDelay(IntegerArgumentType.getInteger(context, "delay"));
        }
        if (forceDeath) {
            command.setForcedDeath(BoolArgumentType.getBool(context, "force death"));
        }
        if (allowMulti) {
            command.setAllowMulti(BoolArgumentType.getBool(context, "allow multi"));
        }
        if (checkState) {
            command.setCheckState(BoolArgumentType.getBool(context, "check state"));
        }
        PacketDistributor.sendToPlayersTrackingChunk(context.getSource().getLevel(), new ChunkPos(command.pos), command);
        return Command.SINGLE_SUCCESS;
    }

    @Override
    public void encode(RegistryFriendlyByteBuf buf) {
        super.encode(buf);
        buf.writeBlockPos(pos);
        buf.writeBoolean(checkState);
    }

    @Override
    public void decode(RegistryFriendlyByteBuf buf) {
        super.decode(buf);
        pos = buf.readBlockPos();
        checkState = buf.readBoolean();
    }

    public static BlockEffectCommand decodePacket(RegistryFriendlyByteBuf buf) {
        var packet = new BlockEffectCommand();
        packet.decode(buf);
        return packet;
    }

    public static void execute(BlockEffectCommand packet, IPayloadContext context) {
        if (LDLib2.isClient()) {
            Client.execute(packet, context);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static class Client {
        public static void execute(BlockEffectCommand packet, IPayloadContext context) {
            var level = Minecraft.getInstance().level;
            if (level != null && level.isLoaded(packet.pos)) {
                var fx = FXHelper.getFX(packet.location);
                if (fx != null) {
                    var effect = new BlockEffectExecutor(fx, level, packet.pos);
                    var offset = packet.offset;
                    var rotation = packet.rotation;
                    var scale = packet.scale;
                    effect.setOffset(offset.x, offset.y, offset.z);
                    effect.setRotation(rotation.x, rotation.y, rotation.z);
                    effect.setScale(scale.x, scale.y, scale.z);
                    effect.setDelay(packet.delay);
                    effect.setForcedDeath(packet.forcedDeath);
                    effect.setAllowMulti(packet.allowMulti);
                    effect.setCheckState(packet.checkState);
                    effect.start();
                }
            }
        }
    }

}
