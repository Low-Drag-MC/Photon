package com.lowdragmc.photon.client.gameobject.emitter.data;

import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of the per-instance "additional GPU data" channels shared between the instanced
 * renderers (upload/layout) and the shader side (shadergraph node + hand-written shaders).
 * Channels are packed canonically (registry order, first-fit) into vec4 attribute "slots"
 * (iCustom0..N) per particle {@link Kind}; a channel's slot+swizzle is FIXED regardless of which
 * channels a given config enables, so one compiled shader serves every enabled-set. Only slots
 * containing at least one enabled channel are present in the instance VBO (locations stay fixed,
 * buffer offsets compact over the active slots).
 * <p>
 * The channel list is APPEND-ONLY: packing is derived from registry order, and the GLSL side is
 * MIRRORED IN assets/photon/shaders/include/particle.glsl ({@code photon_data_*()} accessors) —
 * keep both in lockstep.
 */
public final class PhotonGpuChannels {

    public enum Kind {
        /** billboard tile particles (PARTICLE_INSTANCE): base instance attributes end at location 7. */
        TILE(8),
        /** model tile particles (PARTICLE_MODEL_INSTANCE): base ends at location 8. Same channel set/packing as TILE. */
        TILE_MODEL(9),
        /** trail segments (TRAIL_INSTANCE, TBO vertex pulling): per-instance base = aPos + iSeg + iSegV, ends at location 2. */
        TRAIL(3),
        /** AraTrail segments (ARA_TRAIL_INSTANCE / ARA_TRAIL_TUBE_INSTANCE, TBO vertex pulling): base ends at location 2. */
        ARA_TRAIL(3),
        /** beams (BEAM_INSTANCE): base ends at location 5. */
        BEAM(6);

        public final int baseAttribLocation;

        Kind(int baseAttribLocation) {
            this.baseAttribLocation = baseAttribLocation;
        }
    }

    /**
     * @param index    registry ordinal (mask bit)
     * @param id       persisted/lang id, e.g. "addition_gpu_data.t"
     * @param typeName GLSL type the accessor returns ("float"/"int"/"vec3") — tooltip + node typing
     * @param floats   floats consumed in the packed slots (0 = derived, GLSL-only, nothing uploaded;
     *                 per-point trail channels store 2: value at segment start and end)
     * @param perPoint trail kinds only: uploaded per segment endpoint pair, mixed by corner in the shader
     * @param hasTips  whether a "&lt;id&gt;.tips" lang entry exists
     */
    public record Channel(int index, String id, String typeName, int floats, boolean perPoint,
                          boolean hasTips, EnumSet<Kind> supported) {
        public boolean uploadable() {
            return floats > 0;
        }

        public long bit() {
            return 1L << index;
        }

        /** Accessor function name in particle.glsl, e.g. "photon_data_t". */
        public String glslAccessor() {
            return "photon_data_" + id.substring(id.lastIndexOf('.') + 1);
        }

        public Component tooltip() {
            var type = Component.translatable("addition_gpu_data.type." + typeName);
            return hasTips ? Component.translatable(id + ".tips").append(type) : type;
        }
    }

    public static final List<Channel> CHANNELS;
    private static final Map<String, Channel> BY_ID = new HashMap<>();
    private static final Map<Kind, Map<Channel, SlotRef>> PACKING = new EnumMap<>(Kind.class);
    private static final Map<Kind, Integer> SLOT_COUNTS = new EnumMap<>(Kind.class);
    private static final Map<Kind, Long> SUPPORTED_MASKS = new EnumMap<>(Kind.class);

    /** Fixed canonical position of a channel: vec4 slot index (attribute = base + slot) and first component (0=x..3=w). */
    public record SlotRef(int slot, int component) {
    }

