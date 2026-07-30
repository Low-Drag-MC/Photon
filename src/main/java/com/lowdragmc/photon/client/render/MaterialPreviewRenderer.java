package com.lowdragmc.photon.client.render;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Off-screen live preview for materials: renders one billboard quad through the material's actual
 * {@link RenderType} into a {@link GpuTexture}, so previews show the real render (pixel/HDR/blend/discard)
 * instead of the raw source texture or a MISSING placeholder.
 * <p>
 * <b>Two tiers.</b> {@link #livePreviewOf} backs the inspector's preview: there is one of it, so it renders
 * EVERY frame at the resolution it is actually drawn at (slot size x GUI scale) — animation and uniform
 * edits show up immediately and it is never up- or down-scaled. {@link #previewOf} backs the resource-panel
 * tiles and the inline material slots, where dozens can be on screen: those share a fixed small target,
 * are cached, and only re-render when the material's {@link RenderType} changes or on a slow
 * {@link #REFRESH_MS} cadence.
 * <p>
 * <b>Phase split (mandatory).</b> GUI drawing happens INSIDE an open render pass — creating a pass or
 * uploading a texture there throws {@code "Close the existing render pass before performing additional
 * commands"}. So the GUI side only reads the cache and records a request; all GPU work runs from
 * {@link #processPending()} at {@code RenderFrameEvent.Pre}, where no pass is open.
 * <p>
 * Caches are keyed by material IDENTITY (materials are mutable — hash keys would rot), failures back off
 * instead of retrying every frame, and everything is released after {@link #IDLE_RELEASE_MS} with nothing
 * on screen, which also drops the material references a closed editor would otherwise leave pinned.
 * <p>
 * <b>Why a hand-rolled pass.</b> Photon binds {@code PhotonMaterial}/{@code PhotonEngine}/lightmap (and, for
 * shader graphs, KilaGraph's own blocks) manually in {@code PhotonWorldRenderState} — the RenderSetup does not
 * carry them, so a plain {@code RenderType.draw} would leave them unbound. The preview mirrors that binding
 * sequence for a single quad.
 */
public final class MaterialPreviewRenderer {
    /** Tile/inline preview target edge (px) — small and shared; the inspector sizes its own. */
    private static final int TILE_SIZE = 64;
    private static final int MIN_LIVE_SIZE = 32;
    private static final int MAX_LIVE_SIZE = 512;
    private static final int MAX_ENTRIES = 64;
    /** Tile renders per frame — they fill in over a few frames instead of bursting. */
    private static final int MAX_TILE_RENDERS_PER_FRAME = 8;
    /** Defensive cap; the inspector normally has a handful of material slots at most. */
    private static final int MAX_LIVE_RENDERS_PER_FRAME = 8;
    /** Tile refresh cadence: content can move without a new RenderType (GameTime animation, and
     *  custom-shader uniform edits, whose values live in a UBO the RenderType doesn't key on). */
    private static final long REFRESH_MS = 100;
    private static final long RETRY_MS = 3000;
    /** Drop a live target this long after its slot stopped being drawn (inspector switched material). */
    private static final long LIVE_STALE_MS = 1000;
    /** No preview drawn for this long (panel hidden / editor closed) → release everything. */
    private static final long IDLE_RELEASE_MS = 5000;
    /** A neutral render state for standalone previews (no emitter MaterialSetting in the resource panel). */
    private static final MaterialSetting DEFAULT_SETTING = new MaterialSetting();
    private static final AtomicInteger ID_SEQ = new AtomicInteger();

    // constant draw inputs, shared because the live tier re-renders every frame
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Vector4f NO_MODULATION = new Vector4f(1, 1, 1, 1);
    private static final Vector3f NO_OFFSET = new Vector3f();
    private static final Matrix4f PREVIEW_ORTHO = new Matrix4f().setOrtho(-1, 1, -1, 1, -1, 1);

    /** One preview target, registered so the GUI can blit it by Identifier. */
    private static final class Entry {
        final GpuTexture color;
        final GpuTextureView colorView;
        final Identifier id;
        final int size;
        @Nullable
        RenderType renderType;
        long lastUsed;
        long lastRendered;

        Entry(GpuTexture color, GpuTextureView colorView, Identifier id, int size) {
            this.color = color;
            this.colorView = colorView;
            this.id = id;
            this.size = size;
        }

        void close() {
            // release() removes the registration and closes the AbstractTexture, which closes both the
            // texture and its view — closing them again here would just be redundant
            Minecraft.getInstance().getTextureManager().release(id);
        }
    }

    /** Tile/inline tier: cached + throttled. */
    private static final Map<IMaterial, Entry> CACHE = new IdentityHashMap<>();
    private static final Set<IMaterial> PENDING = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Inspector tier: rendered every frame, target sized to the slot. */
    private static final Map<IMaterial, Entry> LIVE = new IdentityHashMap<>();
    /** material -> target edge in physical px, recorded at draw time (the GUI knows the box, we don't). */
    private static final Map<IMaterial, Integer> LIVE_REQUESTS = new IdentityHashMap<>();
    /** material -> epoch millis before which we don't retry (failed render / not previewable). */
    private static final Map<IMaterial, Long> FAILED = new IdentityHashMap<>();
    /** Entries whose render failed: released on the NEXT frame, because GUI elements already hold their
     *  texture Identifier and closing one mid-frame crashes the GuiRenderer on a closed view. */
    private static final List<Entry> RELEASE_NEXT_FRAME = new ArrayList<>();
    /** When a preview was last drawn — drives {@link #IDLE_RELEASE_MS}. */
    private static long lastRequestMs;

    /** Shared depth attachments, one per target size (a pass needs depth matching its color). */
    private static final Map<Integer, GpuTextureView> DEPTH_VIEWS = new HashMap<>();
    private static final Map<Integer, GpuTexture> DEPTH_TEXTURES = new HashMap<>();
    /** 1x1 stand-ins for the scene captures a custom shader may sample (no scene in a preview). */
    @Nullable
    private static GpuTexture dummyColor;
    @Nullable
    private static GpuTextureView dummyColorView;
    @Nullable
    private static GpuTexture dummyDepth;
    @Nullable
    private static GpuTextureView dummyDepthView;
    /** Shared unit quad in {@link PhotonPipelines#PARTICLE_FORMAT}. */
    @Nullable
    private static GpuBuffer quad;
    @Nullable
    private static ProjectionMatrixBuffer projBuffer;

    private MaterialPreviewRenderer() {
    }

    /**
     * Inspector preview: re-rendered every frame at the resolution it is drawn at. Use for the single
     * large preview; for many-on-screen tiles use {@link #previewOf}.
     */
    public static IGuiTexture livePreviewOf(IMaterial material) {
        return GuiTexture.of((context, x, y, width, height) -> {
            lastRequestMs = System.currentTimeMillis();
            LIVE_REQUESTS.put(material, targetPixels(width, height));
            blit(context, LIVE.get(material), x, y, width, height);
        });
    }

    /** Tile / inline-slot preview: cached, refreshed on change or on the slow cadence. */
    public static IGuiTexture previewOf(IMaterial material) {
        return GuiTexture.of((context, x, y, width, height) -> {
            lastRequestMs = System.currentTimeMillis();
            PENDING.add(material); // re-validated next frame
            var entry = CACHE.get(material);
            if (entry != null) entry.lastUsed = lastRequestMs;
            blit(context, entry, x, y, width, height);
        });
    }

    private static void blit(GUIContext context,
                             @Nullable Entry entry, float x, float y, float width, float height) {
        var texture = entry != null ? entry.id : MissingTextureAtlasSprite.getLocation();
        context.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, width, height, 0f, 0f, 1f, 1f, -1);
    }

    /** The physical-pixel edge a slot of this GUI size needs, quantized so layout jitter doesn't
     *  rebuild the target every frame. */
    private static int targetPixels(float width, float height) {
        var scale = Minecraft.getInstance().getWindow().getGuiScale();
        var px = (int) Math.ceil(Math.max(width, height) * scale);
        px = Math.clamp(px, MIN_LIVE_SIZE, MAX_LIVE_SIZE);
        return (px + 15) / 16 * 16;
    }

    /**
     * Renders the previews requested by the last GUI frame. MUST run outside any render pass
     * ({@code RenderFrameEvent.Pre}).
     */
    public static void processPending() {
        if (!RenderSystem.isOnRenderThread()) return;
        // a full frame has passed since these failed, so no GUI draw list can still reference them
        if (!RELEASE_NEXT_FRAME.isEmpty()) {
            RELEASE_NEXT_FRAME.forEach(Entry::close);
            RELEASE_NEXT_FRAME.clear();
        }
        var now = System.currentTimeMillis();
        if (PENDING.isEmpty() && LIVE_REQUESTS.isEmpty()) {
            // nothing on screen wants a preview — release everything once we've been idle a while
            if ((!CACHE.isEmpty() || !LIVE.isEmpty()) && now - lastRequestMs > IDLE_RELEASE_MS) releaseAll();
            return;
        }
        processLive(now);
        processTiles(now);
        pruneDepth();
    }

    /**
     * Free depth attachments no live/tile target uses any more. Without this, dragging a panel edge
     * walks the live target through many quantized sizes and every one of them would keep its own
     * depth texture alive (up to ~1&nbsp;MB each at the 512 cap) until the idle release.
     */
    private static void pruneDepth() {
        if (DEPTH_VIEWS.size() <= 1) return;
        var inUse = new HashSet<Integer>();
        if (!CACHE.isEmpty()) inUse.add(TILE_SIZE);
        for (var entry : LIVE.values()) inUse.add(entry.size);
        DEPTH_VIEWS.keySet().removeIf(size -> {
            if (inUse.contains(size)) return false;
            DEPTH_VIEWS.get(size).close();
            var texture = DEPTH_TEXTURES.remove(size);
            if (texture != null) texture.close();
            return true;
        });
    }

    /** Inspector tier: every request, every frame, at its own resolution. */
    private static void processLive(long now) {
        if (LIVE_REQUESTS.isEmpty()) {
            dropStaleLive(now);
            return;
        }
        var requests = new ArrayList<>(LIVE_REQUESTS.entrySet());
        LIVE_REQUESTS.clear();
        var budget = MAX_LIVE_RENDERS_PER_FRAME;
        for (var request : requests) {
            if (budget <= 0) break;
            var material = request.getKey();
            var size = request.getValue();
            var retryAt = FAILED.get(material);
            if (retryAt != null && now < retryAt) continue;
            var resolved = resolve(material, now);
            if (resolved == null) continue;
            var entry = LIVE.get(material);
            if (entry != null && entry.size != size) {
                // the slot resized — rebuild at the new resolution rather than scaling
                entry.close();
                LIVE.remove(material);
                entry = null;
            }
            budget--;
            var rendered = render(material, entry, resolved, size, now);
            if (rendered == null) {
                LIVE.remove(material);
                markFailed(material, now, null, null);
            } else {
                LIVE.put(material, rendered);
                FAILED.remove(material);
            }
        }
        dropStaleLive(now);
    }

    /** Tile tier: render new/changed first, then refresh the stalest with whatever budget is left. */
    private static void processTiles(long now) {
        if (PENDING.isEmpty()) return;
        var requested = new ArrayList<>(PENDING);
        PENDING.clear();
        var budget = MAX_TILE_RENDERS_PER_FRAME;
        var refresh = new ArrayList<Refresh>();
        for (var material : requested) {
            var retryAt = FAILED.get(material);
            if (retryAt != null && now < retryAt) continue;
            var resolved = resolve(material, now);
            if (resolved == null) continue;
            var entry = CACHE.get(material);
            if (entry != null && entry.renderType == resolved.renderType()) {
                if (now - entry.lastRendered >= REFRESH_MS) {
                    refresh.add(new Refresh(material, entry, resolved));
                }
                continue; // up to date
            }
            if (budget <= 0) {
                PENDING.add(material); // spread over the next frames
                continue;
            }
            budget--;
            renderTile(material, entry, resolved, now);
        }
        // stalest first, so a panel full of tiles refreshes round-robin instead of starving the tail
        refresh.sort(Comparator.comparingLong(item -> item.entry().lastRendered));
        for (var item : refresh) {
            if (budget <= 0) break;
            budget--;
            renderTile(item.material(), item.entry(), item.resolved(), now);
        }
        evictOverflow();
    }

    private record Refresh(IMaterial material, Entry entry, Resolved resolved) {
    }

    private record Resolved(RenderType renderType, PhotonRenderTypes.PhotonDrawInfo info) {
    }

    /** Resolve the material's RenderType (+ Photon draw info when it owns one), or null (with backoff
     *  recorded) when the material can't render at all. */
    @Nullable
    private static Resolved resolve(IMaterial material, long now) {
        RenderType renderType;
        PhotonRenderTypes.PhotonDrawInfo info;
        try {
            renderType = material.getRenderType(DEFAULT_SETTING, VertexFormat.Mode.QUADS);
            info = renderType == null ? null : PhotonRenderTypes.drawInfo(renderType);
        } catch (Exception e) {
            markFailed(material, now, "render type resolution failed", e);
            return null;
        }
        if (renderType == null || info == null) {
            // not previewable: no render type, or one Photon owns no draw info for
            markFailed(material, now, null, null);
            return null;
        }
        return new Resolved(renderType, info);
    }

    private static void renderTile(IMaterial material, @Nullable Entry entry, Resolved resolved, long now) {
        var rendered = render(material, entry, resolved, TILE_SIZE, now);
        if (rendered == null) {
            CACHE.remove(material); // render() queued the failed target for release next frame
            markFailed(material, now, null, null);
        } else {
            CACHE.put(material, rendered);
            FAILED.remove(material);
        }
    }

    /** Render into {@code entry} (created at {@code size} when null); null on failure (already logged). */
    @Nullable
    private static Entry render(IMaterial material, @Nullable Entry entry, Resolved resolved, int size, long now) {
        try {
            if (entry == null) entry = createEntry(size);
            renderInto(entry, resolved.renderType(), resolved.info());
            entry.renderType = resolved.renderType();
            entry.lastUsed = now;
            entry.lastRendered = now;
            return entry;
        } catch (Exception e) {
            // the caller records the backoff; log once per transition, not per attempt
            if (!FAILED.containsKey(material)) {
                Photon.LOGGER.warn("Material preview render failed: {}", material.getClass().getSimpleName(), e);
            }
            // NOT closed here: this entry's texture is registered under an Identifier that GUI elements
            // already hold, and releasing it mid-frame left the GuiRenderer drawing a closed view
            // ("Texture view Sampler0 has been closed!" — a hard crash). Release a frame later instead.
            if (entry != null) RELEASE_NEXT_FRAME.add(entry);
            return null;
        }
    }

    /** Back off (and log once per failure transition — never per frame). */
    private static void markFailed(IMaterial material, long now, @Nullable String reason, @Nullable Exception e) {
        if (FAILED.put(material, now + RETRY_MS) == null && reason != null) {
            Photon.LOGGER.warn("Material preview unavailable ({}): {}", reason, material.getClass().getSimpleName(), e);
        }
    }

    /** Free live targets whose slot stopped being drawn (e.g. the inspector switched material). */
    private static void dropStaleLive(long now) {
        LIVE.entrySet().removeIf(e -> {
            if (now - e.getValue().lastUsed > LIVE_STALE_MS) {
                e.getValue().close();
                return true;
            }
            return false;
        });
    }

    /** Free every cached target and drop the material references (render thread only). */
    public static void releaseAll() {
        CACHE.values().forEach(Entry::close);
        CACHE.clear();
        LIVE.values().forEach(Entry::close);
        LIVE.clear();
        DEPTH_VIEWS.values().forEach(GpuTextureView::close);
        DEPTH_VIEWS.clear();
        DEPTH_TEXTURES.values().forEach(GpuTexture::close);
        DEPTH_TEXTURES.clear();
        // the 1x1 stand-ins are trivial, but releasing them keeps "idle == nothing held" honest
        if (dummyColorView != null) {
            dummyColorView.close();
            dummyColorView = null;
        }
        if (dummyColor != null) {
            dummyColor.close();
            dummyColor = null;
        }
        if (dummyDepthView != null) {
            dummyDepthView.close();
            dummyDepthView = null;
        }
        if (dummyDepth != null) {
            dummyDepth.close();
            dummyDepth = null;
        }
        RELEASE_NEXT_FRAME.forEach(Entry::close);
        RELEASE_NEXT_FRAME.clear();
        FAILED.clear();
        PENDING.clear();
        LIVE_REQUESTS.clear();
    }

    private static void evictOverflow() {
        while (CACHE.size() > MAX_ENTRIES) {
            Map.Entry<IMaterial, Entry> oldest = null;
            for (var e : CACHE.entrySet()) {
                if (oldest == null || e.getValue().lastUsed < oldest.getValue().lastUsed) oldest = e;
            }
            if (oldest == null) break;
            oldest.getValue().close();
            CACHE.remove(oldest.getKey());
        }
    }

    private static Entry createEntry(int size) {
        var device = RenderSystem.getDevice();
        // sampleable in the GUI (TEXTURE_BINDING) + a render attachment; the pass itself clears it
        var color = device.createTexture(() -> "Photon material preview",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                TextureFormat.RGBA8, size, size, 1, 1);
        var view = device.createTextureView(color);
        var id = Photon.id("material_preview/" + ID_SEQ.getAndIncrement());
        Minecraft.getInstance().getTextureManager().register(id, new AbstractTexture() {{
            this.texture = color;
            this.textureView = view;
            // the tile tier is magnified into its slot and MC's default magnifies LINEAR, which blurs
            // away exactly what a preview must show (pixel-art materials above all). Clamp too — the
            // default REPEAT bleeds the opposite edge in.
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        }});
        return new Entry(color, view, id, size);
    }

    private static void renderInto(Entry entry, RenderType renderType,
                                   PhotonRenderTypes.PhotonDrawInfo info) {
        var device = RenderSystem.getDevice();
        // the live tier runs this every frame — the transform inputs are constant, so share them
        var dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(
                IDENTITY, NO_MODULATION, NO_OFFSET, IDENTITY);

        // resolve textures BEFORE opening the pass: first use uploads to the GPU, illegal inside a pass
        var textureManager = Minecraft.getInstance().getTextureManager();
        var textures = new ArrayList<Map.Entry<String, AbstractTexture>>();
        for (var e : info.bindings().textures().entrySet()) {
            textures.add(Map.entry(e.getKey(), textureManager.getTexture(e.getValue())));
        }

        // shader graphs own their uniforms/textures; upload+resolve them before the pass opens
        if (info.bindings().graph() != null) {
            info.bindings().graph().material().prepareUniforms();
        }

        // Same rule for the scene stand-ins: creating them CLEARS them, and clearColorTexture throws
        // "Close the existing render pass before creating a new one!" inside a pass. They used to be
        // built lazily at bind time (inside the pass), so the first material with a scene sampler —
        // any Scene Color/Depth graph, or the wireframe overlay — always threw.
        var needsSceneColor = PhotonPipelines.isWireframe(info.programs().main());
        var needsSceneDepth = false;
        for (var name : info.bindings().sceneSamplers()) {
            if (name.contains("Depth")) needsSceneDepth = true;
            else needsSceneColor = true;
        }
        if (needsSceneColor) dummyColorView();
        if (needsSceneDepth) dummyDepthView();

        RenderSystem.backupProjectionMatrix();
        // ortho that maps the unit quad straight to the target: ProjMat * identity(ModelView) * (±1,±1,0)
        RenderSystem.setProjectionMatrix(projBuffer().getBuffer(PREVIEW_ORTHO),
                ProjectionType.ORTHOGRAPHIC);
        try (RenderPass pass = device.createCommandEncoder().createRenderPass(
                () -> "Photon material preview", entry.colorView, OptionalInt.of(0),
                depthView(entry.size), OptionalDouble.of(1.0))) {
            pass.setPipeline(info.programs().main());
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            var materialSlice = PhotonMaterialUniforms.sliceFor(renderType);
            if (materialSlice != null) pass.setUniform("PhotonMaterial", materialSlice);
            var engineSlice = PhotonEngineUniforms.sliceFor(renderType);
            if (engineSlice != null) pass.setUniform("PhotonEngine", engineSlice);
            var custom = info.bindings().customUniforms();
            var customSlice = custom == null ? null : custom.slice();
            if (customSlice != null) pass.setUniform("PhotonCustomMaterial", customSlice);
            for (var e : textures) {
                pass.bindTexture(e.getKey(), e.getValue().getTextureView(), e.getValue().getSampler());
            }
            pass.bindTexture("Sampler2", Minecraft.getInstance().gameRenderer.lightmap(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            // A preview has no scene of its own, but every declared sampler must still be bound — and a
            // blank stand-in makes a Scene Color/Depth material preview a useless flat black. Prefer the
            // most recent capture the real drain took (the world or the editor scene, whichever drew last),
            // which is what makes these previews show the same content they will sample in place.
            var neutral = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            var previewSceneColor = PhotonSceneCapture.lastColorView();
            var previewSceneDepth = PhotonSceneCapture.lastDepthView();
            var sceneSampler = previewSceneColor != null || previewSceneDepth != null
                    ? PhotonSceneCapture.sampler() : neutral;
            if (info.bindings().graph() != null) {
                info.bindings().graph().material().bindCustomUniforms(pass);
            }
            // Photon-owned scene samplers, after the graph's own binds — see PhotonWorldRenderState.drawRun.
            if (PhotonPipelines.isWireframe(info.programs().main())) {
                pass.bindTexture("SamplerSceneColor",
                        previewSceneColor != null ? previewSceneColor : dummyColorView(), sceneSampler);
            }
            for (var name : info.bindings().sceneSamplers()) {
                GpuTextureView view;
                if (name.contains("Depth")) {
                    view = previewSceneDepth != null ? previewSceneDepth : dummyDepthView();
                } else {
                    view = previewSceneColor != null ? previewSceneColor : dummyColorView();
                }
                pass.bindTexture(name, view, sceneSampler);
            }
            var autoIndices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            pass.setVertexBuffer(0, quad());
            pass.setIndexBuffer(autoIndices.getBuffer(6), autoIndices.type());
            pass.drawIndexed(0, 0, 6, 1);
        } finally {
            RenderSystem.restoreProjectionMatrix();
        }
    }

    /** Depth attachment for a target of this edge (previews never read it; a cleared depth just lets
     *  depthTest pass). Shared per size — target sizes are quantized, so there are only a few. */
    private static GpuTextureView depthView(int size) {
        return DEPTH_VIEWS.computeIfAbsent(size, edge -> {
            var device = RenderSystem.getDevice();
            var format = depthFormat();
            var texture = device.createTexture(() -> "Photon material preview depth",
                    GpuTexture.USAGE_RENDER_ATTACHMENT, format, edge, edge, 1, 1);
            DEPTH_TEXTURES.put(edge, texture);
            return device.createTextureView(texture);
        });
    }

    /** The main target's depth format, so a stencil-using pipeline (the mask materials) still gets a
     *  stencil aspect; the superset is the fallback if the main target ever has no depth attachment. */
    private static TextureFormat depthFormat() {
        var depth = Minecraft.getInstance().getMainRenderTarget().getDepthTexture();
        return depth == null ? TextureFormat.DEPTH32_STENCIL8 : depth.getFormat();
    }

    private static GpuTextureView dummyColorView() {
        if (dummyColorView == null) {
            var device = RenderSystem.getDevice();
            // COPY_DST is required by clearColorTexture below (verifyColorTexture rejects without it)
            dummyColor = device.createTexture(() -> "Photon preview scene color stand-in",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT
                            | GpuTexture.USAGE_COPY_DST,
                    TextureFormat.RGBA8, 1, 1, 1, 1);
            dummyColorView = device.createTextureView(dummyColor);
            device.createCommandEncoder().clearColorTexture(dummyColor, 0);
        }
        return dummyColorView;
    }

    private static GpuTextureView dummyDepthView() {
        if (dummyDepthView == null) {
            var device = RenderSystem.getDevice();
            var format = depthFormat();
            dummyDepth = device.createTexture(() -> "Photon preview scene depth stand-in",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT
                            | GpuTexture.USAGE_COPY_DST, format, 1, 1, 1, 1);
            dummyDepthView = device.createTextureView(dummyDepth);
            device.createCommandEncoder().clearDepthTexture(dummyDepth, 1.0);
        }
        return dummyDepthView;
    }

    private static ProjectionMatrixBuffer projBuffer() {
        if (projBuffer == null) projBuffer = new ProjectionMatrixBuffer("photon_material_preview");
        return projBuffer;
    }

    /** Unit quad (±1): full UV, white, full-bright, +Z normal. */
    private static void emitQuad(VertexConsumer builder) {
        builder.addVertex(-1, -1, 0).setColor(-1).setUv(0, 1).setLight(0xF000F0).setNormal(0, 0, 1);
        builder.addVertex(1, -1, 0).setColor(-1).setUv(1, 1).setLight(0xF000F0).setNormal(0, 0, 1);
        builder.addVertex(1, 1, 0).setColor(-1).setUv(1, 0).setLight(0xF000F0).setNormal(0, 0, 1);
        builder.addVertex(-1, 1, 0).setColor(-1).setUv(0, 0).setLight(0xF000F0).setNormal(0, 0, 1);
    }

    /** The cached quad vertex buffer for the Photon-pass path (see {@link #emitQuad}). */
    private static GpuBuffer quad() {
        if (quad == null) {
            var builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, PhotonPipelines.PARTICLE_FORMAT);
            emitQuad(builder);
            try (var mesh = builder.buildOrThrow()) {
                quad = RenderSystem.getDevice().createBuffer(() -> "Photon material preview quad",
                        GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
        return quad;
    }
}
