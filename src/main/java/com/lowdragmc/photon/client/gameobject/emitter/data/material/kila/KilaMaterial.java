package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.kilagraph.rendertype.compiler.KGSamplerGl;
import com.lowdragmc.kilagraph.rendertype.runtime.KGSamplerBinder;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.ToggleSelectorConfigurator;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialRenderState;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.ShaderInstanceMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.StackedDistortion;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2f;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The built-in all-in-one particle material: one shader, switchable modules, and parameters that can follow
 * per-particle data. Each enabled set of modules compiles to its own program variant.
 */
@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "kila_material", registry = "photon:material")
public class KilaMaterial extends ShaderInstanceMaterial {
    /** ⚠️ ordinals MIRRORED IN {@code photon:kila.fsh} (KilaSurfaceModes.x). */
    public enum VertexColor {
        MULTIPLY, ALPHA_ONLY, IGNORE,
        /** for when the vertex alpha drives something else, e.g. dissolve on a trail */
        RGB_ONLY
    }

    public enum PreviewBackground {
        CHECKER(KilaSlotPreview.checker()),
        DARK(ColorPattern.BLACK.rectTexture()),
        LIGHT(ColorPattern.LIGHT_GRAY.rectTexture());

        public final IGuiTexture texture;

        PreviewBackground(IGuiTexture texture) {
            this.texture = texture;
        }
    }

    /** One step of the material shown on its own, to see what a module does. ⚠️ ordinals MIRRORED IN {@code kila.fsh} (KilaDebug.x). */
    public enum DebugView {
        OFF, MAIN, ALPHA, MASK, DISSOLVE, DISTORTION, NORMAL, UV
    }

    private static final String[] BASE = new String[KilaParam.GROUPS];
    private static final String[] SCALE = new String[KilaParam.GROUPS];
    private static final String[] SOURCE = new String[KilaParam.GROUPS];

    static {
        for (int group = 0; group < KilaParam.GROUPS; group++) {
            BASE[group] = "KilaBase" + group;
            SCALE[group] = "KilaScale" + group;
            SOURCE[group] = "KilaSrc" + group;
        }
    }
    public static final String PREVIEW_CORNER_ID = "kila_preview_corner";
    private static final BlendMode STRAIGHT_BLEND = BlendMode.Preset.ALPHA.create();
    private static final KGSamplerGl.GlSampler RAMP_SAMPLER = new KGSamplerGl.GlSampler(GL11.GL_LINEAR, GL11.GL_LINEAR,
            GL12.GL_CLAMP_TO_EDGE, GL12.GL_CLAMP_TO_EDGE);

    // render state
    /** Leave blend, cull and depth to each slot, as a TextureMaterial does. */
    @Persisted
    public boolean slotRenderState;
    @Persisted
    public BlendMode.Preset blend = BlendMode.Preset.ALPHA;
    /** Premultiplied only: 0 is a normal blend, 1 is purely additive. */
    @Persisted
    public float additive;
    @Persisted
    public boolean doubleSided;
    /** double sided, drawn twice: back faces, then front ones */
    @Persisted
    public boolean backFacesFirst;
    @Persisted
    public boolean depthTest = true;
    @Persisted
    public boolean depthWrite;
    /** blocks towards the camera the quad is depth tested at; where it shows does not move */
    @Persisted
    public float cameraOffset;

    // surface
    @Persisted
    public HDRColor color = HDRColor.white();
    @Persisted
    public HDRColor backColor = HDRColor.white();
    @Persisted
    public HDRColor emission = HDRColor.black();
    @Persisted(subPersisted = true)
    public final KilaDriver alpha = new KilaDriver(1);
    @Persisted(subPersisted = true)
    public final KilaDriver intensity = new KilaDriver(1);
    @Persisted
    public float alphaSharpen = 1;
    @Persisted
    public VertexColor vertexColor = VertexColor.MULTIPLY;
    @Persisted
    public boolean worldLight = true;
    @Persisted
    public boolean fog = true;
    @Persisted
    public float alphaClip = 0.01f;
    /** animations stepped at this many frames per second; 0 = smooth */
    @Persisted
    public float frameRate;
    /** how far the uv jumps every step */
    @Persisted
    public float boil;

