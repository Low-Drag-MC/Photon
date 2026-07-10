package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import lombok.Getter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL33.glVertexAttribDivisor;

/**
 * Per-emitter selection of {@link PhotonGpuChannels} to expose as per-instance data. Two paths,
 * uploaded from the same channel set but stored differently so both material kinds keep working:
 * <ul>
 *   <li><b>Vertex attributes (legacy):</b> the user-toggled channels ({@link #userMask()}) are laid
 *   out one attribute per channel, sequentially from the kind's base location, in registry order —
 *   byte-identical to the pre-packing layout. Hand-written {@code CustomShaderMaterial} shaders read
 *   them via their own {@code layout(location = N) in ...} declarations. This layout depends ONLY on
 *   the user toggles, never on what a shadergraph auto-enables, so those declarations never shift.</li>
 *   <li><b>Data buffer texture (shadergraph):</b> the effective channels ({@code userMask | materialMask})
 *   are packed into the kind's fixed vec4 slots and uploaded to the {@code PhotonData} buffer texture,
 *   indexed by {@code gl_InstanceID}. The {@code photon_data_*()} GLSL accessors read it at a
 *   config-independent fixed offset — so one compiled shadergraph program serves every config.</li>
 * </ul>
 * The two are independent: a pass batching both material kinds populates both. Only the attribute
 * layout is a GL vertex layout ({@link #attribRelayoutNeeded()}); the TBO record size is fixed per kind.
 */
@OnlyIn(Dist.CLIENT)
public abstract class AdditionalGPUDataSetting extends ToggleGroup {

    /** Channel → float offset inside the packed TBO record of one instance. */
    protected record PlannedChannel(PhotonGpuChannels.Channel channel, int floatOffset) {
    }

    // channels required by shadergraph materials on the pass, independent of the user toggles
    @Getter
    private long materialMask = 0;

    // ---- vertex-attribute path (legacy custom shaders) ----
    private long lastAttribMask = -1; // the userMask the current attribute layout was built for
    private final List<PhotonGpuChannels.Channel> attribPlan = new ArrayList<>();

    // ---- data-TBO record path (shadergraph accessors) ----
    private long recordPlanMask = -1;
    private final List<PlannedChannel> recordPlan = new ArrayList<>();
    private float[] recordScratch = new float[0];
    private FloatBuffer recordView = FloatBuffer.wrap(recordScratch);

    public abstract PhotonGpuChannels.Kind kind();

    /** The persisted set of user-enabled channel ids. */
    protected abstract Set<String> enabledChannelIds();

    /** Writes {@code channel.floats()} floats for this instance at the buffer's current position. */
    protected abstract void uploadChannel(PhotonGpuChannels.Channel channel, IParticle particle,
                                          FloatBuffer target, float partialTicks);

    public void setMaterialMask(long materialMask) {
        this.materialMask = materialMask & PhotonGpuChannels.supportedMask(kind());
    }

    public long userMask() {
        return PhotonGpuChannels.maskOf(kind(), enabledChannelIds());
    }

    /** Channels physically present: user toggles (when enabled) plus material-required ones. */
    public long effectiveMask() {
        return attribMask() | materialMask;
    }

    /** The channels backing the vertex-attribute layout — user toggles only, never shadergraph's. */
    private long attribMask() {
        return isEnable() ? userMask() : 0L;
    }

    // ---------------------------------------------------------------------
    // vertex-attribute path (legacy)
    // ---------------------------------------------------------------------

    /** Whether any user-toggled channel needs vertex attributes this frame. */
    public boolean hasAttribs() {
        return attribMask() != 0;
    }

    /** True when the vertex-attribute layout no longer matches the user toggles. */
    public boolean attribRelayoutNeeded() {
        return attribMask() != lastAttribMask;
    }

    /** Floats per instance the attribute tail occupies in the instance VBO (sum of enabled channel sizes). */
    public int attribFloats() {
        var mask = attribMask();
        int floats = 0;
        for (var channel : PhotonGpuChannels.CHANNELS) {
            if ((mask & channel.bit()) != 0 && channel.supported().contains(kind()) && channel.uploadable()) {
                floats += channel.floats();
            }
        }
        return floats;
    }

