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
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttrib4f;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL33.glVertexAttribDivisor;

/**
 * Per-emitter selection of {@link PhotonGpuChannels} to upload as per-instance attributes.
 * Channels pack into fixed vec4 slots (see the registry); only slots with at least one enabled
 * channel exist in the instance VBO. The effective channel set is the user's toggles plus
 * whatever the pass's shadergraph materials read ({@link #setMaterialMask}); when it changes,
 * the owning render pass must rebuild the instance layout ({@link #relayoutNeeded}).
 */
@OnlyIn(Dist.CLIENT)
public abstract class AdditionalGPUDataSetting extends ToggleGroup {

    /** Channel → float offset inside the compacted custom-data tail of one instance. */
    protected record PlannedChannel(PhotonGpuChannels.Channel channel, int floatOffset) {
    }

    // channels required by shadergraph materials on the pass, independent of the user toggles
    @Getter
    private long materialMask = 0;
    // the mask the current GL layout (and upload plan) was built for; -1 = never built
    private long lastLayoutMask = -1;

    private List<PlannedChannel> uploadPlan = List.of();
    private float[] scratch = new float[0];
    private FloatBuffer scratchView = FloatBuffer.wrap(scratch);

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

    /** Channels actually uploaded: user toggles (when the group is enabled) plus material-required ones. */
    public long effectiveMask() {
        return (isEnable() ? userMask() : 0L) | materialMask;
    }

    /** True when the GL instance layout no longer matches the effective channel set. */
    public boolean relayoutNeeded() {
        return effectiveMask() != lastLayoutMask;
    }

    public boolean hasCustomData() {
        return effectiveMask() != 0;
    }

    /** Floats per instance occupied by the custom-data tail (4 per active slot). */
    public int getCustomDataSize() {
        return 4 * PhotonGpuChannels.activeSlots(kind(), effectiveMask()).length;
    }

    /**
     * Defines the divisor-1 vec4 attributes for the active slots (instance VBO bound) at their
     * fixed locations, and rebuilds the upload plan. Call unconditionally when (re)creating the
     * layout — with no active slots it only records the layout mask.
     */
    public void instanceDataLayout(int offset, int stride) {
        var kind = kind();
        var mask = effectiveMask();
        lastLayoutMask = mask;

        var slots = PhotonGpuChannels.activeSlots(kind, mask);
        var slotLocal = new int[PhotonGpuChannels.declaredSlotCount(kind)];
        Arrays.fill(slotLocal, -1);
        for (int i = 0; i < slots.length; i++) {
            var location = kind.baseAttribLocation + slots[i];
            glVertexAttribPointer(location, 4, GL_FLOAT, false, stride, offset);
            glEnableVertexAttribArray(location);
            glVertexAttribDivisor(location, 1);
            offset += 4 * Float.BYTES;
            slotLocal[slots[i]] = i;
        }

        var plan = new ArrayList<PlannedChannel>();
        for (var channel : PhotonGpuChannels.CHANNELS) {
            if ((mask & channel.bit()) == 0 || !channel.supported().contains(kind) || !channel.uploadable()) continue;
            var ref = PhotonGpuChannels.slotOf(kind, channel);
            plan.add(new PlannedChannel(channel, slotLocal[ref.slot()] * 4 + ref.component()));
        }
        uploadPlan = plan;
        scratch = new float[4 * slots.length];
        scratchView = FloatBuffer.wrap(scratch);
    }

    /**
     * Sets the current-value of every declared-but-inactive slot to zero before an instanced draw.
     * Disabled attribute arrays otherwise read the GL default {@code (0,0,0,1)}, which would leak
     * 1.0 into any channel packed into a {@code .w} component.
     */
    public void zeroInactiveSlots() {
        var kind = kind();
        var declared = PhotonGpuChannels.declaredSlotCount(kind);
        if (declared == 0) return;
        long activeBits = 0;
        if (lastLayoutMask > 0) {
            for (int slot : PhotonGpuChannels.activeSlots(kind, lastLayoutMask)) {
                activeBits |= 1L << slot;
            }
        }
        for (int slot = 0; slot < declared; slot++) {
            if ((activeBits & (1L << slot)) == 0) {
                glVertexAttrib4f(kind.baseAttribLocation + slot, 0, 0, 0, 0);
            }
        }
    }

    /** Appends one instance's custom-data tail (zero-filled slots, enabled channels at their packed offsets). */
    public void uploadData(IParticle particle, FloatBuffer buffer, float partialTicks) {
        if (scratch.length == 0) return;
        Arrays.fill(scratch, 0f);
        for (var planned : uploadPlan) {
            scratchView.position(planned.floatOffset());
            uploadChannel(planned.channel(), particle, scratchView, partialTicks);
        }
        buffer.put(scratch);
    }

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
