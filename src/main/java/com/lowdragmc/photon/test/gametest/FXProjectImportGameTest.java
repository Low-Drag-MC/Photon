package com.lowdragmc.photon.test.gametest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.gui.editor.FXProject;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Loads every {@code .fxproj} under the local LDLib2 assets dir through the real editor load path
 * ({@code ProjectType.loadProjectFromFile} → version datafix → deserialize). Strict: any load
 * failure fails the test (failures are also logged with full stack traces — grep the run log for
 * {@code [fxproj-survey]}). This is the regression net for 1.21-format compatibility.
 */
public final class FXProjectImportGameTest {

    private static final String LOCAL_PROJECT_SURVEY = "local_fxproj_survey";

    private FXProjectImportGameTest() {}

    public static void registerFunctions() {
        PhotonGameTests.registerFunction(LOCAL_PROJECT_SURVEY, helper -> {
            var files = LDLib2.getAssetsDir().listFiles((dir, name) -> name.endsWith(".fxproj"));
            if (files == null || files.length == 0) {
                Photon.LOGGER.info("[fxproj-survey] no local .fxproj files, nothing to survey");
                helper.succeed();
                return;
            }
            int ok = 0;
            int failed = 0;
            for (var file : files) {
                try {
                    FXProject.TYPE.loadProjectFromFile(file);
                    ok++;
                    Photon.LOGGER.info("[fxproj-survey] OK     {}", file.getName());
                } catch (Throwable t) {
                    failed++;
                    Photon.LOGGER.error("[fxproj-survey] FAILED {}", file.getName(), t);
                }
            }
            Photon.LOGGER.info("[fxproj-survey] done: {} ok, {} failed (of {})", ok, failed, files.length);
            if (failed > 0) {
                helper.fail(Component.literal(
                        failed + " of " + files.length + " local .fxproj files failed to load — see [fxproj-survey] in the log"));
            } else {
                helper.succeed();
            }
        });
    }

    public static void register(RegisterGameTestsEvent event,
                                Holder<TestEnvironmentDefinition<?>> environment) {
        PhotonGameTests.registerFunctionTest(event, LOCAL_PROJECT_SURVEY, PhotonGameTests.defaultTestData(environment));
    }
}
