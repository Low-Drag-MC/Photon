package com.lowdragmc.photon.gui.editor.view.scene;

import com.lowdragmc.lowdraglib2.client.utils.RenderBufferUtils;
import com.lowdragmc.lowdraglib2.editor.ui.View;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.SceneEditor;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProjectEffectExecutor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.vfyjxf.taffy.style.FlexDirection;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.opengl.GL11;

import java.util.List;

public class SceneView extends View {
    public enum SceneMode {
        PLATFORM("scene_mode.platform"),
        REAL_WORLD("scene_mode.real_world");

        public final String translateKey;

        SceneMode(String translateKey) {
            this.translateKey = translateKey;
        }
    }
    public enum DrawMode {
        DRAW("draw_mode.draw"),
        WIREFRAME("draw_mode.wireframe");

        public final String translateKey;

        DrawMode(String translateKey) {
            this.translateKey = translateKey;
        }
    }
    /**
     * The Minecraft-style scene sky preset: an OPAQUE horizon (fog) color the FBO clears to, plus the
     * sky-dome zenith color and day/night celestial set drawn by {@link SceneSkyRenderer}. Opaque on
     * purpose: the scene panel then composites like any other texture and the particle pipeline behaves
     * exactly as in-game — a transparent background would need premultiplied-alpha handling through the
     * whole pipeline (blend funcs, bloom, blits).
     */
    public enum SceneBackground {
        DAY("scene_background.day", 0xFFAFC9FF, 0xFF78A7FF, true),
        NIGHT("scene_background.night", 0xFF0B0E1A, 0xFF05070F, false);

        public final String translateKey;
        /** The FBO clear color — what shows at/below the horizon, like vanilla's fog-colored void. */
        public final int horizonColor;
        /** The sky-dome (zenith) tint. */
        public final int skyColor;
        /** Sun (day) vs full moon + stars (night). */
        public final boolean day;

        SceneBackground(String translateKey, int horizonColor, int skyColor, boolean day) {
            this.translateKey = translateKey;
            this.horizonColor = horizonColor;
            this.skyColor = skyColor;
            this.day = day;
        }
    }
    public final FXEditor fxEditor;
    public final ParticleSceneEditor sceneEditor;
    public final TrackedDummyWorld level = new TrackedDummyWorld();
    public final PhotonParticleManager particleManager = new PhotonParticleManager(this);
    public final FXProjectEffectExecutor effect = new FXProjectEffectExecutor(level);
    public final FXObjectInfoView fxObjectInfoView = new FXObjectInfoView(this);
    public final FXObjectAnimationView fxObjectAnimationView = new FXObjectAnimationView(this);
    @Getter @Setter
    private boolean isShapeVisible = true;
    @Getter @Setter
    private boolean isCullBoxVisible = true;
    @Getter
    private SceneMode sceneMode = SceneMode.PLATFORM;
    @Getter @Setter
    private DrawMode drawMode = DrawMode.DRAW;
    @Getter
    private SceneBackground sceneBackground = SceneBackground.NIGHT;
    @Getter
    private int sceneRange = 6;
    // runtime
    private boolean isSceneLoaded = false;

    public SceneView(FXEditor fxEditor) {
        super("editor.scene", Icons.CAMERA);
        this.getLayout().widthPercent(100.0F);
        this.getLayout().heightPercent(100.0F);
        this.fxEditor = fxEditor;
        level.setParticleManager(particleManager);

        sceneEditor = new ParticleSceneEditor();
        sceneEditor.layout(layout -> {
            layout.widthPercent(100);
            layout.flex(1);
        });
        // FBO-backed scene: the world (and the particle pipeline) render into a dedicated full-viewport
        // framebuffer instead of a sub-viewport of the window — so screen-space shader inputs
        // (gl_FragCoord / U_ViewPort / scene color+depth) are always consistent. The FBO is resized to
        // the panel's real pixel resolution every tick (see screenTick).
        sceneEditor.scene
                .createScene(level, true, null)
                .setTickWorld(true)
                .useCacheBuffer();
        this.addChild(sceneEditor);
        this.addChild(fxObjectInfoView);
        this.addChild(fxObjectAnimationView);
        applySceneBackground();
        // The MC-style skybox (dome + sun/moon/stars) draws right after the clear, before the world.
        var renderer = sceneEditor.scene.getRenderer();
        if (renderer != null) {
            renderer.setBeforeWorldRender(r -> SceneSkyRenderer.render(r, sceneBackground));
        }
    }

