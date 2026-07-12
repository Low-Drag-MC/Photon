package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.ui.ArrayConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.configurator.NumberFunctionConfigurator;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import lombok.Getter;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
 *
 * <h3>Custom data</h3>
 * Beyond the fixed registry, an emitter may define up to {@link #MAX_CUSTOM_DATA} user {@link CustomData}
 * streams (Unity-style Custom Data), each uploaded as one {@code vec4}. Custom shaders read them as extra
 * vertex attributes appended after the registry-channel attributes ({@link #layoutAttribs}); shadergraphs
 * read them from a SEPARATE {@code PhotonCustomData} buffer texture with a constant stride of
 * {@code MAX_CUSTOM_DATA} slots ({@link #uploadCustomRecord}), so the {@code photon_custom_data(i)}
 * accessor stays config-independent. The custom TBO is only uploaded when a shadergraph material on the
 * pass actually reads custom data ({@link #hasCustomRecord()}).
 */
@OnlyIn(Dist.CLIENT)
public abstract class AdditionalGPUDataSetting extends ToggleGroup {

    /** Max user custom-data streams per emitter — mirrored by {@code PHOTON_CUSTOM_TEXELS} in particle.glsl. */
    public static final int MAX_CUSTOM_DATA = 4;

    /** Channel → float offset inside the packed TBO record of one instance. */
    protected record PlannedChannel(PhotonGpuChannels.Channel channel, int floatOffset) {
    }

    // channels required by shadergraph materials on the pass, independent of the user toggles
    @Getter
    private long materialMask = 0;

    // ---- vertex-attribute path (legacy custom shaders) ----
    private long lastAttribMask = -1; // the userMask the current attribute layout was built for
    private int lastCustomDataCount = -1; // custom-data streams the current attribute layout was built for
    private final List<PhotonGpuChannels.Channel> attribPlan = new ArrayList<>();

    // ---- data-TBO record path (shadergraph accessors) ----
    private long recordPlanMask = -1;
    private final List<PlannedChannel> recordPlan = new ArrayList<>();
    private float[] recordScratch = new float[0];
    private FloatBuffer recordView = FloatBuffer.wrap(recordScratch);

    // ---- custom-data path ----
    private boolean customDataMaterialUsed = false;
    private final float[] customScratch = new float[4];
    /** Stable per-particle memoized-random keys, one per custom-data stream (avoids per-frame allocation). */
    private static final Object[] CUSTOM_RANDOM_KEYS = new Object[MAX_CUSTOM_DATA];

    static {
        for (int i = 0; i < MAX_CUSTOM_DATA; i++) {
            CUSTOM_RANDOM_KEYS[i] = "photon_custom_data_" + i;
        }
    }

    public abstract PhotonGpuChannels.Kind kind();

    /** The persisted set of user-enabled channel ids. */
    protected abstract Set<String> enabledChannelIds();

    /** Writes {@code channel.floats()} floats for this instance at the buffer's current position. */
    protected abstract void uploadChannel(PhotonGpuChannels.Channel channel, IParticle particle,
                                          FloatBuffer target, float partialTicks);

    /** User-defined custom-data streams (a mutable live list); empty for kinds without support. */
    protected List<CustomData> customDataList() {
        return List.of();
    }

    /** Whether this kind exposes the custom-data editor / upload (per-particle instanced kinds). */
    protected boolean supportsCustomData() {
        return false;
    }

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

    /** Custom-data streams actually uploaded, capped at {@link #MAX_CUSTOM_DATA} (0 when disabled). */
    private int customDataCount() {
        return isEnable() ? Math.min(customDataList().size(), MAX_CUSTOM_DATA) : 0;
    }

    // ---------------------------------------------------------------------
    // vertex-attribute path (legacy)
    // ---------------------------------------------------------------------

    /** Whether any user-toggled channel or custom-data stream needs vertex attributes this frame. */
    public boolean hasAttribs() {
        return attribMask() != 0 || customDataCount() > 0;
    }

    /** True when the vertex-attribute layout no longer matches the user toggles / custom-data count. */
    public boolean attribRelayoutNeeded() {
        return attribMask() != lastAttribMask || customDataCount() != lastCustomDataCount;
    }

    /** Floats per instance the attribute tail occupies in the instance VBO (enabled channels + custom vec4s). */
    public int attribFloats() {
        var mask = attribMask();
        int floats = 0;
        for (var channel : PhotonGpuChannels.CHANNELS) {
            if ((mask & channel.bit()) != 0 && channel.supported().contains(kind()) && channel.uploadable()) {
                floats += channel.floats();
            }
        }
        return floats + customDataCount() * 4;
    }

    /**
     * Defines one divisor-1 attribute per enabled channel, sequentially from the kind's base
     * location, in registry order (the legacy layout), then one {@code vec4} attribute per custom-data
     * stream after them. The instance VBO is bound. Rebuilds the upload plan. Call unconditionally when
     * (re)creating the layout.
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

        // custom-data streams: one vec4 divisor-1 attribute each, appended after the registry channels
        int customCount = customDataCount();
        lastCustomDataCount = customCount;
        for (int i = 0; i < customCount; i++) {
            glVertexAttribPointer(attribIndex, 4, GL_FLOAT, false, stride, offset);
            glEnableVertexAttribArray(attribIndex);
            glVertexAttribDivisor(attribIndex, 1);
            offset += 4 * Float.BYTES;
            attribIndex++;
        }
    }

    /** Appends one instance's attribute tail (enabled channels in registry order, then custom vec4s). */
    public void uploadAttribs(IParticle particle, FloatBuffer buffer, float partialTicks) {
        for (var channel : attribPlan) {
            uploadChannel(channel, particle, buffer, partialTicks);
        }
        int count = lastCustomDataCount;
        if (count > 0) {
            var list = customDataList();
            float t = particle.getT(partialTicks);
            for (int i = 0; i < count && i < list.size(); i++) {
                list.get(i).sampleInto(customScratch, t, randomFor(particle, i));
                buffer.put(customScratch[0]).put(customScratch[1]).put(customScratch[2]).put(customScratch[3]);
            }
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
    // custom-data TBO path (shadergraph CustomDataNode)
    // ---------------------------------------------------------------------

    /** Set by the render pass: whether any shadergraph material on the pass reads custom data. */
    public void setCustomDataMaterialUsed(boolean used) {
        this.customDataMaterialUsed = used;
    }

    /** RGBA32F texels per instance in the {@code PhotonCustomData} record — the constant custom-data stride. */
    public int customDataTexels() {
        return MAX_CUSTOM_DATA;
    }

    /**
     * Whether the custom-data buffer texture must be uploaded this frame — i.e. a shadergraph material
     * on the pass reads custom data. Uploaded even when the emitter defines no streams (all-zero record),
     * so the shared graph's {@code photon_custom_data(i)} accessor always fetches a valid (zeroed) texel
     * rather than a stale texture unit; a pass with no custom-data-reading graph skips it entirely.
     */
    public boolean hasCustomRecord() {
        return customDataMaterialUsed;
    }

    /**
     * Appends one instance's custom-data record ({@code MAX_CUSTOM_DATA} vec4 texels) to the staging
     * buffer: each defined stream sampled at its slot, remaining slots zero.
     */
    public void uploadCustomRecord(IParticle particle, FloatBuffer buffer, float partialTicks) {
        var list = customDataList();
        int count = customDataCount();
        float t = particle.getT(partialTicks);
        for (int i = 0; i < MAX_CUSTOM_DATA; i++) {
            if (i < count && i < list.size()) {
                list.get(i).sampleInto(customScratch, t, randomFor(particle, i));
                buffer.put(customScratch[0]).put(customScratch[1]).put(customScratch[2]).put(customScratch[3]);
            } else {
                buffer.put(0f).put(0f).put(0f).put(0f);
            }
        }
    }

    /** A stable per-particle random in [0,1) for the {@code i}-th custom-data stream (shared by its channels). */
    private static Supplier<Float> randomFor(IParticle particle, int streamIndex) {
        Object key = CUSTOM_RANDOM_KEYS[streamIndex];
        return () -> particle.getMemRandom(key);
    }

    // ---------------------------------------------------------------------
    // persistence — custom-data list (NumberFunctions round-trip via their CODEC wrapper)
    // ---------------------------------------------------------------------

    @Override
    public Tag serializeAdditionalNBT(HolderLookup.@NotNull Provider provider) {
        if (!supportsCustomData()) return super.serializeAdditionalNBT(provider);
        var tag = new CompoundTag();
        var list = new ListTag();
        for (var data : customDataList()) {
            list.add(data.toNBT());
        }
        tag.put("custom_data", list);
        return tag;
    }

    @Override
    public void deserializeAdditionalNBT(Tag tag, HolderLookup.@NotNull Provider provider) {
        if (!supportsCustomData() || !(tag instanceof CompoundTag compound)) return;
        var streams = customDataList();
        streams.clear();
        var dataList = compound.getList("custom_data", Tag.TAG_COMPOUND);
        for (int i = 0; i < dataList.size(); i++) {
            streams.add(CustomData.fromNBT(dataList.getCompound(i)));
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

        if (supportsCustomData()) {
            buildCustomDataConfigurator(father);
        }
    }

    // ---------------------------------------------------------------------
    // custom-data editor
    // ---------------------------------------------------------------------

    private void buildCustomDataConfigurator(ConfiguratorGroup father) {
        var group = new ArrayConfiguratorGroup<>("photon.custom_data", true,
                this::customDataList, this::buildCustomDataItem, true);
        group.setTips("photon.custom_data.tips");
        group.setAddDefault(CustomData::new);
        group.setOnUpdate(list -> {
            var model = customDataList();
            model.clear();
            model.addAll(list);
            onChannelsChanged();
            onConfiguratorUpdate();
        });
        father.addConfigurator(group);
    }

    /**
     * One custom-data stream editor: a collapsible group titled by its live index (0,1,2…), holding a
     * Type selector, a channel-count selector (VECTOR only), and the per-channel function editors.
     */
    private Configurator buildCustomDataItem(Supplier<CustomData> getter, Consumer<CustomData> setter) {
        var data = getter.get();
        int index = Math.max(0, customDataList().indexOf(data));
        var itemGroup = new ConfiguratorGroup(String.valueOf(index), false);
        // keep the title in sync with the stream's current index (reorder/remove don't rebuild the item)
        itemGroup.addEventListener(UIEvents.TICK, e -> {
            int idx = customDataList().indexOf(data);
            var name = idx < 0 ? "" : String.valueOf(idx);
            if (!itemGroup.getLabel().getString().equals(name)) {
                itemGroup.setLabel(name);
            }
        });

        // holds the channel editors; rebuilt on type / channel-count changes (never touches the selectors)
        var channelsGroup = new ConfiguratorGroup("", false).hideTitle();

        var countSelector = new SelectorConfigurator<>("photon.custom_data.channels",
                data::getChannelCount,
                count -> {
                    data.setChannelCount(count);
                    setter.accept(data);
                    rebuildCustomDataChannels(channelsGroup, data, setter);
                },
                1, true,
                List.of(1, 2, 3, 4),
                String::valueOf);
        countSelector.setDisplay(data.getType() == CustomData.Type.VECTOR); // COLOR has no channel count

        itemGroup.addConfigurator(new SelectorConfigurator<>("photon.custom_data.type",
                data::getType,
                type -> {
                    data.setType(type);
                    setter.accept(data);
                    countSelector.setDisplay(type == CustomData.Type.VECTOR);
                    rebuildCustomDataChannels(channelsGroup, data, setter);
                },
                CustomData.Type.VECTOR, true,
                List.of(CustomData.Type.VECTOR, CustomData.Type.COLOR),
                type -> I18n.get("photon.custom_data.type." + type.name().toLowerCase())));
        itemGroup.addConfigurator(countSelector);

        rebuildCustomDataChannels(channelsGroup, data, setter);
        itemGroup.addConfigurator(channelsGroup);
        return itemGroup;
    }

    private void rebuildCustomDataChannels(ConfiguratorGroup channelsGroup, CustomData data, Consumer<CustomData> setter) {
        channelsGroup.removeAllConfigurators();
        if (data.getType() == CustomData.Type.COLOR) {
            channelsGroup.addConfigurator(new NumberFunctionConfigurator("photon.custom_data.color",
                    () -> data.getChannels().getFirst(),
                    fn -> {
                        data.getChannels().set(0, fn);
                        setter.accept(data);
                    }, true, COLOR_CONFIG));
        } else {
            for (int c = 0; c < data.getChannelCount(); c++) {
                final int ci = c;
                var configurator = new NumberFunctionConfigurator(data.getChannelName(ci),
                        () -> data.getChannels().get(ci),
                        fn -> {
                            data.getChannels().set(ci, fn);
                            setter.accept(data);
                        }, true, SCALAR_CONFIG);
                // double-click the label to rename this channel (cosmetic + persisted, for sharing projects)
                attachRename(configurator, () -> data.getChannelName(ci), name -> {
                    data.setChannelName(ci, name);
                    setter.accept(data);
                });
                channelsGroup.addConfigurator(configurator);
            }
        }
    }

    /** Makes a configurator's label double-click editable: swaps in a TextField, commits the name on blur. */
    private static void attachRename(Configurator configurator, Supplier<String> getName, Consumer<String> setName) {
        var label = configurator.label;
        var line = configurator.lineContainer;
        label.addEventListener(UIEvents.DOUBLE_CLICK, e -> {
            for (var child : line.getChildren()) {
                if (child instanceof TextField) return; // already editing
            }
            var field = new TextField().setAnyString();
            field.setText(getName.get(), false);
            field.layout(layout -> {
                layout.width(60);
                layout.height(12);
            });
            var committed = new boolean[]{false};
            Runnable commit = () -> {
                if (committed[0]) return;
                committed[0] = true;
                var value = field.getValue();
                setName.accept(value.isBlank() ? "" : value);
                line.removeChild(field);
                label.setText(getName.get());
                label.setDisplay(true);
            };
            field.addEventListener(UIEvents.BLUR, be -> commit.run());
            label.setDisplay(false);
            line.addChildAt(field, 0);
            field.focus();
        });
    }

    // real @NumberFunctionConfig instances for the per-channel editors (scalar vs color function sets)
    @SuppressWarnings("unused")
    private static final class ConfigHolders {
        @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class},
                curveConfig = @CurveConfig(bound = {-1, 1}))
        private float scalar;
        @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
        private int color;
    }

    private static final NumberFunctionConfig SCALAR_CONFIG = readConfig("scalar");
    private static final NumberFunctionConfig COLOR_CONFIG = readConfig("color");

    private static NumberFunctionConfig readConfig(String field) {
        try {
            return ConfigHolders.class.getDeclaredField(field).getAnnotation(NumberFunctionConfig.class);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

}
