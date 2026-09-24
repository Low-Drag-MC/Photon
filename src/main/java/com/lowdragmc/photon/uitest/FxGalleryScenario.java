package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.ShaderGraphMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.UIResourceMaterial;
import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Plays every loadable effect (fxpacks and the workspace pack) and fails on any logged error or shader compile
 * failure. Slow, so it is only run when selected by name.
 */
@LDLRegisterClient(name = "fx_gallery", group = "photon_gallery", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FxGalleryScenario implements UIScenario {

    private static final int FRAMES_PER_EFFECT = 45;
    private static final String APPENDER = "PhotonFxGallery";

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("gallery");
    }

    @Override
    public void define(ScenarioBuilder s) {
        // -PphotonGalleryFilter=<regex> narrows the run
        var filter = System.getProperty("photon.gallery.filter");
        var effects = filter == null || filter.isBlank() ? FXHelper.listAllFX()
                : FXHelper.listAllFX().stream().filter(id -> id.toString().matches(filter)).toList();
        s.step("collect errors while the effects play", ctx -> {
            ctx.check("there are effects to play", !effects.isEmpty(), "> 0", effects.size());
            Collector.install();
        });
        s.step("look ahead, slightly down", ctx -> {
            var player = ctx.mc().player;
            ctx.require("the client player exists", player != null);
            player.setXRot(10f);
        });
        s.frames(4);

        for (var id : effects) {
            // screenshots are taken from the next frame, so the check must be a separate step
            s.step("play " + id, ctx -> play(ctx, id))
                    .frames(FRAMES_PER_EFFECT)
                    .screenshot(label(id))
                    .step("check " + id, ctx -> {
                        ctx.log(id + (alive(ctx) ? " was still playing at its capture" : " had finished before its capture"));
                        var errors = new ArrayList<>(Collector.drain());
                        // failed shaders are logged only once, so also ask the materials
                        errors.addAll(materialErrors(ctx));
                        ctx.check(id + " played without errors", errors.isEmpty(), "no errors",
                                errors.isEmpty() ? "none" : String.join(" | ", errors));
                        stop(ctx);
                    });
        }
        s.teardown("stop the last effect", FxGalleryScenario::stop)
                .teardown("stop collecting errors", ctx -> Collector.uninstall());
    }

    private static void play(TestContext ctx, Identifier id) {
        stop(ctx);
        Collector.drain();
        var fx = FXHelper.getFX(id);
        ctx.check(id + " loads", fx != null, "an FX", "null");
        if (fx == null) {
            return;
        }
        var player = ctx.mc().player;
        ctx.require("the client player exists", player != null);
        var pos = player.blockPosition().relative(player.getDirection(), 8);
        var executor = new BlockEffectExecutor(fx, player.level(), pos);
        executor.setOffset(0, 1.5, 0);
        executor.setAllowMulti(false);
        executor.start();
        ctx.put("galleryExecutor", executor);
    }

    /** Every shader material of the playing effect that recorded a compile failure. */
    private static List<String> materialErrors(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("galleryExecutor");
        if (executor == null || executor.getRuntime() == null) {
            return List.of();
        }
        var out = new ArrayList<String>();
        for (var object : executor.getRuntime().objects.values()) {
            if (!(object instanceof Emitter emitter)) {
                continue;
            }
            for (var setting : emitter.rendererRuntime().getMaterials()) {
                var material = setting.getMaterial();
                if (material instanceof UIResourceMaterial resource) {
                    material = resource.getRawMaterial();
                }
                if (material instanceof CustomShaderMaterial custom && custom.isCompiledError()) {
                    out.add("custom shader " + custom.getShaderLocation() + ": " + custom.getCompiledErrorMessage());
                } else if (material instanceof ShaderGraphMaterial graph && graph.isCompiledError()) {
                    out.add("shader graph: " + graph.getCompiledErrorMessage());
                }
            }
        }
        return out;
    }

    private static boolean alive(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("galleryExecutor");
        return executor != null && executor.getRuntime() != null && executor.getRuntime().isAlive();
    }

    private static void stop(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("galleryExecutor");
        if (executor != null && executor.getRuntime() != null) {
            executor.getRuntime().destroy(true);
        }
        ctx.state().remove("galleryExecutor");
    }

    private static String label(Identifier id) {
        return (id.getNamespace() + "_" + id.getPath()).replaceAll("[^a-zA-Z0-9_.-]", "_").toLowerCase(Locale.ROOT);
    }

    /** Collects ERROR events — and compile-failure warnings — from every logger while installed. */
    private static final class Collector extends AbstractAppender {
        private static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());

        private Collector() {
            super(APPENDER, null, null, true, Property.EMPTY_ARRAY);
        }

        static void install() {
            var context = (LoggerContext) LogManager.getContext(false);
            var configuration = context.getConfiguration();
            if (configuration.getAppender(APPENDER) != null) {
                return;
            }
            var appender = new Collector();
            appender.start();
            configuration.addAppender(appender);
            configuration.getRootLogger().addAppender(appender, Level.WARN, null);
            context.updateLoggers();
        }

        static void uninstall() {
            var context = (LoggerContext) LogManager.getContext(false);
            var configuration = context.getConfiguration();
            configuration.getRootLogger().removeAppender(APPENDER);
            var appender = configuration.getAppender(APPENDER);
            if (appender != null) {
                appender.stop();
            }
            context.updateLoggers();
        }

        static List<String> drain() {
            synchronized (EVENTS) {
                var copy = List.copyOf(EVENTS);
                EVENTS.clear();
                return copy;
            }
        }

        @Override
        public void append(LogEvent event) {
            var message = event.getMessage().getFormattedMessage();
            var compileFailure = message.contains("ompile") && (message.contains("fail") || message.contains("Couldn't"));
            if (!event.getLevel().isMoreSpecificThan(Level.ERROR) && !compileFailure) {
                return;
            }
            var text = "[" + event.getLoggerName() + "] " + message;
            var thrown = event.getThrown();
            if (thrown != null) {
                text += " (" + thrown + ")";
            }
            EVENTS.add(text.length() > 400 ? text.substring(0, 400) : text);
        }
    }
}