    public void setSceneBackground(SceneBackground background) {
        if (background == null || this.sceneBackground == background) return;
        this.sceneBackground = background;
        applySceneBackground();
    }

    /** Push the selected background's horizon color as the scene FBO's opaque clear color. */
    private void applySceneBackground() {
        if (sceneEditor.scene.getRenderer() instanceof com.lowdragmc.lowdraglib2.client.scene.FBOWorldSceneRenderer fboRenderer) {
            var color = sceneBackground.horizonColor;
            fboRenderer.setClearColor(
                    ((color >> 16) & 0xFF) / 255f,
                    ((color >> 8) & 0xFF) / 255f,
                    (color & 0xFF) / 255f,
                    1f);
        }
    }

    /** The scene's offscreen render target when the FBO renderer is active (else null). */
    @org.jetbrains.annotations.Nullable
    public com.mojang.blaze3d.pipeline.RenderTarget getSceneRenderTarget() {
        return sceneEditor.scene.getRenderer() instanceof com.lowdragmc.lowdraglib2.client.scene.FBOWorldSceneRenderer fboRenderer
                ? fboRenderer.getFbo() : null;
    }

    /**
     * Keep the scene FBO at the panel's true pixel resolution: the element's content size is in
     * GUI-scaled units, so multiply by the window's gui scale to get real framebuffer pixels — a resize
     * of the panel or a gui-scale change retargets the FBO (cheap no-op when unchanged).
     */
    @Override
    public void screenTick() {
        super.screenTick();
        if (sceneEditor.scene.getRenderer() instanceof com.lowdragmc.lowdraglib2.client.scene.FBOWorldSceneRenderer fboRenderer) {
            var guiScale = Minecraft.getInstance().getWindow().getGuiScale();
            int width = Math.max(1, (int) Math.round(sceneEditor.scene.getContentWidth() * guiScale));
            int height = Math.max(1, (int) Math.round(sceneEditor.scene.getContentHeight() * guiScale));
            if (width != fboRenderer.getResolutionWidth() || height != fboRenderer.getResolutionHeight()) {
                fboRenderer.setFBOSize(width, height);
            }
        }
    }

    public void clearScene() {
        level.clear();
        reset();
        fxObjectAnimationView.clear();
        fxObjectInfoView.clear();
        isSceneLoaded = false;
    }

    public void reset() {
        particleManager.clear();
        effect.reset();
    }

    public void play() {
        if (fxEditor.runtime != null) {
            fxEditor.runtime.emmit(effect);
            particleManager.play();
        }
    }

    public void simulateTo(long time) {
        // a scrub/seek/edit-preview replay (never live play): silence timeline audio so a replayed clip
        // doesn't start (or leave) a long sound playing. syncSignalDispatch re-enables it next UI tick if
        // playback is actually live.
        if (fxEditor.runtime != null) fxEditor.runtime.timelinePlayer.setAudioDispatch(false);
        var curTime = particleManager.getTime();
        if (time > curTime) {
            var iter = Math.min(time - curTime, 500 * 20);
            for (int i = 0; i < iter; i++) {
                particleManager.tickInternal();
            }
            particleManager.setTime(time);
        } else {
            reset();
            if (fxEditor.runtime != null) {
                fxEditor.runtime.emmit(effect);
                var iter = Math.min(time, 500 * 20);
                for (int i = 0; i < iter; i++) {
                    particleManager.tickInternal();
                }
                particleManager.setTime(time);
            }
        }
    }

