package com.lowdragmc.photon.client.gameobject.emitter.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code PhotonData} record layout is MIRRORED in {@code assets/photon/shaders/include/particle.glsl}
 * ({@code PHOTON_DATA_TEXELS} + the {@code photon_data_*()} slot/component reads). Nothing at runtime can
 * detect a drift — a moved slot just makes every shadergraph read the wrong float — so pin the packing here.
 */
class PhotonGpuChannelsPackingTest {

    /** {@code #define PHOTON_DATA_TEXELS} per instanced variant in particle.glsl. */
    @Test
    void declaredSlotCountsMatchTheShader() {
        assertEquals(5, PhotonGpuChannels.declaredSlotCount(PhotonGpuChannels.Kind.TILE));
        assertEquals(5, PhotonGpuChannels.declaredSlotCount(PhotonGpuChannels.Kind.TILE_MODEL));
        assertEquals(4, PhotonGpuChannels.declaredSlotCount(PhotonGpuChannels.Kind.TRAIL));
        assertEquals(4, PhotonGpuChannels.declaredSlotCount(PhotonGpuChannels.Kind.ARA_TRAIL));
        assertEquals(3, PhotonGpuChannels.declaredSlotCount(PhotonGpuChannels.Kind.BEAM));
    }

    /** The tile slots the {@code photon_data_*()} accessors read (PARTICLE_INSTANCE branch). */
    @Test
    void tilePackingMatchesTheShaderAccessors() {
        assertSlot(PhotonGpuChannels.Kind.TILE, "addition_gpu_data.random", 0, 0);
        assertSlot(PhotonGpuChannels.Kind.TILE, "addition_gpu_data.t", 0, 1);
        assertSlot(PhotonGpuChannels.Kind.TILE, "addition_gpu_data.age", 0, 2);
        assertSlot(PhotonGpuChannels.Kind.TILE, "addition_gpu_data.lifetime", 0, 3);
        assertSlot(PhotonGpuChannels.Kind.TILE, "addition_gpu_data.position", 1, 0);
        assertSlot(PhotonGpuChannels.Kind.TILE, "addition_gpu_data.isCollided", 1, 3);
        assertSlot(PhotonGpuChannels.Kind.TILE, "addition_gpu_data.velocity", 2, 0);
        assertSlot(PhotonGpuChannels.Kind.TILE, "addition_gpu_data.emitter_t", 2, 3);
        assertSlot(PhotonGpuChannels.Kind.TILE, "addition_gpu_data.emitter_age", 3, 0);
        assertSlot(PhotonGpuChannels.Kind.TILE, "addition_gpu_data.emitter_position", 3, 1);
        assertSlot(PhotonGpuChannels.Kind.TILE, "addition_gpu_data.emitter_velocity", 4, 0);
    }

    /** The custom-data stride is {@code #define PHOTON_CUSTOM_TEXELS} in particle.glsl. */
    @Test
    void customDataStrideMatchesTheShader() {
        assertEquals(4, AdditionalGPUDataSetting.MAX_CUSTOM_DATA);
    }

    private static void assertSlot(PhotonGpuChannels.Kind kind, String channelId, int slot, int component) {
        var channel = PhotonGpuChannels.CHANNELS.stream()
                .filter(c -> c.id().equals(channelId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no such channel: " + channelId));
        assertTrue(channel.supported().contains(kind), channelId + " must be supported by " + kind);
        var ref = PhotonGpuChannels.slotOf(kind, channel);
        assertEquals(slot, ref.slot(), channelId + " slot");
        assertEquals(component, ref.component(), channelId + " component");
    }
}
