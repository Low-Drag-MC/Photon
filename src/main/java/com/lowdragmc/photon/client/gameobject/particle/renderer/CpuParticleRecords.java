package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.photon.client.gameobject.emitter.data.AdditionalGPUDataSetting;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

/**
 * Per-particle data and custom-data records for the CPU tile path, appended in the order the particles' vertices
 * are written; the {@code PHOTON_CPU_DATA} vertex stage finds its own as
 * {@code (gl_VertexID - PhotonRecordBase) / PhotonVertsPerParticle}.
 * <p>
 * Staging only: the emitter uploads what was written into its own rings and hands them to the draw
 * ({@code Emitter.bakeGroup}). 26.1 sorts a CPU mesh by moving its vertices, not by an index buffer as 1.21 did,
 * so a sort must move these records with the particles — see {@link #permute}.
 */
public final class CpuParticleRecords {
    @Nullable
    private FloatBuffer dataStaging;
    @Nullable
    private FloatBuffer customStaging;
    @Nullable
    private AdditionalGPUDataSetting setting;
    private boolean writeData;
    private boolean writeCustom;
    /** Floats per record, fixed at {@link #begin}: a sort permutes after {@link #end}. */
    private int dataFloats;
    private int customFloats;
    private int vertsPerParticle = 4;
    private int count;
    @Nullable
    private FloatBuffer scratch;

    /** Start a group's records; {@code setting} decides which of the two buffers are wanted. */
    public void begin(AdditionalGPUDataSetting setting, int particleCapacity) {
        this.setting = setting;
        this.writeData = setting.hasDataRecord();
        this.writeCustom = setting.hasCustomRecord();
        this.dataFloats = setting.dataTexels() * 4;
        this.customFloats = setting.customDataTexels() * 4;
        this.count = 0;
        if (writeData) dataStaging = staging(dataStaging, particleCapacity * dataFloats);
        if (writeCustom) customStaging = staging(customStaging, particleCapacity * customFloats);
    }

    public void setVertsPerParticle(int vertsPerParticle) {
        this.vertsPerParticle = Math.max(1, vertsPerParticle);
    }

    public int vertsPerParticle() {
        return vertsPerParticle;
    }

    /** Records written so far, one per particle. */
    public int count() {
        return count;
    }

    /** Append one particle's records — call exactly once per particle whose vertices were written. */
    public void write(IParticle particle, float partialTicks) {
        if (setting == null) return;
        if (writeData && dataStaging != null) ensure(dataStaging, dataFloats, true);
        if (writeCustom && customStaging != null) ensure(customStaging, customFloats, false);
        if (writeData && dataStaging != null) setting.uploadDataRecord(particle, dataStaging, partialTicks);
        if (writeCustom && customStaging != null) setting.uploadCustomRecord(particle, customStaging, partialTicks);
        count++;
    }

    /** The data records, flipped for upload, or null when no material on the group reads them. */
    @Nullable
    public FloatBuffer data() {
        return writeData && dataStaging != null ? dataStaging.duplicate().flip() : null;
    }

    @Nullable
    public FloatBuffer custom() {
        return writeCustom && customStaging != null ? customStaging.duplicate().flip() : null;
    }

    /** Reorder the records so record {@code i} becomes record {@code order[i]}'s — the particle sort's order. */
    public void permute(int[] order, int particles) {
        if (writeData && dataStaging != null) permute(dataStaging, order, particles, dataFloats);
        if (writeCustom && customStaging != null) permute(customStaging, order, particles, customFloats);
    }

    /** Done writing; the staging stays readable until the next {@link #begin}. */
    public void end() {
        setting = null;
    }

    private void permute(FloatBuffer records, int[] order, int particles, int stride) {
        if (stride <= 0 || particles <= 1) return;
        int floats = particles * stride;
        if (scratch == null || scratch.capacity() < floats) scratch = BufferUtils.createFloatBuffer(floats);
        scratch.clear();
        for (int i = 0; i < particles; i++) {
            int from = order[i] * stride;
            for (int f = 0; f < stride; f++) scratch.put(records.get(from + f));
        }
        for (int f = 0; f < floats; f++) records.put(f, scratch.get(f));
    }

    /** Room for one more record, growing the staging buffer (a group may emit more than it was sized for). */
    private void ensure(FloatBuffer buffer, int floats, boolean data) {
        if (buffer.remaining() >= floats) return;
        var grown = BufferUtils.createFloatBuffer(Math.max(buffer.capacity() * 2, buffer.position() + floats));
        buffer.flip();
        grown.put(buffer);
        if (data) dataStaging = grown; else customStaging = grown;
    }

    private static FloatBuffer staging(@Nullable FloatBuffer current, int floats) {
        if (current == null || current.capacity() < floats) {
            int capacity = current == null ? Math.max(floats, 4096) : Math.max(floats, current.capacity() * 2);
            current = BufferUtils.createFloatBuffer(capacity);
        }
        current.clear();
        return current;
    }
}