    public void loadScene() {
        level.clear();
        reset();
        isSceneLoaded = false;
        if (sceneMode == SceneMode.PLATFORM) {
            var i = 0;
            for (int x = -sceneRange + 1; x < sceneRange; x++) {
                for (int z = -sceneRange + 1; z < sceneRange; z++) {
                    var blockState = (i % 2 == 0 ? Blocks.GRAY_CONCRETE : Blocks.LIGHT_GRAY_CONCRETE).defaultBlockState();
                    level.setBlockAndUpdate(new BlockPos(x, 0, z), blockState);
                    i++;
                }
            }
        } else {
            var player = Minecraft.getInstance().player;
            if (player == null) return;
            var standPos = player.blockPosition();
            for (int x = -sceneRange + 1; x < sceneRange; x++) {
                for (int z = -sceneRange + 1; z < sceneRange; z++) {
                    for (int y = -3; y < 5; y++) {
                        var blockState = player.level().getBlockState(standPos.offset(x, y, z));
                        level.setBlockAndUpdate(new BlockPos(x, y, z), blockState);
                    }
                }
            }
        }
        sceneEditor.scene.setRenderedCore(level.getFilledBlocks().longStream().mapToObj(BlockPos::of).toList());
        isSceneLoaded = true;
    }

    public void setSceneMode(SceneMode sceneMode) {
        if (this.sceneMode == sceneMode) return;
        this.sceneMode = sceneMode;
        if (isSceneLoaded) loadScene();
        fxEditor.reloadEffect();
    }

    public void setSceneRange(int sceneRange) {
        if (this.sceneRange == sceneRange) return;
        this.sceneRange = sceneRange;
        if (isSceneLoaded) loadScene();
        fxEditor.reloadEffect();
    }

    public class ParticleSceneEditor extends SceneEditor {
        public static final IGuiTexture SHAPE_OUTLINE = Icons.icon(Photon.MOD_ID, "shape_outline");
        public static final IGuiTexture CULL_BOX = Icons.icon(Photon.MOD_ID, "cull_box");

        public SceneView sceneView() {
            return SceneView.this;
        }

        @Override
        protected void renderAfterWorld(@NotNull MultiBufferSource bufferSource, float partialTicks) {
            if (fxObjectInfoView.getInspected() != null) {
                fxObjectInfoView.getInspected().drawEditorAfterWorld(this, bufferSource, partialTicks);
                if (isCullBoxVisible && fxObjectInfoView.getInspected() instanceof FXObject fxObject) {
                    var cullBox = fxObject.getRenderBoundingBox(partialTicks);
                    if (cullBox != AABB.INFINITE) {
                        RenderSystem.enableBlend();
                        RenderSystem.disableDepthTest();
                        RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                        RenderSystem.disableCull();
                        RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
                        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
                        RenderSystem.lineWidth(3);

                        RenderBufferUtils.drawCubeFrame(new PoseStack(), buffer,
                                (float) cullBox.minX, (float) cullBox.minY, (float) cullBox.minZ,
                                (float) cullBox.maxX, (float) cullBox.maxY, (float) cullBox.maxZ,
                                1, 0.5f, 0.5f, 1);

                        BufferUploader.drawWithShader(buffer.buildOrThrow());
                        RenderSystem.enableDepthTest();
                        RenderSystem.enableCull();
                    }
                }
            }
            if (fxObjectAnimationView.isDisplayed() && fxEditor.runtime != null) {
                fxObjectAnimationView.runFrameAnimation(fxEditor.runtime.root.transform(), particleManager.getRealTime(partialTicks));
            }
            super.renderAfterWorld(bufferSource, partialTicks);
        }