    // modules
    @Persisted(subPersisted = true)
    public final KilaMainTexture main = new KilaMainTexture();
    @Persisted(subPersisted = true)
    public final KilaLayer layer = new KilaLayer();
    @Persisted(subPersisted = true)
    public final KilaLayer layer2 = new KilaLayer(2);
    @Persisted(subPersisted = true)
    public final KilaMask mask1 = new KilaMask(1);
    @Persisted(subPersisted = true)
    public final KilaMask mask2 = new KilaMask(2);
    @Persisted(subPersisted = true)
    public final KilaDissolve dissolve = new KilaDissolve();
    @Persisted(subPersisted = true)
    public final KilaDistortion distortion = new KilaDistortion();
    @Persisted(subPersisted = true)
    public final KilaUvEffects uvEffects = new KilaUvEffects();
    @Persisted(subPersisted = true)
    public final KilaProjection projection = new KilaProjection();
    @Persisted(subPersisted = true)
    public final KilaScreenDistortion screenDistortion = new KilaScreenDistortion();
    @Persisted(subPersisted = true)
    public final KilaRamp ramp = new KilaRamp();
    @Persisted(subPersisted = true)
    public final KilaFresnel fresnel = new KilaFresnel();
    @Persisted(subPersisted = true)
    public final KilaShading shading = new KilaShading();
    @Persisted(subPersisted = true)
    public final KilaMatcap matcap = new KilaMatcap();
    @Persisted(subPersisted = true)
    public final KilaVertexOffset vertexOffset = new KilaVertexOffset();
    @Persisted(subPersisted = true)
    public final KilaDepth depth = new KilaDepth();
    @Persisted(subPersisted = true)
    public final KilaVolume volume = new KilaVolume();
    @Persisted(subPersisted = true)
    public final KilaSimpleModules.CameraFade cameraFade = new KilaSimpleModules.CameraFade();
    @Persisted(subPersisted = true)
    public final KilaSimpleModules.ColorAdjust colorAdjust = new KilaSimpleModules.ColorAdjust();
    @Persisted(subPersisted = true)
    public final KilaSimpleModules.Pixelate pixelate = new KilaSimpleModules.Pixelate();
    @Persisted(subPersisted = true)
    public final KilaSimpleModules.Lit lit = new KilaSimpleModules.Lit();

    private final List<KilaModule> modules = List.of(main, layer, layer2, mask1, mask2, dissolve, distortion, uvEffects,
            projection, screenDistortion, ramp, fresnel, shading, matcap, vertexOffset, depth, volume, cameraFade,
            colorAdjust, pixelate, lit);
    // when the samplers do not all fit, the later ones give way first
    private final List<KilaModule> keepOrder = List.of(main, layer, mask1, mask2, dissolve, distortion, uvEffects,
            projection, screenDistortion, ramp, fresnel, shading, vertexOffset, depth, volume, cameraFade, colorAdjust,
            pixelate, lit, layer2, matcap);

    /** Not saved: authoring only. */
    public DebugView debugView = DebugView.OFF;
    /** Not saved: authoring only. */
    public PreviewBackground previewBackground = PreviewBackground.CHECKER;

    // runtime
    // one layout per stacking state: the thumbnail never stacks while the scene may
    private final KilaLayout[] layouts = new KilaLayout[2];
    private final long[] layoutStructures = new long[2];
    @Nullable
    private MaterialRenderState renderState;
    private int renderStateKey;
    private final float[] scratch = new float[4];
    private boolean stackedDraw;
    private boolean depthTestLifted;
    private final float[][] base = new float[KilaParam.GROUPS][4];
    private final float[][] scale = new float[KilaParam.GROUPS][4];
    private final int[][] source = new int[KilaParam.GROUPS][4];
    private final List<KGSamplerGl.Binding> bindings = new ArrayList<>();
    // allocation-free walks: these run per draw
    private final BiConsumer<KilaParam, KilaDriver> stager = this::stage;
    private long textureHash;
    private final Consumer<KilaTexture> textureHasher = texture -> textureHash = textureHash * 31 + texture.structureHash();
    private long scanMask;
    private boolean scanCustom;
    private boolean scanParticleData;
    private final BiConsumer<KilaParam, KilaDriver> scanner = (param, driver) -> {
        scanMask |= driver.channelMask();
        scanCustom |= driver.readsCustomData();
        scanParticleData |= driver.source.isParticleData();
    };

    public List<KilaModule> modules() {
        return modules;
    }

    // ---- variant ----------------------------------------------------------------------------------

