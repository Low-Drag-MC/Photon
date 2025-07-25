package com.lowdragmc.photon.client;

import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.systems.RenderSystem;
import lombok.experimental.UtilityClass;

import java.lang.ref.Cleaner;
import java.util.Objects;

@UtilityClass
public final class AutoCloseCleaner {
    private static final Cleaner cleaner = Cleaner.create();

    public static <T extends AutoCloseable> Cleaner.Cleanable register(Object owner, T resource) {
        return register(owner, resource, false);
    }

    public static <T extends AutoCloseable> Cleaner.Cleanable registerRenderThread(Object owner, T resource) {
        return register(owner, resource, true);
    }

    public static <T extends AutoCloseable> Cleaner.Cleanable register(Object owner, T resource, boolean requireRenderThread) {
        Objects.requireNonNull(resource, "resource cannot be null");
        return cleaner.register(owner, () -> {
            if (requireRenderThread && !RenderSystem.isOnRenderThread()) {
                RenderSystem.recordRenderCall(() -> closeResource(resource));
            } else {
                closeResource(resource);
            }
        });
    }

    private static void closeResource(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            Photon.LOGGER.error("Failed to close resource: {}", resource, e);
        }
    }
}