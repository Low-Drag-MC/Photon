package com.lowdragmc.photon.client.light;

import com.lowdragmc.photon.PhotonConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dynamic lights without Photon FX. Client render thread only.
 *
 * <pre>{@code
 * var lamp = PhotonLights.add(new DynamicLight().at(x, y, z).color(1f, .5f, .2f).intensity(12).range(10));
 * lamp.position.set(...);          // lights are live: change them whenever you like
 * PhotonLights.remove(lamp);
 *
 * var handle = PhotonLights.attach(entity, new DynamicLight().color(.3f, .6f, 1f).range(8), new Vec3(0, 1, 0));
 * PhotonLights.flash(new DynamicLight().at(x, y, z).color(1f, .8f, .4f).intensity(40).range(16), 10);
 * PhotonLights.addProvider((sink, partialTick) -> sink.next().at(x, y, z).range(4));
 * }</pre>
 */
@OnlyIn(Dist.CLIENT)
public final class PhotonLights {
    private static final Set<Handle> ATTACHED = new HashSet<>();
    private static final List<Flash> FLASHES = new ArrayList<>();
    private static final LightProvider FLASH_PROVIDER = PhotonLights::submitFlashes;

    private PhotonLights() {
    }

    /** A light that stays until {@link #remove}d. */
    public static DynamicLight add(DynamicLight light) {
        return DynamicLightManager.add(light);
    }

    public static boolean remove(DynamicLight light) {
        return DynamicLightManager.remove(light);
    }

    /** Submits lights every frame until {@link #removeProvider}; lights taken from the sink last one frame. */
    public static void addProvider(LightProvider provider) {
        DynamicLightManager.addGlobalProvider(provider);
    }

    public static void removeProvider(LightProvider provider) {
        DynamicLightManager.removeGlobalProvider(provider);
    }

    /**
     * Follows {@code entity} at {@code offset} until the entity is gone or the handle closed. The template
     * stays live: changing it changes the light.
     */
    public static Handle attach(Entity entity, DynamicLight template, Vec3 offset) {
        var handle = new Handle(entity);
        handle.provider = (sink, partialTick) -> {
            var position = entity.getPosition(partialTick);
            sink.next().set(template).at(position.x + offset.x, position.y + offset.y, position.z + offset.z);
        };
        ATTACHED.add(handle);
        DynamicLightManager.addGlobalProvider(handle.provider);
        return handle;
    }

    /** A copy of {@code template} that fades out over {@code durationTicks}. */
    public static void flash(DynamicLight template, int durationTicks) {
        var level = Minecraft.getInstance().level;
        if (level == null || durationTicks <= 0 || !isEnabled()) return;
        FLASHES.add(new Flash(new DynamicLight().set(template), level.getGameTime(), durationTicks));
        DynamicLightManager.addGlobalProvider(FLASH_PROVIDER);
    }

    public static boolean isEnabled() {
        return PhotonConfig.INSTANCE.dynamicLights.get();
    }

    /** Client tick: drops what has run out, whether or not anything is being drawn. */
    public static void tick() {
        var level = Minecraft.getInstance().level;
        ATTACHED.removeIf(handle -> {
            boolean gone = handle.entity.isRemoved() || handle.entity.level() != level;
            if (gone) handle.release();
            return gone;
        });
        long time = level == null ? Long.MAX_VALUE : level.getGameTime();
        FLASHES.removeIf(flash -> time - flash.start >= flash.duration);
        if (FLASHES.isEmpty()) {
            DynamicLightManager.removeGlobalProvider(FLASH_PROVIDER);
        }
    }

    /** Flashes are timed on the level's clock, so none may outlive it. */
    public static void onLevelUnload(LevelAccessor level) {
        FLASHES.clear();
        DynamicLightManager.removeGlobalProvider(FLASH_PROVIDER);
        ATTACHED.removeIf(handle -> {
            boolean gone = handle.entity.level() == level;
            if (gone) handle.release();
            return gone;
        });
    }

    private static void submitFlashes(LightSink sink, float partialTick) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        long time = level.getGameTime();
        for (var flash : FLASHES) {
            float fade = Math.clamp(1f - (time - flash.start + partialTick) / flash.duration, 0f, 1f);
            if (fade > 0) {
                sink.next().set(flash.light).intensity *= fade * fade;
            }
        }
    }

    private record Flash(DynamicLight light, long start, int duration) {
    }

    /** Stops an attached light early; it also stops by itself once the entity is gone. */
    public static final class Handle implements AutoCloseable {
        private final Entity entity;
        private LightProvider provider;
        private boolean closed;

        private Handle(Entity entity) {
            this.entity = entity;
        }

        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (closed) return;
            ATTACHED.remove(this);
            release();
        }

        private void release() {
            closed = true;
            DynamicLightManager.removeGlobalProvider(provider);
        }
    }
}