    /** The layout of the current settings, rebuilt only when something that changes the variant changed. */
    public KilaLayout layout() {
        long structure = structure();
        int slot = screenDistortion.stacking ? 1 : 0;
        var layout = layouts[slot];
        if (layout == null || structure != layoutStructures[slot]) {
            var builder = KilaLayout.surface();
            if (cameraOffset != 0) builder.define("KILA_CAMERA_OFFSET").float1("KilaCameraOffset", 0);
            if (frameRate > 0) builder.define("KILA_TIME_STEP").float1("GameTime", 0).vec4("KilaTimeStep", 12, 0, 0, 0);
            var enabled = new ArrayList<KilaModule>();
            for (var module : keepOrder) {
                if (module.isEnable()) enabled.add(module);
            }
            layout = builder.build(enabled);
            layouts[slot] = layout;
            layoutStructures[slot] = structure;
        }
        return layout;
    }

    private long structure() {
        textureHash = (cameraOffset != 0 ? 2 : 1) + (frameRate > 0 ? 4 : 0);
        for (var module : modules) {
            textureHash = textureHash * 31 + (module.isEnable() ? 1 : 0);
            if (!module.isEnable()) continue;
            textureHash = textureHash * 31 + module.structure();
            module.textures(textureHasher);
        }
        return textureHash;
    }

    private boolean active(KilaModule module, KilaLayout layout) {
        return module.isEnable() && !layout.dropped.contains(module);
    }

    @Override
    public ShaderInstance getShader(MaterialContext context) {
        syncStacking(context.isRenderingPreview());
        var variant = layout().variant(context);
        return variant == null ? IMaterial.MISSING.begin(context) : variant.shader();
    }

    @Override
    public ShaderInstance begin(MaterialContext context) {
        syncStacking(context.isRenderingPreview());
        var layout = layout();
        var variant = layout.variant(context);
        // a broken variant must still honour the context, or an instanced pass draws a CPU-layout program
        if (variant == null) return IMaterial.MISSING.begin(context);
        var shader = variant.shader();
        upload(shader, layout, variant, context);
        if (context.isRenderingPreview()) {
            var state = getPreferredRenderState();
            (state == null ? STRAIGHT_BLEND : state.blendMode()).apply();
        }
        stackedDraw = screenDistortion.stacking && active(screenDistortion, layout);
        if (stackedDraw) StackedDistortion.beginDraw();
        // the slot's depth test would hide the projection behind the terrain it paints
        depthTestLifted = slotRenderState && !context.isRenderingPreview() && active(projection, layout);
        if (depthTestLifted) RenderSystem.disableDepthTest();
        return shader;
    }

    /** Stacked only in a build that can bend the frame, and not while a debug view replaces the output. */
    private void syncStacking(boolean preview) {
        screenDistortion.stacking = screenDistortion.isEnable() && screenDistortion.stack && !preview
                && debugView == DebugView.OFF && StackedDistortion.available();
    }

    @Override
    public void end(MaterialContext context) {
        if (stackedDraw) {
            StackedDistortion.endDraw();
            stackedDraw = false;
        }
        if (depthTestLifted) {
            RenderSystem.enableDepthTest();
            depthTestLifted = false;
        }
        // the instanced path never clears the program, so release the sampler objects ourselves
        KGSamplerBinder.onClear();
        if (context.isRenderingPreview()) {
            RenderSystem.enableBlend();
            RenderSystem.blendEquation(BlendMode.BlendFuc.ADD.op);
            RenderSystem.defaultBlendFunc();
        }
    }