    static {
        var all = EnumSet.allOf(Kind.class);
        var tile = EnumSet.of(Kind.TILE, Kind.TILE_MODEL);
        var trail = EnumSet.of(Kind.TRAIL, Kind.ARA_TRAIL);
        var beam = EnumSet.of(Kind.BEAM);

        var channels = new ArrayList<Channel>();
        // the original tile channel ids/order — do not reorder (packing + persisted ids depend on it)
        channels.add(new Channel(channels.size(), "addition_gpu_data.random", "float", 1, false, true, all));
        channels.add(new Channel(channels.size(), "addition_gpu_data.t", "float", 1, false, true, all));
        channels.add(new Channel(channels.size(), "addition_gpu_data.age", "float", 1, false, false, tile));
        channels.add(new Channel(channels.size(), "addition_gpu_data.lifetime", "float", 1, false, false, tile));
        channels.add(new Channel(channels.size(), "addition_gpu_data.position", "vec3", 3, false, false, tile));
        channels.add(new Channel(channels.size(), "addition_gpu_data.velocity", "vec3", 3, false, false, tile));
        channels.add(new Channel(channels.size(), "addition_gpu_data.isCollided", "float", 1, false, true, tile));
        channels.add(new Channel(channels.size(), "addition_gpu_data.emitter_t", "float", 1, false, false, all));
        channels.add(new Channel(channels.size(), "addition_gpu_data.emitter_age", "float", 1, false, false, all));
        channels.add(new Channel(channels.size(), "addition_gpu_data.emitter_position", "vec3", 3, false, false, all));
        channels.add(new Channel(channels.size(), "addition_gpu_data.emitter_velocity", "vec3", 3, false, false, all));
        // appended channels
        channels.add(new Channel(channels.size(), "addition_gpu_data.point_t", "float", 2, true, true, trail));
        channels.add(new Channel(channels.size(), "addition_gpu_data.point_life", "float", 2, true, true, trail));
        channels.add(new Channel(channels.size(), "addition_gpu_data.beam_direction", "vec3", 0, false, true, beam));
        channels.add(new Channel(channels.size(), "addition_gpu_data.beam_length", "float", 0, false, true, beam));
        CHANNELS = List.copyOf(channels);

        if (CHANNELS.size() > 64) {
            throw new IllegalStateException("PhotonGpuChannels: channel masks are 64-bit");
        }

        for (var channel : CHANNELS) {
            BY_ID.put(channel.id(), channel);
        }

        for (var kind : Kind.values()) {
            var packing = new HashMap<Channel, SlotRef>();
            var slotFill = new ArrayList<Integer>(); // floats used per slot, allocated tail-first
            long supported = 0;
            for (var channel : CHANNELS) {
                if (!channel.supported().contains(kind) || !channel.uploadable()) continue;
                supported |= channel.bit();
                // first-fit: earliest slot whose free tail holds the channel contiguously
                int slot = -1;
                for (int i = 0; i < slotFill.size(); i++) {
                    if (4 - slotFill.get(i) >= channel.floats()) {
                        slot = i;
                        break;
                    }
                }
                if (slot == -1) {
                    slot = slotFill.size();
                    slotFill.add(0);
                }
                packing.put(channel, new SlotRef(slot, slotFill.get(slot)));
                slotFill.set(slot, slotFill.get(slot) + channel.floats());
            }
            PACKING.put(kind, Map.copyOf(packing));
            SLOT_COUNTS.put(kind, slotFill.size());
            SUPPORTED_MASKS.put(kind, supported);

            if (kind.baseAttribLocation + slotFill.size() > 16) {
                throw new IllegalStateException("PhotonGpuChannels: " + kind + " exceeds the 16 vertex attribute budget");
            }
        }
    }

    private PhotonGpuChannels() {
    }

    @Nullable
    public static Channel byId(String id) {
        return BY_ID.get(id);
    }

    /** Mask of all uploadable channels the kind supports. */
    public static long supportedMask(Kind kind) {
        return SUPPORTED_MASKS.get(kind);
    }

    public static long maskOf(Kind kind, Collection<String> ids) {
        long mask = 0;
        for (var id : ids) {
            var channel = BY_ID.get(id);
            if (channel != null) {
                mask |= channel.bit();
            }
        }
        return mask & supportedMask(kind);
    }

    /** Number of vec4 slots the kind DECLARES in GLSL (all of them, active or not). */
    public static int declaredSlotCount(Kind kind) {
        return SLOT_COUNTS.get(kind);
    }

    public static SlotRef slotOf(Kind kind, Channel channel) {
        var ref = PACKING.get(kind).get(channel);
        if (ref == null) {
            throw new IllegalArgumentException(channel.id() + " is not packed for " + kind);
        }
        return ref;
    }

    /** Canonical slot indices (ascending) that contain at least one channel of the mask. */
    public static int[] activeSlots(Kind kind, long mask) {
        mask &= supportedMask(kind);
        long slotBits = 0;
        for (var entry : PACKING.get(kind).entrySet()) {
            if ((mask & entry.getKey().bit()) != 0) {
                slotBits |= 1L << entry.getValue().slot();
            }
        }
        var slots = new int[Long.bitCount(slotBits)];
        var i = 0;
        for (int slot = 0; slot < SLOT_COUNTS.get(kind); slot++) {
            if ((slotBits & (1L << slot)) != 0) {
                slots[i++] = slot;
            }
        }
        return slots;
    }
}
