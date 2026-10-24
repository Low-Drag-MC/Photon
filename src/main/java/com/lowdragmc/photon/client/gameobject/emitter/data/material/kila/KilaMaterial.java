package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.ToggleSelectorConfigurator;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.photon.client.AutoCloseCleaner;
import com.lowdragmc.photon.client.fx.PreloadContext;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialRenderState;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialRenderTypes;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.ShaderInstanceMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PhotonRenderTypes;
import com.lowdragmc.photon.client.render.StackedDistortion;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.joml.Vector2f;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The built-in all-in-one particle material: switchable modules over one shader, each enabled set its own variant
 * ({@link KilaPipelines}, one {@link KilaBlock}). Each RenderType owns its block values and binds them through a
 * {@link PhotonRenderTypes.MaterialBinder}; the thumbnail has its own (full intensity, pulsing particle data, no scene).
 */
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
    /** A RenderType no draw asked for in this long is let go: editing walks through many variants. */
    private static final long RENDER_TYPE_IDLE_MS = 5000;

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
    private final float[][] base = new float[KilaParam.GROUPS][4];
    private final float[][] scale = new float[KilaParam.GROUPS][4];
    private final int[][] source = new int[KilaParam.GROUPS][4];
    // allocation-free walks: these run per draw
    private final BiConsumer<KilaParam, KilaDriver> stager = this::stage;
    private long textureHash;
    private final Consumer<KilaTexture> textureHasher = texture -> textureHash = textureHash * 31 + texture.structureHash();
    /** what the pass has to provide; the layout has a scan of its own, so neither interrupts the other */
    private final DriverScan scan = new DriverScan();
    private final DriverScan layoutScan = new DriverScan();
    @Nullable
    private transient GpuState gpu;

    public List<KilaModule> modules() {
        return modules;
    }

    // ---- variant ----------------------------------------------------------------------------------

    /** The layout of the current settings, as the scene draws it. */
    public KilaLayout layout() {
        return layout(false);
    }

    /** The layout of the current settings, rebuilt only when something that changes the variant changed. */
    KilaLayout layout(boolean preview) {
        syncStacking(preview);
        long structure = structure();
        int slot = screenDistortion.stacking ? 1 : 0;
        var layout = layouts[slot];
        if (layout == null || structure != layoutStructures[slot]) {
            layout = buildLayout();
            layouts[slot] = layout;
            layoutStructures[slot] = structure;
        }
        return layout;
    }

    private KilaLayout buildLayout() {
        var enabled = new ArrayList<KilaModule>();
        for (var module : keepOrder) {
            if (module.isEnable()) enabled.add(module);
        }
        var layout = layoutBuilder().build(enabled);
        // a record a driver reads must be declared, and a declared one bound: follow getUsedChannelMask /
        // usesCustomData. Defines take no sampler, so the rebuild keeps the same modules.
        layoutScan.reset();
        scanActive(layout, layoutScan);
        if (layoutScan.mask == 0 && !layoutScan.custom) return layout;
        var builder = layoutBuilder();
        if (layoutScan.mask != 0) builder.define(KilaPipelines.DATA_DEFINE);
        if (layoutScan.custom) builder.define(KilaPipelines.CUSTOM_DATA_DEFINE);
        return builder.build(enabled);
    }

    private KilaLayout.Builder layoutBuilder() {
        var builder = KilaLayout.surface();
        // blocks the quad is drawn towards the camera
        if (cameraOffset != 0) builder.define("KILA_CAMERA_OFFSET").float1("KilaCameraOffset", 0);
        // x = steps per second, y = how far the uv jumps each step
        if (frameRate > 0) builder.define("KILA_TIME_STEP").vec4("KilaTimeStep", 12, 0, 0, 0);
        return builder;
    }

    private long structure() {
        textureHash = (cameraOffset != 0 ? 2 : 1) + (frameRate > 0 ? 4 : 0);
        textureHash = textureHash * 31 + needs(KilaParam.ALPHA, alpha) * 4L + needs(KilaParam.INTENSITY, intensity);
        for (var module : modules) {
            textureHash = textureHash * 31 + (module.isEnable() ? 1 : 0);
            if (!module.isEnable()) continue;
            textureHash = textureHash * 31 + module.structure();
            module.textures(textureHasher);
            // a driver starting or stopping to read particle data changes what the program declares
            layoutScan.reset();
            module.drivers(layoutScan);
            textureHash = textureHash * 31 + layoutScan.needs();
        }
        return textureHash;
    }

    private int needs(KilaParam param, KilaDriver driver) {
        layoutScan.reset();
        layoutScan.accept(param, driver);
        return layoutScan.needs();
    }

    private boolean active(KilaModule module, KilaLayout layout) {
        return module.isEnable() && !layout.dropped.contains(module);
    }

    /** Its file textures, which the binder loads on the first draw, and its baked gradients. */
    @Override
    public void preload(PreloadContext context) {
        for (var texture : layout().textures) {
            if (texture.texture() != null) {
                context.texture(texture.texture().getTexture());
            } else if (texture.dynamic() != null) {
                texture.dynamic().textureView();
            }
        }
    }

    /** Stacked only where the frame can be bent, and not while a debug view replaces the output. */
    private void syncStacking(boolean preview) {
        screenDistortion.stacking = screenDistortion.isEnable() && screenDistortion.stack && !preview
                && debugView == DebugView.OFF && StackedDistortion.available();
    }

    private static boolean stacked(KilaLayout layout) {
        return layout.defines.contains(KilaScreenDistortion.STACK_DEFINE);
    }

    @Override
    @Nullable
    public RenderType getRenderType(MaterialSetting setting, VertexFormat.Mode mode) {
        var layout = layout(false);
        var key = setting.pipelineKey(mode);
        // the slot's depth test would hide the projection behind the terrain it paints
        if (slotRenderState && active(projection, layout)) key = KilaPipelines.untested(key);
        if (stacked(layout)) key = KilaPipelines.stacked(key);
        return renderType(layout, key, false);
    }

    @Override
    @Nullable
    public RenderType getPreviewRenderType(MaterialSetting setting, VertexFormat.Mode mode) {
        var layout = layout(true);
        var state = getPreferredRenderState();
        var blendMode = state == null ? STRAIGHT_BLEND : state.blendMode();
        var function = blendMode.toBlendFunction();
        int equation = function != null && blendMode.getBlendFunc() != null ? blendMode.getBlendFunc().op
                : PhotonPipelines.BLEND_EQUATION_ADD;
        return renderType(layout, KilaPipelines.preview(function, equation, mode), true);
    }

    private RenderType renderType(KilaLayout layout, PhotonPipelines.ParticlePipelineKey key, boolean preview) {
        var state = gpu();
        var typeKey = new TypeKey(layout.key, key, preview);
        var now = System.currentTimeMillis();
        var entry = state.renderTypes.get(typeKey);
        if (entry != null) {
            entry.lastUsed = now;
            return entry.renderType;
        }
        var lease = new KilaPipelines.Lease();
        var pipeline = KilaPipelines.get(lease, layout, key, null, false);
        if (pipeline == null) {
            lease.close();
            // visibly broken rather than invisible — the same as MissingMaterial
            return MaterialRenderTypes.hdrParticle(MissingTextureAtlasSprite.getLocation(), key);
        }
        state.evictIdle(now);
        var values = new KilaValues(preview ? "KilaMaterial preview UBO" : "KilaMaterial UBO", layout.block);
        var binder = new Binder(this, layout, values, preview);
        // nothing of the material in here: these sit in the drain's indexes until its Cleaner drops them
        PhotonRenderTypes.PipelineSource pipelines = (variant, cpuData) -> KilaPipelines.get(lease, layout, key, variant, cpuData);
        var info = new PhotonRenderTypes.PhotonDrawInfo(
                new PhotonRenderTypes.PhotonDrawInfo.Programs(pipeline),
                new PhotonRenderTypes.PhotonDrawInfo.Bindings(Map.of(), layout.sceneSamplers, null, null, binder),
                new PhotonRenderTypes.PhotonDrawInfo.InstancedRecipe(key, null, null, pipelines));
        var renderType = PhotonRenderTypes.createMaterialOwned("photon_kila", info, true);
        state.renderTypes.put(typeKey, new Entry(renderType, values, lease, now));
        return renderType;
    }

    /** This material's GPU state, freed on the render thread once the material is gone. */
    private GpuState gpu() {
        if (gpu == null) {
            gpu = new GpuState();
            AutoCloseCleaner.registerRenderThread(this, gpu);
        }
        return gpu;
    }

    private record TypeKey(String layout, PhotonPipelines.ParticlePipelineKey state, boolean preview) {
    }

    /** One RenderType and what only it uses: its values of the variant's block, and its hold on the pipelines. */
    private static final class Entry {
        final RenderType renderType;
        final KilaValues values;
        final KilaPipelines.Lease lease;
        long lastUsed;

        Entry(RenderType renderType, KilaValues values, KilaPipelines.Lease lease, long lastUsed) {
            this.renderType = renderType;
            this.values = values;
            this.lease = lease;
            this.lastUsed = lastUsed;
        }

        void close() {
            PhotonRenderTypes.dropCustomShader(renderType);
            values.close();
            lease.close();
        }
    }

    /** The RenderTypes this material owns. Holds nothing of the material, or its Cleaner could not fire. */
    private static final class GpuState implements AutoCloseable {
        final Map<TypeKey, Entry> renderTypes = new HashMap<>();

        /**
         * Let go of what no draw asked for lately. Not at once: a job baked this frame may still draw it, and without
         * draw info the drain would bind none of Kila's state.
         */
        void evictIdle(long now) {
            renderTypes.values().removeIf(entry -> {
                if (now - entry.lastUsed < RENDER_TYPE_IDLE_MS) return false;
                entry.close();
                return true;
            });
        }

        @Override
        public void close() {
            renderTypes.values().forEach(Entry::close);
            renderTypes.clear();
        }
    }

    /**
     * Fills and binds one RenderType's block and textures. Holds the material weakly — the binder sits in the
     * drain's indexes — and once the material is gone the block keeps what it last held.
     */
    private static final class Binder implements PhotonRenderTypes.MaterialBinder {
        private final WeakReference<KilaMaterial> material;
        private final KilaLayout layout;
        private final KilaValues values;
        private final boolean preview;
        private final GpuTextureView[] views;
        private final GpuSampler[] samplers;

        Binder(KilaMaterial material, KilaLayout layout, KilaValues values, boolean preview) {
            this.material = new WeakReference<>(material);
            this.layout = layout;
            this.values = values;
            this.preview = preview;
            this.views = new GpuTextureView[layout.textures.size()];
            this.samplers = new GpuSampler[layout.textures.size()];
        }

        @Override
        public void prepare() {
            var owner = material.get();
            if (owner != null) owner.pack(values, layout, preview);
            values.upload();
            var textureManager = Minecraft.getInstance().getTextureManager();
            for (int i = 0; i < views.length; i++) {
                var texture = layout.textures.get(i);
                if (texture.texture() != null) {
                    // the first use of a texture uploads it: here, before the pass opens
                    views[i] = textureManager.getTexture(texture.texture().getTexture()).getTextureView();
                    samplers[i] = KilaSamplers.of(texture.texture());
                } else if (texture.dynamic() != null) {
                    views[i] = texture.dynamic().textureView();
                    samplers[i] = KilaSamplers.gradient();
                }
            }
        }

        @Override
        public void bind(RenderPass pass) {
            var slice = values.slice();
            if (slice != null) pass.setUniform(KilaBlock.NAME, slice);
            for (int i = 0; i < views.length; i++) {
                if (views[i] != null) pass.bindTexture(layout.textures.get(i).name(), views[i], samplers[i]);
            }
        }
    }

    // ---- values -----------------------------------------------------------------------------------

    /** Everything {@code layout}'s program reads, into {@code values}: what 1.21 uploaded before every draw. */
    void pack(KilaValues values, KilaLayout layout, boolean preview) {
        syncStacking(preview);
        values.clear();
        layout.seed(values);
        // the thumbnail drops the intensity
        var tint = (preview ? color.withIntensity(1f) : color).toVector4f();
        values.set("KilaTint", tint.x, tint.y, tint.z, tint.w);
        var back = backColor.toVector4fOpaque();
        values.set("KilaBackTint", back.x, back.y, back.z, 1f);
        var glow = emission.toVector4fOpaque();
        values.set("KilaEmission", glow.x, glow.y, glow.z, 0f);
        values.set("KilaSurface", alphaClip, alphaSharpen, worldLight ? 1f : 0f, fog ? 1f : 0f);
        // the slot's blend wants what a TextureMaterial gives it: straight colour
        values.set("KilaSurfaceModes", vertexColor.ordinal(),
                slotRenderState ? BlendMode.Preset.ALPHA.ordinal() : blend.ordinal(), 0, 0);
        values.set("KilaOutput", additive, 0f, 0f, 0f);
        values.set("KilaPreview", preview ? previewValue() : -1f);
        values.set("KilaDebug", debugView.ordinal(), 0, 0, 0);
        if (cameraOffset != 0) values.set("KilaCameraOffset", preview ? 0f : cameraOffset);
        if (frameRate > 0) values.set("KilaTimeStep", frameRate, boil, 0f, 0f);

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
            module.apply(values, preview, scratch);
        }
        for (int group = 0; group < KilaParam.GROUPS; group++) {
            if (!layout.groups[group]) continue;
            var b = base[group];
            var s = scale[group];
            var c = source[group];
            values.set(BASE[group], b[0], b[1], b[2], b[3]);
            values.set(SCALE[group], s[0], s[1], s[2], s[3]);
            values.set(SOURCE[group], c[0], c[1], c[2], c[3]);
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

    /** What a set of drivers reads. */
    private static final class DriverScan implements BiConsumer<KilaParam, KilaDriver> {
        long mask;
        boolean custom;

        void reset() {
            mask = 0;
            custom = false;
        }

        int needs() {
            return (mask != 0 ? 1 : 0) | (custom ? 2 : 0);
        }

        @Override
        public void accept(KilaParam param, KilaDriver driver) {
            mask |= driver.channelMask();
            custom |= driver.readsCustomData();
        }
    }

    private void scanActive(KilaLayout layout, DriverScan into) {
        into.accept(KilaParam.ALPHA, alpha);
        into.accept(KilaParam.INTENSITY, intensity);
        for (var module : modules) {
            if (active(module, layout)) module.drivers(into);
        }
    }

    private void scanDrivers() {
        var layout = layout(false);
        scan.reset();
        scanActive(layout, scan);
    }

    @Override
    public long getUsedChannelMask() {
        scanDrivers();
        return scan.mask;
    }

    @Override
    public boolean usesCustomData() {
        scanDrivers();
        return scan.custom;
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
        var layout = layout(false);
        for (var module : modules) {
            if (active(module, layout)) module.drivers(collect);
        }
        return needed;
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
        PersistedParser.deserializeNBT(PersistedParser.serializeNBT(this, provider), copy, provider);
        return copy;
    }

    /** A KilaMaterial that draws what {@code source} draws; blend, cull and depth stay with each slot. */
    public static KilaMaterial fromTexture(TextureMaterial source) {
        var kila = new KilaMaterial();
        kila.slotRenderState = true;
        var texture = kila.main.texture;
        texture.texture = source.getTexture();
        var metadata = textureMetadata(source.getTexture());
        texture.nearest = !metadata.blur();
        var wrap = metadata.clamp() ? KilaTexture.Wrap.CLAMP : KilaTexture.Wrap.REPEAT;
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

    private static TextureMetadataSection textureMetadata(Identifier location) {
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(location);
            if (resource.isPresent()) {
                var section = resource.get().metadata().getSection(TextureMetadataSection.TYPE);
                if (section.isPresent()) return section.get();
            }
        } catch (Exception ignored) {
            // Minecraft's own fallback: no blur, no clamp
        }
        return new TextureMetadataSection(TextureMetadataSection.DEFAULT_BLUR, TextureMetadataSection.DEFAULT_CLAMP,
                MipmapStrategy.AUTO, TextureMetadataSection.DEFAULT_ALPHA_CUTOFF_BIAS);
    }

    /** Take every setting of {@code other}, as if it had been loaded in its place. */
    public void copyFrom(KilaMaterial other) {
        var provider = Platform.getFrozenRegistry();
        PersistedParser.deserializeNBT(PersistedParser.serializeNBT(other, provider), this, provider);
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
                .addChild(previewImage(true));
        father.addConfigurator(new Configurator("ldlib.gui.editor.group.preview").addChild(frame));
        followPreview(father, frame);

        KilaUI.add(father, new ToggleSelectorConfigurator<>("kila.preview.background", () -> previewBackground,
                v -> previewBackground = v, PreviewBackground.CHECKER, true, List.of(PreviewBackground.values()),
                v -> "kila.preview.background." + v.name().toLowerCase(), v -> v.texture), "kila.preview.background");
        KilaUI.choice(father, "kila.debug_view", DebugView.values(), () -> debugView, v -> debugView = v);
    }

    /** {@code live}: rendered every frame at the size it is drawn — the inspector's one large preview; else cached. */
    private UIElement previewImage(boolean live) {
        var drawn = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.heightPercent(100);
        }).style(style -> style.backgroundTexture(DynamicTexture.of(live ? this::previewLive : this::preview)));
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
                .moveInlineAsDefault().addChild(previewImage(false));
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