    private void upload(ShaderInstance shader, KilaLayout layout, KilaShaders.Variant variant, MaterialContext context) {
        var preview = context.isRenderingPreview();
        var textureManager = Minecraft.getInstance().getTextureManager();
        bindings.clear();
        for (var texture : layout.textures) {
            int unit = variant.textureUnits()[texture.index()];
            if (texture.texture() != null) {
                shader.setSampler(texture.name(), textureManager.getTexture(texture.texture().getTexture()));
                if (unit >= 0) bindings.add(new KGSamplerGl.Binding(unit, texture.texture().glSampler()));
            } else if (texture.dynamic() != null) {
                shader.setSampler(texture.name(), texture.dynamic().getAsInt());
                if (unit >= 0) bindings.add(new KGSamplerGl.Binding(unit, RAMP_SAMPLER));
            }
        }
        // consumed by the apply() of this same draw, before the next upload refills the list
        KGSamplerBinder.stage(shader, bindings);

        // the thumbnail drops the intensity
        var tint = (preview ? color.withIntensity(1f) : color).toVector4f();
        shader.safeGetUniform("KilaTint").set(tint.x, tint.y, tint.z, tint.w);
        var back = backColor.toVector4fOpaque();
        shader.safeGetUniform("KilaBackTint").set(back.x, back.y, back.z, 1f);
        var glow = emission.toVector4fOpaque();
        shader.safeGetUniform("KilaEmission").set(glow.x, glow.y, glow.z, 0f);
        shader.safeGetUniform("KilaSurface").set(alphaClip, alphaSharpen, worldLight ? 1f : 0f, fog ? 1f : 0f);
        // the slot's blend wants what a TextureMaterial gives it: straight colour
        shader.safeGetUniform("KilaSurfaceModes").set(vertexColor.ordinal(),
                slotRenderState ? BlendMode.Preset.ALPHA.ordinal() : blend.ordinal(), 0, 0);
        shader.safeGetUniform("KilaOutput").set(additive, 0f, 0f, 0f);
        shader.safeGetUniform("KilaPreview").set(preview ? previewValue() : -1f);
        shader.safeGetUniform("KilaDebug").set(debugView.ordinal(), 0, 0, 0);
        if (cameraOffset != 0) shader.safeGetUniform("KilaCameraOffset").set(preview ? 0f : cameraOffset);
        if (frameRate > 0) shader.safeGetUniform("KilaTimeStep").set(frameRate, boil, 0f, 0f);
        if (layout.world) {
            var pipeline = preview ? null : RenderPassPipeline.getCurrent();
            var camera = pipeline == null ? null : pipeline.getCamera();
            var position = camera == null ? Vec3.ZERO : camera.getPosition();
            shader.safeGetUniform("KilaCameraPos").set((float) position.x, (float) position.y, (float) position.z);
        }

        for (int group = 0; group < KilaParam.GROUPS; group++) {
            Arrays.fill(base[group], 0);
            Arrays.fill(scale[group], 0);
            Arrays.fill(source[group], 0);
        }
        stage(KilaParam.ALPHA, alpha);
        stage(KilaParam.INTENSITY, intensity);
        for (var module : modules) {
            if (!active(module, layout)) continue;
            module.drivers(stager);
            module.apply(shader, context, scratch);
        }
        for (int group = 0; group < KilaParam.GROUPS; group++) {
            if (!layout.groups[group]) continue;
            var b = base[group];
            var s = scale[group];
            var c = source[group];
            shader.safeGetUniform(BASE[group]).set(b[0], b[1], b[2], b[3]);
            shader.safeGetUniform(SCALE[group]).set(s[0], s[1], s[2], s[3]);
            shader.safeGetUniform(SOURCE[group]).set(c[0], c[1], c[2], c[3]);
        }
    }

    private void stage(KilaParam param, KilaDriver driver) {
        base[param.group][param.component] = driver.value;
        scale[param.group][param.component] = driver.isDriven() ? driver.scale : 0;
        source[param.group][param.component] = driver.code();
    }

    /** 0 to 1 and back every three seconds, standing in for particle data in the thumbnail. */
    private static float previewValue() {
        double t = (System.currentTimeMillis() % 3000L) / 3000.0;
        return (float) (0.5 - 0.5 * Math.cos(t * Math.PI * 2));
    }

    // ---- what the pass has to provide -------------------------------------------------------------

    private void scanDrivers() {
        syncStacking(false);
        scanMask = 0;
        scanCustom = false;
        scanParticleData = false;
        scanner.accept(KilaParam.ALPHA, alpha);
        scanner.accept(KilaParam.INTENSITY, intensity);
        var layout = layout();
        for (var module : modules) {
            if (active(module, layout)) module.drivers(scanner);
        }
    }

    @Override
    public long getUsedChannelMask() {
        scanDrivers();
        return scanMask;
    }

    @Override
    public boolean usesCustomData() {
        scanDrivers();
        return scanCustom;
    }

