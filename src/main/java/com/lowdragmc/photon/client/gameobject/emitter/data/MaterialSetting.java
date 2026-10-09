package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.ConfiguratorParser;
import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinPath;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialRenderState;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PremultipliedBlendPlan;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
import com.mojang.blaze3d.vertex.VertexFormat;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Getter
@Setter
@Accessors(chain = true)
@EqualsAndHashCode
public class MaterialSetting implements IConfigurable, IPersistedSerializable {
    /** Where the blend / cull / depth state of this slot comes from. */
    public enum RenderStateSource {
        /** The material's own state when it has one, otherwise this slot's. */
        MATERIAL,
        /** Always this slot's. */
        CUSTOM
    }

    /** The blends a slot picks by name; CUSTOM is factors set by hand. */
    public enum BlendChoice {
        ALPHA(BlendMode.Preset.ALPHA),
        ADDITIVE(BlendMode.Preset.ADDITIVE),
        PREMULTIPLIED(BlendMode.Preset.PREMULTIPLIED),
        MULTIPLY(BlendMode.Preset.MULTIPLY),
        OPAQUE(BlendMode.Preset.OPAQUE),
        CUSTOM(null);

        @Nullable
        public final BlendMode.Preset preset;

        BlendChoice(@Nullable BlendMode.Preset preset) {
            this.preset = preset;
        }

        public String langKey() {
            return "photon.blend_preset." + name().toLowerCase();
        }

        public static BlendChoice of(BlendMode mode) {
            var preset = BlendMode.Preset.of(mode);
            for (var choice : values()) {
                if (choice.preset == preset) return choice;
            }
            return CUSTOM;
        }
    }

    private static final List<String> SLOT_STATE_FIELDS = List.of("blendMode", "cull", "depthTest", "depthMask");

    @Nonnull
    @Configurable(name = "material")
    protected IMaterial material;
    @Configurable(name = "MaterialSetting.renderStateSource", tips = "MaterialSetting.renderStateSource.tips")
    protected RenderStateSource renderStateSource = RenderStateSource.MATERIAL;
    @Configurable(name = "MaterialSetting.blendMode", subConfigurable = true)
    protected final BlendMode blendMode = new BlendMode();
    @Configurable
    protected boolean cull = true;
    @Configurable
    protected boolean depthTest = true;
    @Configurable
    protected boolean depthMask = false;

    public MaterialSetting() {
        this(Optional.ofNullable(MaterialResource.INSTANCE.getResourceInstance().getResource(new BuiltinPath("circle"))).orElseGet(TextureMaterial::new));
    }

    public MaterialSetting(@Nonnull IMaterial material) {
        this.material = material;
    }

    /** The material's render state when this slot follows it, else null. */
    @Nullable
    public MaterialRenderState getFollowedRenderState() {
        return renderStateSource == RenderStateSource.MATERIAL ? material.getPreferredRenderState() : null;
    }

    public BlendMode getEffectiveBlendMode() {
        var followed = getFollowedRenderState();
        return followed != null ? followed.blendMode() : blendMode;
    }

    public boolean isEffectiveCull() {
        var followed = getFollowedRenderState();
        return followed != null ? followed.cull() : cull;
    }

    public boolean isEffectiveDepthTest() {
        var followed = getFollowedRenderState();
        return followed != null ? followed.depthTest() : depthTest;
    }

    public boolean isEffectiveDepthMask() {
        var followed = getFollowedRenderState();
        return followed != null ? followed.depthMask() : depthMask;
    }

    /**
     * Whether the slot draws its geometry twice, back faces then front ones, so a closed transparent mesh sorts
     * itself. 26.1 pipelines only know whether to cull, not which face, so the drain draws such a job twice and
     * flips the cull face around each draw — see {@code PhotonWorldRenderState.withCullFace}.
     */
    public boolean isBackFacesFirst() {
        var followed = getFollowedRenderState();
        return followed != null && followed.backFacesFirst();
    }

