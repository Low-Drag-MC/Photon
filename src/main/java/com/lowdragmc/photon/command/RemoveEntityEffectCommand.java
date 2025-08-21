package com.lowdragmc.photon.command;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.EntityEffectExecutor;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import lombok.Setter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public class RemoveEntityEffectCommand implements CustomPacketPayload {
    public static final ResourceLocation ID = Photon.id("remove_entity_effect_command");
    public static final CustomPacketPayload.Type<RemoveEntityEffectCommand> TYPE = new CustomPacketPayload.Type<>(ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, RemoveEntityEffectCommand> CODEC = StreamCodec.ofMember(RemoveEntityEffectCommand::encode, RemoveEntityEffectCommand::decodePacket);

    @Setter
    protected List<Entity> entities;
    // client
    private int[] ids = new int[0];
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
        PacketDistributor.sendToAllPlayers(command);
        return Command.SINGLE_SUCCESS;
    }

    public void encode(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(entities.size());
        for (Entity entity : entities) {
            buf.writeVarInt(entity.getId());
        }
        buf.writeBoolean(force);
        buf.writeBoolean(location != null);
        if (location != null) {
            buf.writeResourceLocation(location);
        }
    }

    public void decode(RegistryFriendlyByteBuf buf) {
        ids = new int[buf.readVarInt()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = buf.readVarInt();
        }
        force = buf.readBoolean();
        if (buf.readBoolean()) {
            location = buf.readResourceLocation();
        }
    }

    public static RemoveEntityEffectCommand decodePacket(RegistryFriendlyByteBuf buf) {
        var packet = new RemoveEntityEffectCommand();
        packet.decode(buf);
        return packet;
    }

    public static void execute(RemoveEntityEffectCommand packet, IPayloadContext context) {
        if (LDLib2.isClient()) {
            Client.execute(packet, context);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static class Client {
        public static void execute(RemoveEntityEffectCommand packet, IPayloadContext context) {
            for (var id : packet.ids) {
                var entity = context.player().level().getEntity(id);
                if (entity != null) {
                    var effects = EntityEffectExecutor.CACHE.get(entity);
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
                    if (effects.isEmpty()) {
                        EntityEffectExecutor.CACHE.remove(entity);
                    }
                }
            }
        }
    }

}
