package com.lowdragmc.photon.command;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.EntityEffectExecutor;
import com.lowdragmc.photon.client.fx.FXEffectExecutor;
import com.lowdragmc.photon.client.fx.timeline.SectionControl;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * {@code /photon fx section (entity <entities> | block <pos>) (jump <section> | queue <section> | advance | release)
 * [location]}: moves the timeline sections of the effects on entities or a block, optionally only those of
 * {@code location}.
 */
public class EffectSectionCommand implements CustomPacketPayload {
    public static final Identifier ID = Photon.id("effect_section_command");
    public static final Type<EffectSectionCommand> TYPE = new Type<>(ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, EffectSectionCommand> CODEC =
            StreamCodec.ofMember(EffectSectionCommand::encode, EffectSectionCommand::decodePacket);

    public enum Action {
        JUMP, QUEUE, ADVANCE, RELEASE;

        boolean named() {
            return this == JUMP || this == QUEUE;
        }
    }

    private int[] entities = new int[0];
    @Nullable
    private BlockPos pos;
    private Action action = Action.JUMP;
    private String section = "";
    @Nullable
    private Identifier location;

    @Override
    @Nonnull
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createServerCommand() {
        return Commands.literal("section")
                .then(Commands.literal("entity").then(actions(Commands.argument("entities", EntityArgument.entities()), false)))
                .then(Commands.literal("block").then(actions(Commands.argument("pos", BlockPosArgument.blockPos()), true)));
    }

    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T actions(T target, boolean block) {
        for (var action : Action.values()) {
            var literal = Commands.literal(action.name().toLowerCase(Locale.ROOT));
            var location = Commands.argument("location", IdentifierArgument.id())
                    .executes(c -> execute(c, block, action, true));
            if (action.named()) {
                literal.then(Commands.argument("section", StringArgumentType.string())
                        .executes(c -> execute(c, block, action, false))
                        .then(location));
            } else {
                literal.executes(c -> execute(c, block, action, false)).then(location);
            }
            target.then(literal);
        }
        return target;
    }

    private static int execute(CommandContext<CommandSourceStack> context, boolean block, Action action, boolean location)
            throws CommandSyntaxException {
        var command = new EffectSectionCommand();
        command.action = action;
        if (action.named()) command.section = StringArgumentType.getString(context, "section");
        if (location) command.location = IdentifierArgument.getId(context, "location");
        if (block) {
            command.pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
            PacketDistributor.sendToPlayersTrackingChunk(context.getSource().getLevel(), ChunkPos.containing(command.pos), command);
        } else {
            command.entities = EntityArgument.getEntities(context, "entities").stream().mapToInt(e -> e.getId()).toArray();
            PacketDistributor.sendToAllPlayers(command);
        }
        return Command.SINGLE_SUCCESS;
    }

    public void encode(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(pos != null);
        if (pos != null) {
            buf.writeBlockPos(pos);
        } else {
            buf.writeVarIntArray(entities);
        }
        buf.writeEnum(action);
        buf.writeUtf(section);
        buf.writeBoolean(location != null);
        if (location != null) buf.writeIdentifier(location);
    }

    public static EffectSectionCommand decodePacket(RegistryFriendlyByteBuf buf) {
        var packet = new EffectSectionCommand();
        if (buf.readBoolean()) {
            packet.pos = buf.readBlockPos();
        } else {
            packet.entities = buf.readVarIntArray();
        }
        packet.action = buf.readEnum(Action.class);
        packet.section = buf.readUtf();
        if (buf.readBoolean()) packet.location = buf.readIdentifier();
        return packet;
    }

    public static void execute(EffectSectionCommand packet, IPayloadContext context) {
        if (LDLib2.isClient()) {
            Client.execute(packet, context);
        }
    }

    /** Only ever loaded on a client: everything touching effects (client classes) stays in here. */
    private static class Client {
        public static void execute(EffectSectionCommand packet, IPayloadContext context) {
            if (packet.pos != null) {
                apply(packet, BlockEffectExecutor.CACHE.get(packet.pos));
                return;
            }
            for (var id : packet.entities) {
                var entity = context.player().level().getEntity(id);
                if (entity != null) apply(packet, EntityEffectExecutor.CACHE.get(entity));
            }
        }

        private static void apply(EffectSectionCommand packet, @Nullable List<? extends FXEffectExecutor> effects) {
            if (effects == null) return;
            for (var effect : List.copyOf(effects)) {
                var runtime = effect.getRuntime();
                if (runtime != null && (packet.location == null || packet.location.equals(effect.getFx().getFxLocation()))) {
                    apply(packet, runtime.sections());
                }
            }
        }

        private static boolean apply(EffectSectionCommand packet, SectionControl sections) {
            return switch (packet.action) {
                case JUMP -> sections.jumpTo(packet.section);
                case QUEUE -> sections.queue(packet.section);
                case ADVANCE -> sections.advance();
                case RELEASE -> sections.release();
            };
        }
    }
}