    // 1.21's pre()/post() applied blend/cull/depth as imperative RenderSystem calls; in 26.1 they
    // are pipeline properties, so this setting contributes the pipeline-variant key instead — built from the
    // EFFECTIVE state, so a material that brings its own render state is followed on every draw path.
    public PhotonPipelines.ParticlePipelineKey pipelineKey(
            VertexFormat.Mode mode) {
        var effectiveBlend = getEffectiveBlendMode();
        var blend = effectiveBlend.toBlendFunction();
        var equation = blend != null && effectiveBlend.getBlendFunc() != null
                ? effectiveBlend.getBlendFunc().op
                : PhotonPipelines.BLEND_EQUATION_ADD;
        var key = new PhotonPipelines.ParticlePipelineKey(blend, equation,
                isEffectiveCull() || isBackFacesFirst(), isEffectiveDepthTest(), isEffectiveDepthMask(), mode, false);
        // Drawing into a transparent layer rather than onto the scene changes what the alpha half of the
        // blend must do — see PremultipliedBlendPlan. Consulted here, at the one place every material's
        // pipeline is chosen, so no draw path can bypass it.
        return PremultipliedBlendPlan.accumulating()
                ? PremultipliedBlendPlan.premultiply(key) : key;
    }

    /** The RenderType this material slot draws with (material + this setting's pipeline state +
     *  the emitter's primitive mode). */
    @Nullable
    public RenderType getRenderType(
            VertexFormat.Mode mode) {
        return material.getRenderType(this, mode);
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        addField(father, "material");
        var sourceRow = new SelectorConfigurator<>("MaterialSetting.renderStateSource",
                () -> renderStateSource, source -> renderStateSource = source, RenderStateSource.MATERIAL, true,
                List.of(RenderStateSource.values()), source -> "MaterialSetting.renderStateSource." + source.name().toLowerCase());
        sourceRow.setTips("MaterialSetting.renderStateSource.tips");
        father.addConfigurator(sourceRow);
        var slotRows = new ArrayList<Configurator>();
        var preset = new SelectorConfigurator<>("MaterialSetting.blendPreset", () -> BlendChoice.of(blendMode),
                choice -> {
                    if (choice.preset != null) setBlend(choice.preset.create());
                }, BlendChoice.ALPHA, true, List.of(BlendChoice.values()), BlendChoice::langKey);
        preset.setTips("MaterialSetting.blendPreset.tips");
        father.addConfigurator(preset);
        slotRows.add(preset);
        for (var name : SLOT_STATE_FIELDS) {
            slotRows.addAll(addField(father, name));
        }
        var followedRow = new Configurator();
        father.addConfigurator(followedRow);
        father.addEventListener(UIEvents.TICK, e -> {
            // only a material that brings its own state has anything to follow
            sourceRow.setDisplay(material.getPreferredRenderState() != null);
            var followed = getFollowedRenderState();
            for (var row : slotRows) {
                row.setDisplay(followed == null);
            }
            followedRow.setDisplay(followed != null);
            if (followed != null) {
                followedRow.setLabel(Component.translatable("MaterialSetting.followedRenderState",
                        describe(followed.blendMode())));
            }
        });
    }

    /** Copies {@code blend} into this slot's own blend, which is final: configurators hold on to it. */
    public void setBlend(BlendMode blend) {
        blendMode.setEnableBlend(blend.isEnableBlend());
        blendMode.setSrcColorFactor(blend.getSrcColorFactor());
        blendMode.setDstColorFactor(blend.getDstColorFactor());
        blendMode.setSrcAlphaFactor(blend.getSrcAlphaFactor());
        blendMode.setDstAlphaFactor(blend.getDstAlphaFactor());
        blendMode.setBlendFunc(blend.getBlendFunc());
    }

    /** The configurators one field produced, so they can be told apart afterwards. */
    private List<Configurator> addField(ConfiguratorGroup father, String name) {
        int before = father.getConfigurators().size();
        try {
            ConfiguratorParser.createFieldConfigurator(MaterialSetting.class.getDeclaredField(name), father,
                    MaterialSetting.class, Map.of(), this);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
        var all = father.getConfigurators();
        return new ArrayList<>(all.subList(before, all.size()));
    }

    private static Component describe(BlendMode blend) {
        var preset = BlendMode.Preset.of(blend);
        return preset != null ? Component.translatable(preset.langKey())
                : Component.literal(blend.getSrcColorFactor() + " / " + blend.getDstColorFactor());
    }
}