    @Override
    public int[] requiredCustomChannels() {
        var needed = new int[4];
        BiConsumer<KilaParam, KilaDriver> collect = (param, driver) -> {
            if (driver.readsCustomData()) {
                int stream = Math.clamp(driver.stream, 0, 3);
                needed[stream] = Math.max(needed[stream], Math.clamp(driver.channel, 0, 3) + 1);
            }
        };
        collect.accept(KilaParam.ALPHA, alpha);
        collect.accept(KilaParam.INTENSITY, intensity);
        syncStacking(false);
        var layout = layout();
        for (var module : modules) {
            if (active(module, layout)) module.drivers(collect);
        }
        return needed;
    }

    /** Whether a parameter follows per-particle data, which only some draws carry (the rest read 0). */
    public boolean readsParticleData() {
        scanDrivers();
        return scanParticleData;
    }

    @Override
    @Nullable
    public MaterialRenderState getPreferredRenderState() {
        if (slotRenderState) return null;
        // a projection paints the very terrain that stands in front of its quad
        boolean tested = depthTest && !projection.isEnable();
        boolean twoPass = doubleSided && backFacesFirst;
        int key = blend.ordinal() | (doubleSided ? 16 : 0) | (tested ? 32 : 0) | (depthWrite ? 64 : 0) | (twoPass ? 128 : 0);
        if (renderState == null || key != renderStateKey) {
            renderState = new MaterialRenderState(blend.create(), !doubleSided || twoPass, tested, depthWrite, twoPass);
            renderStateKey = key;
        }
        return renderState;
    }

    @Override
    public IMaterial copy() {
        var copy = new KilaMaterial();
        var provider = Platform.getFrozenRegistry();
        copy.deserializeNBT(provider, serializeNBT(provider));
        return copy;
    }

    /** A KilaMaterial that draws what {@code source} draws; blend, cull and depth stay with each slot. */
    public static KilaMaterial fromTexture(TextureMaterial source) {
        var kila = new KilaMaterial();
        kila.slotRenderState = true;
        var texture = kila.main.texture;
        texture.texture = source.getTexture();
        var metadata = textureMetadata(source.getTexture());
        texture.nearest = !metadata.isBlur();
        var wrap = metadata.isClamp() ? KilaTexture.Wrap.CLAMP : KilaTexture.Wrap.REPEAT;
        texture.wrap(wrap, wrap);
        kila.alphaClip = source.getDiscardThreshold();
        var hdr = source.getHdr();
        if (source.getHdrMode() == TextureMaterial.HDRMode.ADDITIVE) {
            kila.emission = hdr.copy();
        } else {
            var raw = hdr.toRawVector4f();
            kila.color = new HDRColor(raw.x, raw.y, raw.z, 1, hdr.getIntensity());
        }
        if (source.getPixelArt().isEnable()) {
            kila.pixelate.setEnable(true);
            kila.pixelate.pixels = source.getPixelArt().bits;
        }
        var soft = source.getSoftParticles();
        if (soft.isEnable()) {
            kila.depth.setEnable(true);
            kila.depth.distance = soft.distance;
            kila.depth.power = soft.power;
        }
        var lit = source.getLitParticles();
        if (lit.isEnable()) {
            kila.lit.setEnable(true);
            kila.lit.intensity = lit.intensity;
        }
        return kila;
    }

