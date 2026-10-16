package com.lowdragmc.photon.client.light.dev;

import com.lowdragmc.photon.client.light.DynamicLightManager;
import com.lowdragmc.photon.client.light.DynamicLightRenderer;
import com.lowdragmc.photon.client.light.LightDebug;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Locale;

/**
 * {@code /photonlight}: the demo scene, its haze and fog, and the debug views, registered in a dev environment only.
 * The light settings themselves live in the client config.
 */
@OnlyIn(Dist.CLIENT)
public final class LightCommands {
    private static final List<String> DEBUG_VIEWS = List.of("off", "light", "normal", "shadow", "albedo", "clusters", "volume");

    private LightCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("photonlight")
                .then(Commands.literal("demo")
                        .executes(context -> demo(true))
                        .then(Commands.literal("lights").executes(context -> demo(false))))
                .then(Commands.literal("clear").executes(context -> {
                    LightDemoScene.stopLights();
                    feedback("demo lights removed");
                    return 1;
                }))
                .then(Commands.literal("view")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        LightDemoScene.VIEWS.stream().map(LightDemoScene.View::name), builder))
                                .executes(context -> view(StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("debug")
                        .then(Commands.argument("view", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(DEBUG_VIEWS, builder))
                                .executes(context -> debug(StringArgumentType.getString(context, "view")))))
                .then(Commands.literal("markers")
                        .then(Commands.literal("on").executes(context -> markers(true)))
                        .then(Commands.literal("off").executes(context -> markers(false))))
                .then(Commands.literal("freeze")
                        .executes(context -> freeze(currentSeconds()))
                        .then(Commands.argument("seconds", FloatArgumentType.floatArg(0f))
                                .executes(context -> freeze(FloatArgumentType.getFloat(context, "seconds")))))
                .then(Commands.literal("unfreeze").executes(context -> freeze(-1f)))
                .then(Commands.literal("volumetric")
                        .executes(context -> volumetric(LightDemoScene.volumetric > 0 ? 0f : 1f))
                        .then(Commands.argument("strength", FloatArgumentType.floatArg(0f, 16f))
                                .executes(context -> volumetric(FloatArgumentType.getFloat(context, "strength")))))
                .then(Commands.literal("fog")
                        .executes(context -> fog(LightDemoScene.fogDensity > 0 ? 0f : 1f))
                        .then(Commands.argument("density", FloatArgumentType.floatArg(0f, 16f))
                                .executes(context -> fog(FloatArgumentType.getFloat(context, "density")))))
                .then(Commands.literal("stats").executes(context -> {
                    double gpu = DynamicLightRenderer.gpuMillis();
                    feedback("%d lights (%d shadowed, %d volumetric), %d fog volumes, %d visibility maps, GPU %s, CPU %.3f ms, %d voxel bricks"
                            .formatted(DynamicLightRenderer.lastLightCount(), DynamicLightRenderer.lastShadowedCount(),
                                    DynamicLightRenderer.lastVolumeCount(), DynamicLightRenderer.lastFogCount(),
                                    DynamicLightRenderer.lastVisibilityMapCount(),
                                    gpu < 0 ? "n/a" : "%.3f ms".formatted(gpu), DynamicLightRenderer.prepareMillis(),
                                    bricks()));
                    return 1;
                }));
    }

    private static int demo(boolean build) {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        var player = mc.player;
        if (player == null) return 0;
        var origin = build || LightDemoScene.ground() == null ? player.blockPosition().below() : LightDemoScene.ground();
        LightDemoScene.startLights(origin);
        LightDebug.markers = true;
        if (!build) {
            feedback("demo lights started");
            return 1;
        }
        if (server == null) {
            feedback("building the demo needs singleplayer; lights started without it");
            return 0;
        }
        var uuid = player.getUUID();
        server.execute(() -> {
            var serverPlayer = server.getPlayerList().getPlayer(uuid);
            if (serverPlayer == null) return;
            LightDemoScene.build(serverPlayer.serverLevel(), origin);
            LightDemoScene.teleport(serverPlayer, origin, LightDemoScene.VIEWS.getFirst());
        });
        feedback("demo built at " + origin.toShortString() + "; /photonlight view <name> to look around, "
                + "/photonlight volumetric and /photonlight fog for the haze");
        return 1;
    }

    private static int view(String name) {
        var view = LightDemoScene.view(name);
        var origin = LightDemoScene.ground();
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (view == null || origin == null || server == null || mc.player == null) {
            feedback("run /photonlight demo first; views: "
                    + LightDemoScene.VIEWS.stream().map(LightDemoScene.View::name).toList());
            return 0;
        }
        var uuid = mc.player.getUUID();
        server.execute(() -> {
            var serverPlayer = server.getPlayerList().getPlayer(uuid);
            if (serverPlayer != null) LightDemoScene.teleport(serverPlayer, origin, view);
        });
        return 1;
    }

    private static int debug(String name) {
        int view = DEBUG_VIEWS.indexOf(name.toLowerCase(Locale.ROOT));
        if (view < 0) {
            feedback("debug views: " + DEBUG_VIEWS);
            return 0;
        }
        LightDebug.view = view;
        feedback("debug view " + DEBUG_VIEWS.get(view));
        return 1;
    }

    private static int markers(boolean on) {
        LightDebug.markers = on;
        feedback("light markers " + (on ? "on" : "off"));
        return 1;
    }

    private static int freeze(float seconds) {
        LightDemoScene.frozenSeconds = seconds;
        feedback(seconds < 0 ? "demo lights running" : "demo lights frozen at %.2f s".formatted(seconds));
        return 1;
    }

    private static int volumetric(float strength) {
        LightDemoScene.volumetric = strength;
        feedback((strength > 0 ? "demo lights glow in the air at strength %.2f; /photonlight debug volume shows the haze alone"
                .formatted(strength) : "demo lights' glow off") + demoHint());
        return 1;
    }

    private static int fog(float density) {
        LightDemoScene.fogDensity = density;
        feedback((density > 0 ? "fog over the courtyard at density %.2f".formatted(density) : "courtyard fog removed") + demoHint());
        return 1;
    }

    private static String demoHint() {
        return LightDemoScene.ground() == null ? "; run /photonlight demo to see it" : "";
    }

    private static int bricks() {
        var level = Minecraft.getInstance().level;
        var voxels = level == null ? null : DynamicLightManager.voxels(level);
        return voxels == null ? 0 : voxels.brickCount();
    }

    private static float currentSeconds() {
        var level = Minecraft.getInstance().level;
        return level == null ? 0f : level.getGameTime() / 20f;
    }

    private static void feedback(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(Component.literal("photonlight: " + message));
        }
    }
}
