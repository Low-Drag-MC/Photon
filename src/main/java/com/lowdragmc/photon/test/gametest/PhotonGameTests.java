package com.lowdragmc.photon.test.gametest;

import com.lowdragmc.photon.Photon;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Consumer;

/**
 * Central registration entrypoint for Photon GameTests (the 26.1 TEST_FUNCTION registry pattern,
 * mirroring KilaGraph's {@code KGGameTests}). Run headlessly via {@code runGameTestServer} or
 * in-game via {@code /test} — client-registry-dependent tests skip gracefully on the dedicated
 * server dist and run fully on the client.
 */
public final class PhotonGameTests {

    private static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, Photon.MOD_ID);
    private static boolean initialized = false;

    private PhotonGameTests() {}

    public static void init(IEventBus eventBus) {
        if (initialized) return;
        initialized = true;

        FXSerializationGameTest.registerFunctions();
        FXProjectImportGameTest.registerFunctions();

        TEST_FUNCTIONS.register(eventBus);
        eventBus.addListener(PhotonGameTests::registerGameTests);
    }

    private static void registerGameTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
                id("fx"),
                new TestEnvironmentDefinition.AllOf()
        );
        FXSerializationGameTest.register(event, environment);
        FXProjectImportGameTest.register(event, environment);
    }

    public static void registerFunction(String path, Consumer<GameTestHelper> function) {
        TEST_FUNCTIONS.register(path, () -> function);
    }

    public static ResourceKey<Consumer<GameTestHelper>> functionKey(String path) {
        return ResourceKey.create(Registries.TEST_FUNCTION, id(path));
    }

    /** Reuse LDLib2's empty structure — FX tests don't need world blocks. */
    public static TestData<Holder<TestEnvironmentDefinition<?>>> defaultTestData(
            Holder<TestEnvironmentDefinition<?>> environment) {
        return new TestData<>(
                environment,
                Identifier.fromNamespaceAndPath("ldlib2", "empty"),
                20,
                0,
                true,
                Rotation.NONE,
                false,
                1,
                1,
                false,
                0
        );
    }

    public static void registerFunctionTest(
            RegisterGameTestsEvent event,
            String path,
            TestData<Holder<TestEnvironmentDefinition<?>>> testData
    ) {
        event.registerTest(id(path), new FunctionGameTestInstance(functionKey(path), testData));
    }

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Photon.MOD_ID, path);
    }
}
