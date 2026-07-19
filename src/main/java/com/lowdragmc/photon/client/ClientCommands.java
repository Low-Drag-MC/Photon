package com.lowdragmc.photon.client;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.photon.client.fx.compat.FXCompat;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.ParticleQueueRenderType;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.EntityEffectExecutor;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.fx.VanillaParticleHost;
import com.lowdragmc.photon.client.postfx.PhotonPostFX;
import com.lowdragmc.photon.core.mixins.accessor.ParticleEngineAccessor;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

import static com.lowdragmc.lowdraglib2.client.ClientCommands.createLiteral;

/**
 * @author KilaBash
 * @date 2023/2/9
 * @implNote ClientCommands
 */
public class ClientCommands {

    @SuppressWarnings("unchecked")
    public static <S> List<LiteralArgumentBuilder<S>> createClientCommands() {
        return List.of(
                (LiteralArgumentBuilder<S>) createLiteral("photon_editor").executes(context -> {
                    if (Platform.getMinecraftServer() != null && !Platform.getMinecraftServer().isSingleplayer()) {
                        context.getSource().sendFailure(Component.literal("This command can only be used in singleplayer"));
                        return 0;
                    }
                    var minecraft = Minecraft.getInstance();
                    var entityPlayer = minecraft.player;
                    if (entityPlayer == null) return 0;
                    var ui = new ModularUI(UI.of(EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                            .shouldCloseOnEsc(false)
                            .shouldCloseOnKeyInventory(false);
                    var screen = new ModularUIScreen(ui, Component.empty());
                    minecraft.setScreen(screen);
                    return 1;
                }),
                // post-effect smoke test: keeps requesting the effect every frame until "clear".
                // The effect path is a builtin fullscreen-graph name (e.g. "invert") or a full
                // "type(path)" resource path.
                (LiteralArgumentBuilder<S>) createLiteral("photonfx")
                        .then(createLiteral("test")
                                .then(Commands.argument("effect", StringArgumentType.string())
                                        .executes(context -> startTestEffect(context, 1f))
                                        .then(Commands.argument("weight", FloatArgumentType.floatArg(0f, 1f))
                                                .executes(context -> startTestEffect(context,
                                                        FloatArgumentType.getFloat(context, "weight"))))))
                        .then(createLiteral("clear")
                                .executes(context -> {
                                    PhotonPostFX.clearTestEffect();
                                    feedback("photonfx: test effect cleared");
                                    return 1;
                                }))
                        .then(createLiteral("list")
                                .executes(context -> {
                                    var paths = PhotonPostFX.listEffectPaths();
                                    feedback("photonfx: %d effect(s) available:".formatted(paths.size()));
                                    paths.forEach(path -> feedback("  " + path));
                                    return 1;
                                })),
                (LiteralArgumentBuilder<S>) createLiteral("photon_client")
                        .then(createLiteral("clear_particles")
                                .executes(context -> {
                                    if (Minecraft.getInstance().particleEngine instanceof ParticleEngineAccessor accessor) {
                                        // TODO(M1): once Photon registers its own ParticleGroup, remove only that
                                        // group; until then all FX live in the shared NO_RENDER group.
                                        accessor.getParticles().entrySet().removeIf(entry ->
                                                entry.getKey() == net.minecraft.client.particle.ParticleRenderType.NO_RENDER);
                                    }
                                    VanillaParticleHost.onWipe(); // cached FXRuntimes turn invalid immediately
                                    EntityEffectExecutor.CACHE.clear();
                                    BlockEffectExecutor.CACHE.clear();
                                    return 1;
                                }))
                        .then(createLiteral("clear_client_fx_cache")
                                .executes(context -> {
                                    if (Minecraft.getInstance().player != null) {
                                        Minecraft.getInstance().player.sendSystemMessage(Component.literal("clear client cache fx: " + FXHelper.clearCache()));
                                    } else {
                                        FXHelper.clearCache();
                                    }
                                    return 1;
                                }))
                        .then(Commands.literal("convert").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(context -> {
                                    if (Minecraft.getInstance().player != null) {
                                        Minecraft.getInstance().player.sendSystemMessage(
                                                Component.literal("trying to convert photon 1 fx under the ")
                                                        .append(Component.literal("[ldlib2/assets/photon/fx_old]")
                                                                .withStyle(style -> style.withColor(0xff008000)
                                                                        .withClickEvent(new ClickEvent.OpenFile(LDLib2.getAssetsDir() + "/photon/fx_old")))
                                                        )
                                                        .append(Component.literal(" folder"))
                                        );
                                    }
                                    var converted = FXCompat.convertFX();
                                    if (Minecraft.getInstance().player != null) {
                                        Minecraft.getInstance().player.sendSystemMessage(
                                                Component.literal("convert result: " + converted)
                                        );
                                    }
                                    return 1;
                                }))
        );
    }

    private static <S> int startTestEffect(CommandContext<S> context, float weight) {
        var text = StringArgumentType.getString(context, "effect");
        var path = PhotonPostFX.parsePath(text);
        if (path == null) {
            feedback("photonfx: cannot parse effect path '" + text + "'");
            return 0;
        }
        PhotonPostFX.setTestEffect(path, weight);
        feedback("photonfx: testing '" + text + "' at weight " + weight + " (stop with /photonfx clear)");
        return 1;
    }

    private static void feedback(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
