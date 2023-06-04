package com.lowdragmc.lowdraglib;

import dev.architectury.injectables.annotations.ExpectPlatform;

public class Platform {

    @ExpectPlatform
    public static String platformName() {
        // Just throw an error, the content should get replaced at runtime.
        throw new AssertionError();
    }
}