    private static TextureMetadataSection textureMetadata(ResourceLocation location) {
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(location);
            if (resource.isPresent()) {
                var section = resource.get().metadata().getSection(TextureMetadataSection.SERIALIZER);
                if (section.isPresent()) return section.get();
            }
        } catch (Exception ignored) {
            // Minecraft's own fallback: no blur, no clamp
        }
        return new TextureMetadataSection(false, false);
    }

    /** Take every setting of {@code other}, as if it had been loaded in its place. */
    public void copyFrom(KilaMaterial other) {
        var provider = Platform.getFrozenRegistry();
        deserializeNBT(provider, other.serializeNBT(provider));
    }

    // ---- inspector --------------------------------------------------------------------------------

    @Override
    public void createPreview(ConfiguratorGroup father) {
        var frame = new UIElement().layout(layout -> {
            layout.setAspectRatio(1.0f);
            layout.widthPercent(80);
            layout.alignSelf(AlignItems.CENTER);
            layout.paddingAll(3);
        }).addClass("preview_bg").style(style -> style.backgroundTexture(Sprites.BORDER1_RT1)).moveInlineAsDefault()
                .addChild(previewImage());
        father.addConfigurator(new Configurator("ldlib.gui.editor.group.preview").addChild(frame));
        followPreview(father, frame);

        KilaUI.add(father, new ToggleSelectorConfigurator<>("kila.preview.background", () -> previewBackground,
                v -> previewBackground = v, PreviewBackground.CHECKER, true, List.of(PreviewBackground.values()),
                v -> "kila.preview.background." + v.name().toLowerCase(), v -> v.texture), "kila.preview.background");
        KilaUI.choice(father, "kila.debug_view", DebugView.values(), () -> debugView, v -> debugView = v);
    }

    private UIElement previewImage() {
        var drawn = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.heightPercent(100);
        }).style(style -> style.backgroundTexture(DynamicTexture.of(this::preview)));
        return new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.heightPercent(100);
        }).style(style -> style.backgroundTexture(DynamicTexture.of(() -> previewBackground.texture))).addChild(drawn);
    }

    /**
     * While the preview is scrolled out of sight, a click-through copy floats just outside the inspector's top,
     * outside so it never covers the rows being edited.
     */
    private void followPreview(ConfiguratorGroup father, UIElement frame) {
        float size = 64;
        var corner = new UIElement().layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.width(size);
            layout.height(size);
            layout.paddingAll(3);
        }).style(style -> style.backgroundTexture(Sprites.BORDER1_RT1)).addClasses("preview_bg", "__kila-floating-preview__")
                .moveInlineAsDefault().addChild(previewImage());
        corner.setId(PREVIEW_CORNER_ID);
        corner.selfAndAllChildren().forEach(element -> element.setAllowHitTest(false));
        var placed = new float[]{Float.NaN, Float.NaN};
        father.addEventListener(UIEvents.TICK, e -> {
            var scroller = frame.getFirstAncestorOfType(ScrollerView.class);
            var mui = father.getModularUI();
            if (scroller == null || mui == null) return;
            var port = scroller.viewPort;
            boolean away = frame.getPositionY() + frame.getSizeHeight() < port.getPositionY();
            // a collapsed group still ticks: no corner for a preview that isn't drawn at all
            if (!away || !shownUnder(frame, mui.ui.rootElement)) {
                if (corner.getParent() != null) corner.getParent().removeChild(corner);
                return;
            }
            var root = mui.ui.rootElement;
            if (corner.getParent() == null) root.addChild(corner);
            // left of the inspector, or right of it when there is no room
            float left = port.getPositionX() - root.getContentX() - size - 4;
            if (left < 0) left = port.getPositionX() + port.getSizeWidth() - root.getContentX() + 18;
            float top = port.getPositionY() - root.getContentY() + 2;
            if (left != placed[0] || top != placed[1]) {
                placed[0] = left;
                placed[1] = top;
                corner.layout(layout -> {
                    layout.left(placed[0]);
                    layout.top(placed[1]);
                });
            }
        });
        corner.addEventListener(UIEvents.TICK, e -> {
            var mui = corner.getModularUI();
            if (mui == null) return;
            // the inspector moved on or its tab is hidden: a hidden subtree stops ticking, so it can't take the corner down
            if (!shownUnder(frame, mui.ui.rootElement)) {
                corner.getParent().removeChild(corner);
                return;
            }
            var local = corner.worldToLocal(new Vector2f(mui.getLastMouseX(), mui.getLastMouseY()));
            float opacity = corner.isIntersectWithPoint(local.x, local.y) ? 0.1f : 1f;
            if (corner.getStyle().opacity() != opacity) corner.style(style -> style.opacity(opacity));
        });
    }

    private static boolean shownUnder(UIElement element, UIElement root) {
        for (var e = element; e != null; e = e.getParent()) {
            if (!e.isActive() || !e.isDisplayed() || !e.isVisible()) return false;
            if (e == root) return true;
        }
        return false;
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        createPreview(father);

        var presets = new Configurator();
        presets.inlineContainer.addChild(new Button().setText("kila.preset.browse").setOnClick(e ->
                KilaDialogs.presets(e.currentElement, e.x, e.y, preset -> {
                    copyFrom(preset);
                    presets.notifyChanges();
                })).layout(layout -> layout.flex(1)).style(style -> style.tooltips(Component.translatable("kila.preset.tips"))));
        father.addConfigurator(presets);

        var render = KilaUI.subGroup(father, "kila.render", true);
        KilaUI.bool(render, "kila.render.slot_state", () -> slotRenderState, v -> slotRenderState = v);
        BooleanSupplier own = () -> !slotRenderState;
        KilaUI.showWhen(render, KilaUI.choice(render, "kila.render.blend", BlendMode.Preset.values(), () -> blend,
                v -> blend = v), own);
        KilaUI.showWhen(render, KilaUI.number(render, "kila.render.additive", () -> additive, v -> additive = v, 0, 0, 1),
                () -> !slotRenderState && blend == BlendMode.Preset.PREMULTIPLIED);
        KilaUI.showWhen(render, KilaUI.bool(render, "kila.render.double_sided", () -> doubleSided, v -> doubleSided = v), own);
        KilaUI.showWhen(render, KilaUI.bool(render, "kila.render.back_first", () -> backFacesFirst, v -> backFacesFirst = v),
                () -> !slotRenderState && doubleSided);
        KilaUI.showWhen(render, KilaUI.bool(render, "kila.render.depth_test", () -> depthTest, v -> depthTest = v), own);
        KilaUI.showWhen(render, KilaUI.bool(render, "kila.render.depth_write", () -> depthWrite, v -> depthWrite = v), own);
        KilaUI.number(render, "kila.render.camera_offset", () -> cameraOffset, v -> cameraOffset = v, 0, -8, 8);

        var surface = KilaUI.subGroup(father, "kila.surface", false);
        KilaUI.hdr(surface, "kila.surface.color", () -> color, v -> color = v, true);
        KilaUI.driver(surface, "kila.surface.intensity", intensity, 0, 64);
        KilaUI.driver(surface, "kila.surface.alpha", alpha, 0, 1);
        KilaUI.hdr(surface, "kila.surface.emission", () -> emission, v -> emission = v, false);
        var more = KilaUI.subGroup(surface, "kila.surface.more", true);
        KilaUI.choice(more, "kila.surface.vertex_color", VertexColor.values(), () -> vertexColor, v -> vertexColor = v);
        KilaUI.bool(more, "kila.surface.world_light", () -> worldLight, v -> worldLight = v);
        KilaUI.bool(more, "kila.surface.fog", () -> fog, v -> fog = v);
        KilaUI.number(more, "kila.surface.alpha_clip", () -> alphaClip, v -> alphaClip = v, 0.01f, 0, 1);
        KilaUI.number(more, "kila.surface.alpha_sharpen", () -> alphaSharpen, v -> alphaSharpen = v, 1, 0.05f, 16);
        KilaUI.hdr(more, "kila.surface.back_color", () -> backColor, v -> backColor = v, false);
        KilaUI.number(more, "kila.surface.frame_rate", () -> frameRate, v -> frameRate = v, 0, 0, 60);
        KilaUI.showWhen(more, KilaUI.number(more, "kila.surface.boil", () -> boil, v -> boil = v, 0, 0, 0.2f),
                () -> frameRate > 0);

        var groups = new ArrayList<ConfiguratorGroup>();
        for (var module : modules) {
            var group = new ConfiguratorGroup(module.langKey(), false);
            group.setTips(module.langKey() + ".desc");
            father.addConfigurator(group);
            module.buildConfigurator(group);
            groups.add(group);
        }

        var add = new Configurator();
        add.inlineContainer.addChild(new Button().setText("kila.module.add.button").setOnClick(e ->
                KilaDialogs.modules(e.currentElement, e.x, e.y, this, module -> {
                    module.setEnable(true);
                    groups.get(modules.indexOf(module)).setCollapse(false);
                    add.notifyChanges();
                })).layout(layout -> layout.flex(1)));
        father.addConfigurator(add);

        var status = new Configurator();
        status.addClass("__kila-status__");
        status.label.textStyle(style -> style.textWrap(TextWrap.WRAP).adaptiveHeight(true).adaptiveWidth(false));
        status.label.layout(layout -> layout.widthPercent(100));
        status.label.moveInlineAsDefault();
        father.addConfigurator(status);
        father.addEventListener(UIEvents.TICK, e -> {
            for (int i = 0; i < modules.size(); i++) {
                groups.get(i).setDisplay(modules.get(i).isEnable());
            }
            var current = layout();
            var text = Component.translatable("kila.status.samplers", current.samplers.size(), KilaLayout.SAMPLER_BUDGET);
            for (var dropped : current.dropped) {
                text.append(" ").append(Component.translatable("kila.status.dropped",
                        Component.translatable(dropped.langKey())));
            }
            status.setLabel(text);
        });
    }
}
