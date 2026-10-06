package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ConfiguratorParser;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinPath;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialRenderState;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PremultipliedBlendPlan;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
import com.mojang.blaze3d.systems.RenderSystem;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@OnlyIn(Dist.CLIENT)
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

    // what pre() applied, so post() undoes exactly that even if the material changed in between
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private transient BlendMode appliedBlend = blendMode;
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private transient boolean appliedCull = true;
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private transient boolean appliedDepthTest = true;
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private transient boolean appliedDepthMask = false;
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private transient boolean appliedTwoPass = false;

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

    /** How many times the slot draws its geometry: twice when the material wants back faces first. */
    public int passes() {
        var followed = getFollowedRenderState();
        return followed != null && followed.backFacesFirst() ? 2 : 1;
    }

    /** {@link #pre()} for one of {@link #passes()}: of two, the back faces, then the front ones. */
    public void pre(int pass) {
        pre();
        appliedTwoPass = passes() > 1;
        if (appliedTwoPass) {
            RenderSystem.enableCull();
            GL11.glCullFace(pass == 0 ? GL11.GL_FRONT : GL11.GL_BACK);
        }
    }

    /**
     * Apply this material's GL state.
     *
     * <p>The blend half is asked of the pipeline rather than passed in: under a shader pack — and on
     * the {@code FXCompositeMode.LATE} path — Photon draws into a transparent premultiplied
     * accumulator, where the alpha channel has to carry coverage instead of whatever the author
     * wrote (see {@link PremultipliedBlendPlan}). Reading it from
     * {@link RenderPassPipeline#getCurrent()} — the same seam materials already use for scene
     * samplers — means a new renderer cannot forget to opt in and silently punch a hole in the
     * pack's translucent layer.
     */
    public void pre() {
        var followed = getFollowedRenderState();
        appliedBlend = followed != null ? followed.blendMode() : blendMode;
        appliedCull = followed != null ? followed.cull() : cull;
        appliedDepthTest = followed != null ? followed.depthTest() : depthTest;
        appliedDepthMask = followed != null ? followed.depthMask() : depthMask;
        var pipeline = RenderPassPipeline.getCurrent();
        if (pipeline != null && pipeline.isPremultipliedAccumulation()) {
            PremultipliedBlendPlan.applyPremultiplied(appliedBlend);
        } else {
            appliedBlend.apply();
        }
        if (appliedCull) RenderSystem.enableCull(); else RenderSystem.disableCull();
        if (appliedDepthTest) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        RenderSystem.depthMask(appliedDepthMask);
    }

    public void post() {
        if (appliedTwoPass) {
            GL11.glCullFace(GL11.GL_BACK);
            appliedTwoPass = false;
        }
        if (appliedBlend.getBlendFunc() != BlendMode.BlendFuc.ADD) {
            RenderSystem.blendEquation(BlendMode.BlendFuc.ADD.op);
        }
        appliedBlend.reset();
        if (!appliedCull) RenderSystem.enableCull();
        if (!appliedDepthTest) RenderSystem.enableDepthTest();
        if (!appliedDepthMask) RenderSystem.depthMask(true);
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        addField(father, "material");
        father.addConfigurator(new SelectorConfigurator<>("MaterialSetting.renderStateSource",
                () -> renderStateSource, source -> renderStateSource = source, RenderStateSource.MATERIAL, true,
                List.of(RenderStateSource.values()), source -> "MaterialSetting.renderStateSource." + source.name().toLowerCase())
                .setTips("MaterialSetting.renderStateSource.tips"));
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