        @Override
        public void initTopBar() {
            super.initTopBar();
            var sceneRangeScroller = new Scroller.Horizontal();
            sceneRangeScroller.headButton.setDisplay(false);
            sceneRangeScroller.tailButton.setDisplay(false);
            var sceneSettings = new UIElement().layout(layout -> {
                layout.heightPercent(100);
                layout.flexDirection(FlexDirection.ROW);
                layout.gapAll(1);
                layout.flex(1);
            }).addChildren(
                    new Selector<SceneMode>()
                            .setCandidates(List.of(SceneMode.values()))
                            .setValue(getSceneMode(), false)
                            .setOnValueChanged(SceneView.this::setSceneMode)
                            .setCandidateUIProvider(candidate -> new Label()
                                    .textStyle(style -> style
                                            .textAlignHorizontal(Horizontal.LEFT)
                                            .textAlignVertical(Vertical.CENTER))
                                    .setText(candidate == null ? "---" : candidate.translateKey))
                            .layout(layout -> {
                                layout.heightPercent(100);
                                layout.flex(1);
                            })
                            .style(style -> style.tooltips("editor.scene_mode"))
                            .addEventListener(UIEvents.TICK, event -> {
                                if (event.currentElement instanceof Selector selector) {
                                    if (selector.getValue() != getSceneMode()) {
                                        selector.setValue(getSceneMode(), false);
                                    }
                                }
                            }),
                    new Selector<DrawMode>()
                            .setCandidates(List.of(DrawMode.values()))
                            .setValue(getDrawMode(), false)
                            .setOnValueChanged(SceneView.this::setDrawMode)
                            .setCandidateUIProvider(candidate -> new Label()
                                    .textStyle(style -> style
                                            .textAlignHorizontal(Horizontal.LEFT)
                                            .textAlignVertical(Vertical.CENTER))
                                    .setText(candidate == null ? "---" : candidate.translateKey))
                            .layout(layout -> {
                                layout.heightPercent(100);
                                layout.flex(1);
                            })
                            .style(style -> style.tooltips("editor.draw_mode"))
                            .addEventListener(UIEvents.TICK, event -> {
                                if (event.currentElement instanceof Selector selector) {
                                    if (selector.getValue() != getDrawMode()) {
                                        selector.setValue(getDrawMode(), false);
                                    }
                                }
                            }),
                    new Selector<SceneBackground>()
                            .setCandidates(List.of(SceneBackground.values()))
                            .setValue(getSceneBackground(), false)
                            .setOnValueChanged(SceneView.this::setSceneBackground)
                            .setCandidateUIProvider(candidate -> new Label()
                                    .textStyle(style -> style
                                            .textAlignHorizontal(Horizontal.LEFT)
                                            .textAlignVertical(Vertical.CENTER))
                                    .setText(candidate == null ? "---" : candidate.translateKey))
                            .layout(layout -> {
                                layout.heightPercent(100);
                                layout.flex(1);
                            })
                            .style(style -> style.tooltips("editor.scene_background"))
                            .addEventListener(UIEvents.TICK, event -> {
                                if (event.currentElement instanceof Selector selector) {
                                    if (selector.getValue() != getSceneBackground()) {
                                        selector.setValue(getSceneBackground(), false);
                                    }
                                }
                            }),
                    sceneRangeScroller.setRange(1, 10).setValue((float) getSceneRange(), false)
                            .setScrollBarSize(10).setOnValueChanged(value -> setSceneRange(Mth.clamp((int) value, 1, 10))).layout(layout -> {
                        layout.heightPercent(100);
                        layout.flex(1);
                    })
            );
            var rightMost = new UIElement().layout(layout -> {
                layout.heightPercent(100);
                layout.flexDirection(FlexDirection.ROW_REVERSE);
                layout.gapAll(1);
            });

            rightMost.addChildren(
                    new SceneToggleBuilder(SceneView.this::isShapeVisible,
                            SceneView.this::setShapeVisible)
                            .icon(SHAPE_OUTLINE)
                            .tooltipKey("photon.is_shape_visible")
                            .build(),
                    new SceneToggleBuilder(SceneView.this::isCullBoxVisible,
                            SceneView.this::setCullBoxVisible)
                            .icon(CULL_BOX)
                            .tooltipKey("photon.is_cull_visible")
                            .build(),
                    new SceneToggleBuilder(fxObjectAnimationView::isDisplayed,
                            fxObjectAnimationView::setDisplay)
                            .icon(new TextTexture("A"))
                            .tooltipKey("photon.is_animation_view_visible")
                            .build(),
                    new SceneToggleBuilder(fxObjectInfoView::isDisplayed,
                            fxObjectInfoView::setDisplay)
                            .icon(new TextTexture("S"))
                            .tooltipKey("photon.is_information_view_visible")
                            .build()
            );

            topBar.addChildren(sceneSettings, rightMost);
        }
    }

}