    /**
     * Defines one divisor-1 attribute per enabled channel, sequentially from the kind's base
     * location, in registry order (the legacy layout). The instance VBO is bound. Rebuilds the
     * upload plan. Call unconditionally when (re)creating the layout.
     */
    public void layoutAttribs(int offset, int stride) {
        var kind = kind();
        var mask = attribMask();
        lastAttribMask = mask;
        attribPlan.clear();

        int attribIndex = kind.baseAttribLocation;
        for (var channel : PhotonGpuChannels.CHANNELS) {
            if ((mask & channel.bit()) == 0 || !channel.supported().contains(kind) || !channel.uploadable()) continue;
            glVertexAttribPointer(attribIndex, channel.floats(), GL_FLOAT, false, stride, offset);
            glEnableVertexAttribArray(attribIndex);
            glVertexAttribDivisor(attribIndex, 1);
            offset += channel.floats() * Float.BYTES;
            attribIndex++;
            attribPlan.add(channel);
        }
    }

    /** Appends one instance's attribute tail (enabled channels in registry order). */
    public void uploadAttribs(IParticle particle, FloatBuffer buffer, float partialTicks) {
        for (var channel : attribPlan) {
            uploadChannel(channel, particle, buffer, partialTicks);
        }
    }

    // ---------------------------------------------------------------------
    // data-TBO record path (shadergraph accessors)
    // ---------------------------------------------------------------------

    /**
     * Whether the data buffer texture is needed this frame — i.e. a shadergraph material on the
     * pass reads additional channels ({@code materialMask != 0}). Hand-written custom shaders read
     * their channels through the legacy vertex attributes instead, so a custom-shader-only pass
     * skips the TBO entirely (the tornado etc.).
     */
    public boolean hasDataRecord() {
        return materialMask != 0;
    }

    /** RGBA32F texels per instance in the {@code PhotonData} record — fixed per kind (all declared slots). */
    public int dataTexels() {
        return PhotonGpuChannels.declaredSlotCount(kind());
    }

    /**
     * Appends one instance's packed record to the data staging buffer: the effective channels at
     * their fixed canonical slots, everything else zero (so a disabled channel's accessor reads 0).
     */
    public void uploadDataRecord(IParticle particle, FloatBuffer buffer, float partialTicks) {
        var mask = effectiveMask();
        ensureRecordPlan(mask);
        if (recordScratch.length == 0) return;
        Arrays.fill(recordScratch, 0f);
        for (var planned : recordPlan) {
            recordView.position(planned.floatOffset());
            uploadChannel(planned.channel(), particle, recordView, partialTicks);
        }
        buffer.put(recordScratch);
    }

    private void ensureRecordPlan(long mask) {
        if (mask == recordPlanMask && recordScratch.length != 0) return;
        recordPlanMask = mask;
        var kind = kind();
        recordScratch = new float[dataTexels() * 4];
        recordView = FloatBuffer.wrap(recordScratch);
        recordPlan.clear();
        for (var channel : PhotonGpuChannels.CHANNELS) {
            if ((mask & channel.bit()) == 0 || !channel.supported().contains(kind) || !channel.uploadable()) continue;
            var ref = PhotonGpuChannels.slotOf(kind, channel);
            recordPlan.add(new PlannedChannel(channel, ref.slot() * 4 + ref.component()));
        }
    }

    // ---------------------------------------------------------------------

    /** Notified when the user toggles a channel (relayout via the owning config). */
    protected void onChannelsChanged() {
    }

    protected void onConfiguratorUpdate() {
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        super.buildConfigurator(father);
        father.addEventListener(Configurator.CHANGE_EVENT, e -> onConfiguratorUpdate());
        var enabled = enabledChannelIds();
        for (var channel : PhotonGpuChannels.CHANNELS) {
            if (!channel.supported().contains(kind()) || !channel.uploadable()) continue;
            var configurator = new Configurator(channel.id()).setTips(channel.tooltip());
            configurator.inlineContainer.addChild(new Toggle()
                    .setText("")
                    .setValue(enabled.contains(channel.id()), false)
                    .setOnToggleChanged(isOn -> {
                        if (isOn) enabled.add(channel.id());
                        else enabled.remove(channel.id());
                        onChannelsChanged();
                    })
                    .addEventListener(UIEvents.TICK, e -> {
                        var toggle = (Toggle) e.currentElement;
                        if (toggle.getValue() != enabled.contains(channel.id())) {
                            toggle.setValue(enabled.contains(channel.id()), false);
                        }
                    })
            );
            father.addConfigurator(configurator);
        }
    }

}
