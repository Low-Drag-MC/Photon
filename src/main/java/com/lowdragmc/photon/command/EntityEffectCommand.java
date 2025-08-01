package com.lowdragmc.photon.command;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.EntityEffectExecutor;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import lombok.NoArgsConstructor;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote EntityEffectCommand
 */
@NoArgsConstructor
public class EntityEffectCommand extends EffectCommand {
    public static final ResourceLocation ID = Photon.id("entity_effect_command");
    public static final Type<EntityEffectCommand> TYPE = new Type<>(ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, EntityEffectCommand> CODEC = StreamCodec.ofMember(EntityEffectCommand::encode, EntityEffectCommand::decodePacket);

    @Setter
    protected List<Entity> entities;
    // client
    private int[] ids = new int[0];
    @Setter
    private EntityEffectExecutor.AutoRotate autoRotate = EntityEffectExecutor.AutoRotate.NONE;

    @Override
    @Nonnull
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class AutoRotateType implements ArgumentType<EntityEffectExecutor.AutoRotate> {
        private static final Collection<String> EXAMPLES = Arrays.asList("none", "forward", "look", "xrot");

        public AutoRotateType() {
        }

        public static EntityEffectExecutor.AutoRotate getValue(final CommandContext<?> context, final String name) {
            return context.getArgument(name, EntityEffectExecutor.AutoRotate.class);
        }

        @Override
        public EntityEffectExecutor.AutoRotate parse(final StringReader reader) throws CommandSyntaxException {
            return EntityEffectExecutor.AutoRotate.valueOf(reader.readString().toUpperCase());
        }

        @Override
        public <S> CompletableFuture<Suggestions> listSuggestions(final CommandContext<S> context, final SuggestionsBuilder builder) {
            if ("none".startsWith(builder.getRemainingLowerCase())) {
                builder.suggest("none");
            }
            if ("forward".startsWith(builder.getRemainingLowerCase())) {
                builder.suggest("forward");
            }
            if ("look".startsWith(builder.getRemainingLowerCase())) {
                builder.suggest("look");
            }
            if ("xrot".startsWith(builder.getRemainingLowerCase())) {
                builder.suggest("xrot");
            }
            return builder.buildFuture();
        }

        @Override
        public Collection<String> getExamples() {
            return EXAMPLES;
        }
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createServerCommand() {
        return Commands.literal("entity")
                .then(Commands.argument("entities", EntityArgument.entities())
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
                                                                        .then(Commands.argument("auto rotate", new AutoRotateType())
                                                                                .executes(c -> execute(c, true, true, true, true, true, true, true))
                                                                        )))))
                                        .then(Commands.argument("delay", IntegerArgumentType.integer(0))
                                                .executes(c -> execute(c, true, true, false, true, false, false, false))
                                                .then(Commands.argument("force death", BoolArgumentType.bool())
                                                        .executes(c -> execute(c, true, true, false, true, true, false, false))
                                                        .then(Commands.argument("allow multi", BoolArgumentType.bool())
                                                                .executes(c -> execute(c, true, true, false, true, true, true, false))))))));
    }

    private static int execute(CommandContext<CommandSourceStack> context,
                               boolean offset,
                               boolean rotation,
                               boolean scale,
                               boolean delay,
                               boolean forceDeath,
                               boolean allowMulti,
                               boolean autoRotate
                               ) throws CommandSyntaxException {
        var command = new EntityEffectCommand();
        command.setLocation(ResourceLocationArgument.getId(context, "location"));
        command.setEntities(EntityArgument.getEntities(context, "entities").stream().map(e -> (Entity) e).toList());
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
        if (autoRotate) {
            command.setAutoRotate(AutoRotateType.getValue(context, "auto rotate"));
        }
        PacketDistributor.sendToAllPlayers(command);
        return Command.SINGLE_SUCCESS;
    }

    @Override
    public void encode(RegistryFriendlyByteBuf buf) {
        super.encode(buf);
        buf.writeEnum(autoRotate);
        buf.writeVarInt(entities.size());
        for (Entity entity : entities) {
            buf.writeVarInt(entity.getId());
        }
    }

    @Override
    public void decode(RegistryFriendlyByteBuf buf) {
        super.decode(buf);
        autoRotate = buf.readEnum(EntityEffectExecutor.AutoRotate.class);
        ids = new int[buf.readVarInt()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = buf.readVarInt();
        }
    }

    public static EntityEffectCommand decodePacket(RegistryFriendlyByteBuf buf) {
        var packet = new EntityEffectCommand();
        packet.decode(buf);
        return packet;
    }

    public static void execute(EntityEffectCommand packet, IPayloadContext context) {
        if (LDLib2.isClient()) {
            Client.execute(packet, context);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static class Client {
        public static void execute(EntityEffectCommand packet, IPayloadContext context) {
            var level = Minecraft.getInstance().level;
            if (level != null) {
                var fx = FXHelper.getFX(packet.location);
                if (fx != null) {
                    for (var id : packet.ids) {
                        var entity = level.getEntity(id);
                        if (entity != null) {
                            var effect = new EntityEffectExecutor(fx, level, entity, packet.autoRotate);
                            var offset = packet.offset;
                            var rotation = packet.rotation;
                            var scale = packet.scale;
                            effect.setOffset(offset.x, offset.y, offset.z);
                            effect.setRotation(rotation.x, rotation.y, rotation.z);
                            effect.setScale(scale.x, scale.y, scale.z);
                            effect.setDelay(packet.delay);
                            effect.setForcedDeath(packet.forcedDeath);
                            effect.setAllowMulti(packet.allowMulti);
                            effect.start();
                        }
                    }
                }
            }
        }
    }

}
