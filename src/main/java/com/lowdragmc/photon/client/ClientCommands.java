package com.lowdragmc.photon.client;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.compat.iris.IrisCompositeMode;
import com.lowdragmc.photon.client.compat.iris.IrisDiagnostics;
import com.lowdragmc.photon.client.compat.iris.IrisOverlay;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.lowdragmc.lowdraglib2.client.ClientCommands.createLiteral;

/**
 * @author KilaBash
 * @date 2023/2/9
 * @implNote ClientCommands
 */
@OnlyIn(Dist.CLIENT)
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
                                        accessor.getParticles().entrySet().removeIf(entry ->
                                                entry.getKey() instanceof ParticleQueueRenderType ||
                                                entry.getKey() == FXObject.NO_RENDER_RENDER_TYPE);
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
                        .then(Commands.literal("convert").requires(source -> source.hasPermission(2))
                                .executes(context -> {
                                    if (Minecraft.getInstance().player != null) {
                                        Minecraft.getInstance().player.sendSystemMessage(
                                                Component.literal("trying to convert photon 1 fx under the ")
                                                        .append(Component.literal("[ldlib2/assets/photon/fx_old]")
                                                                .withStyle(style -> style.withColor(0xff008000)
                                                                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE,
                                                                                LDLib2.getAssetsDir() + "/photon/fx_old")))
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
                                })),
                // shader-pack compatibility diagnostics: what layout did we resolve, and what had
                // to be approximated. Resolution does not need FX on screen, so a whole pack can be
                // characterised by loading it and running "status".
                (LiteralArgumentBuilder<S>) createLiteral("photon_iris")
                        .executes(context -> irisStatus(false))
                        .then(createLiteral("status").executes(context -> irisStatus(false)))
                        .then(createLiteral("probe").executes(context -> {
                            IrisCompat.invalidate();
                            return irisStatus(false);
                        }))
                        .then(createLiteral("dump").executes(context -> irisStatus(true)))
                        .then(createLiteral("overlay")
                                .executes(context -> setIrisOverlay(!IrisOverlay.isEnabled()))
                                .then(createLiteral("on").executes(context -> setIrisOverlay(true)))
                                .then(createLiteral("off").executes(context -> setIrisOverlay(false))))
                        .then(createLiteral("mode")
                                .then(createLiteral("auto").executes(context -> setIrisMode(null)))
                                .then(createLiteral("primary")
                                        .executes(context -> setIrisMode(IrisCompositeMode.PREMULTIPLIED_ACCUM)))
                                .then(createLiteral("after")
                                        .executes(context -> setIrisMode(IrisCompositeMode.AFTER_PACK)))
                                .then(createLiteral("scene")
                                        .executes(context -> setIrisMode(IrisCompositeMode.SCENE_REPLACE)))
                                .then(createLiteral("off")
                                        .executes(context -> setIrisMode(IrisCompositeMode.DISABLED))))
        );
    }

    private static int irisStatus(boolean toClipboard) {
        var lines = IrisDiagnostics.report(IrisCompat.diagnosticsTarget());
        lines.forEach(ClientCommands::feedback);
        var joined = String.join("\n", lines);
        Photon.LOGGER.info("Iris compatibility report:\n{}", joined);
        if (toClipboard) {
            Minecraft.getInstance().keyboardHandler.setClipboard(joined);
            feedback("photon_iris: copied to clipboard");
        }
        return 1;
    }

    private static int setIrisOverlay(boolean enabled) {
        IrisOverlay.setEnabled(enabled);
        feedback("photon_iris: overlay " + (enabled ? "on" : "off"));
        return 1;
    }

    private static int setIrisMode(@Nullable IrisCompositeMode mode) {
        IrisCompat.setModeOverride(mode);
        feedback("photon_iris: composite mode " + (mode == null ? "auto (config)" : mode.name()));
        return 1;
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
