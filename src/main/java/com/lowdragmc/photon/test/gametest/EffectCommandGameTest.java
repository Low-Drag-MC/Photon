package com.lowdragmc.photon.test.gametest;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.command.EffectSectionCommand;
import com.mojang.brigadier.CommandDispatcher;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@code /photon fx section} on the server side: every action parses and runs for both targets, and the payload decodes
 * back to the bytes it was sent as. This does not prove the command stays clear of client classes: the dev dedicated
 * dist has them on its classpath. The payload class keeps them out by construction (only its nested {@code Client}
 * names any).
 */
public final class EffectCommandGameTest {

    private static final String SECTION_COMMAND_RUNS = "fx_section_command_runs";
    private static final String SECTION_PAYLOAD_ROUND_TRIP = "fx_section_payload_round_trip";

    private EffectCommandGameTest() {}

    public static void registerFunctions() {
        PhotonGameTests.registerFunction(SECTION_COMMAND_RUNS, helper -> {
            var level = helper.getLevel();
            var source = level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
            var dispatcher = level.getServer().getCommands().getDispatcher();
            var pos = helper.absolutePos(BlockPos.ZERO);
            var block = "block " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
            var entity = "entity " + helper.spawn(EntityType.ARMOR_STAND, BlockPos.ZERO.above()).getUUID();
            var actions = List.of("jump Idle", "jump \"Two words\"", "queue Idle", "advance", "release");
            for (var target : List.of(block, entity)) {
                for (var action : actions) {
                    for (var location : List.of("", " photon:gametest/effect")) {
                        var command = "photon fx section " + target + " " + action + location;
                        if (!run(helper, dispatcher, source, command)) return;
                    }
                }
            }
            helper.succeed();
        });

        PhotonGameTests.registerFunction(SECTION_PAYLOAD_ROUND_TRIP, helper -> {
            var registries = helper.getLevel().registryAccess();
            List<Consumer<RegistryFriendlyByteBuf>> payloads = List.of(
                    buf -> {
                        buf.writeBoolean(true);
                        buf.writeBlockPos(new BlockPos(12, -40, 7));
                        buf.writeEnum(EffectSectionCommand.Action.QUEUE);
                        buf.writeUtf("Idle \"loop\"");
                        buf.writeBoolean(true);
                        buf.writeIdentifier(Identifier.fromNamespaceAndPath("photon", "gametest/effect"));
                    },
                    buf -> {
                        buf.writeBoolean(false);
                        buf.writeVarIntArray(new int[]{3, 70000});
                        buf.writeEnum(EffectSectionCommand.Action.ADVANCE);
                        buf.writeUtf("");
                        buf.writeBoolean(false);
                    });
            for (var write : payloads) {
                var sent = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
                write.accept(sent);
                var expected = bytes(sent);
                var packet = EffectSectionCommand.CODEC.decode(sent);
                if (sent.isReadable()) {
                    helper.fail(Component.literal("the payload left " + sent.readableBytes() + " bytes unread"));
                    return;
                }
                var resent = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
                EffectSectionCommand.CODEC.encode(resent, packet);
                if (!Arrays.equals(expected, bytes(resent))) {
                    helper.fail(Component.literal("the payload re-encodes as " + Arrays.toString(bytes(resent))
                            + ", not as " + Arrays.toString(expected)));
                    return;
                }
            }
            helper.succeed();
        });
    }

    private static boolean run(GameTestHelper helper, CommandDispatcher<CommandSourceStack> dispatcher,
                               CommandSourceStack source, String command) {
        try {
            var result = dispatcher.execute(command, source);
            if (result != 1) {
                helper.fail(Component.literal("/" + command + " returned " + result));
                return false;
            }
            return true;
        } catch (Exception e) {
            Photon.LOGGER.error("/{} failed", command, e);
            helper.fail(Component.literal("/" + command + " failed: " + e));
            return false;
        }
    }

    /** The readable bytes, left unread. */
    private static byte[] bytes(RegistryFriendlyByteBuf buf) {
        var out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }

    public static void register(RegisterGameTestsEvent event, Holder<TestEnvironmentDefinition<?>> environment) {
        var testData = PhotonGameTests.defaultTestData(environment);
        PhotonGameTests.registerFunctionTest(event, SECTION_COMMAND_RUNS, testData);
        PhotonGameTests.registerFunctionTest(event, SECTION_PAYLOAD_ROUND_TRIP, testData);
    }
}
