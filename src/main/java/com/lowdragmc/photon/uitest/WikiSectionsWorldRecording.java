package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIRecording;
import com.lowdragmc.lowdraglib2.uitest.input.Keys;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.gui.editor.FXProject;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.GameType;
import com.mojang.blaze3d.platform.InputConstants;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.lowdragmc.photon.uitest.WikiEditor.say;

/** Wiki video of effects with sections driven from chat commands in a world. For a 3840x2160 window. */
@LDLRegisterClient(name = "wiki_photon_sections_world", group = "photon_wiki", registry = UIRecording.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class WikiSectionsWorldRecording implements UIRecording {
    private static final String DIR = "wiki";
    private static final AtomicReference<GameType> GAME_MODE = new AtomicReference<>();
    private static final AtomicReference<Boolean> SUGGESTIONS = new AtomicReference<>();
    private static final List<File> written = new ArrayList<>();

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("wiki", "photon").guiScale(4).scenarioTimeoutMs(600_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("no command suggestions: they would list this machine's effects", ctx -> {
                    var suggestions = ctx.mc().options.autoSuggestions();
                    SUGGESTIONS.compareAndSet(null, suggestions.get());
                    suggestions.set(false);
                })
                .step("write the effects", ctx -> {
                    write("circle", WikiDemos.magicCircle().fx());
                    write("laser", WikiDemos.laser().fx());
                })
                .server("a clear noon on open ground", sc -> {
                    var origin = LightSite.claim(sc.player());
                    var level = sc.level();
                    TestClock.set(level, 6000);
                    level.getServer().setWeatherParameters(12000, 0, false, false);
                    TestClock.setAdvancing(level, false);
                    // a spectator floats where it is put, so the view is exactly where it is aimed
                    GAME_MODE.compareAndSet(null, sc.player().gameMode.getGameModeForPlayer());
                    sc.player().setGameMode(GameType.SPECTATOR);
                    sc.player().teleportTo(level, origin.getX() + 0.5, origin.getY() + 1, origin.getZ() + 0.5, Set.<Relative>of(), 0, 24, true);
                })
                .ticks(10)
                .waitUntil("the world is drawn", ctx -> ctx.mc().levelRenderer.hasRenderedAllSections())
                .frames(10)
                .take("sections-commands", o -> o.size(1920, 1080).fps(30).poster(7_000), t -> {
                    say(t, "Play an effect that has sections. Here it is bound to the block four ahead.",
                            "播放一个带区段的特效，这里绑定在前方第四格的方块上。");
                    command(t, "photon fx photon:" + DIR + "/circle block ~ ~ ~4");
                    t.waitUntil("the circle plays", ctx -> runtimeOf("circle") != null)
                            .hold(1200);
                    say(t, "It appears, then its Idle section loops for as long as the effect stays.",
                            "它先播放出现动画，然后 Idle 区段一直循环，直到特效被停止。");
                    t.hold(3500);
                    say(t, "remove with force false stops it gently: its release section, Vanish, plays first.",
                            "remove 带 false 是温和停止：先播放它的 release 区段 Vanish。");
                    command(t, "photon fx remove block ~ ~ ~4 false");
                    t.waitUntil("the circle let go of the block", ctx -> runtimeOf("circle") == null)
                            .hold(3200);
                    say(t, "fx section moves a playing effect between its sections.",
                            "fx section 命令可以在播放中的特效的区段之间切换。");
                    command(t, "photon fx photon:" + DIR + "/laser block ~-3 ~-1 ~6");
                    t.timeoutMs(20_000)
                            .waitUntil("the laser fires", ctx -> isIn("laser", "Fire"))
                            .hold(1800);
                    say(t, "advance: Fire stops repeating and, after this pass, goes on to Stop.",
                            "advance：Fire 不再重复，这一遍播完后进入 Stop。");
                    command(t, "photon fx section block ~-3 ~-1 ~6 advance");
                    t.timeoutMs(20_000)
                            .waitUntil("the laser goes on to Stop", ctx -> isIn("laser", "Stop"))
                            .hold(2400);
                    say(t, "", "");
                    t.hold(400);
                });

        s.teardown("command suggestions back", ctx -> {
                    var was = SUGGESTIONS.getAndSet(null);
                    if (was != null) ctx.mc().options.autoSuggestions().set(was);
                })
                .teardown("stop the effects and delete them", ctx -> {
                    for (var effects : List.copyOf(BlockEffectExecutor.CACHE.values())) {
                        for (var effect : List.copyOf(effects)) {
                            if (effect.getRuntime() != null) effect.getRuntime().destroy(true);
                        }
                    }
                    for (var file : written) {
                        if (!file.delete()) file.deleteOnExit();
                    }
                    var dir = new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/fx/" + DIR);
                    var left = dir.list();
                    if (left != null && left.length == 0 && !dir.delete()) dir.deleteOnExit();
                    written.clear();
                    FXHelper.clearCache();
                })
                .teardownServer("put the world back", sc -> {
                    var mode = GAME_MODE.getAndSet(null);
                    if (mode != null) sc.player().setGameMode(mode);
                    LightSite.release(sc.player());
                });
    }

    @Nullable
    private static FXRuntime runtimeOf(String name) {
        for (var effects : BlockEffectExecutor.CACHE.values()) {
            for (var effect : effects) {
                if (effect.getFx().getFxLocation().getPath().endsWith("/" + name)) return effect.getRuntime();
            }
        }
        return null;
    }

    private static boolean isIn(String effect, String section) {
        var runtime = runtimeOf(effect);
        return runtime != null && section.equals(runtime.sections().current());
    }

    /** Opens the chat, types the command and sends it. */
    private static void command(ScenarioBuilder t, String command) {
        // synthetic keys only reach an open screen, so T is shown but the chat is opened here
        t.key(InputConstants.KEY_T)
                .step("open the chat", ctx -> ctx.mc().gui.setScreen(new ChatScreen("", false)))
                .waitUntil("the chat is open", ctx -> ctx.mc().gui.screen() instanceof ChatScreen)
                .type("/" + command)
                .hold(350)
                .key(Keys.ENTER)
                .waitUntil("the chat is closed", ctx -> !(ctx.mc().gui.screen() instanceof ChatScreen));
    }

    private static void write(String name, FX fx) {
        var file = new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/fx/" + DIR + "/" + name + FX.SUFFIX);
        if (file.exists() && !written.contains(file)) {
            throw new IllegalStateException(file + " already exists; not overwriting it");
        }
        try {
            var tag = fx.serializeNBT(Platform.getFrozenRegistry());
            tag.putInt("version", FXProject.VERSION);
            Files.createDirectories(file.getParentFile().toPath());
            NbtIo.writeCompressed(tag, file.toPath());
            written.add(file);
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + file, e);
        }
        FXHelper.clearCache();
    }
}
