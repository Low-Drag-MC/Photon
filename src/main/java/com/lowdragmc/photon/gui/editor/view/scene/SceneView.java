package com.lowdragmc.photon.gui.editor.view.scene;

import com.lowdragmc.lowdraglib2.client.utils.RenderBufferUtils;
import com.lowdragmc.lowdraglib2.editor.ui.View;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.SceneEditor;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProjectEffectExecutor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.appliedenergistics.yoga.*;
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
    private int sceneRange = 6;
    // runtime
    private boolean isSceneLoaded = false;

    public SceneView(FXEditor fxEditor) {
        super("editor.scene", Icons.CAMERA);
        this.getLayout().setWidthPercent(100.0F);
        this.getLayout().setHeightPercent(100.0F);
        this.fxEditor = fxEditor;
        level.setParticleManager(particleManager);

        sceneEditor = new ParticleSceneEditor();
        sceneEditor.layout(layout -> {
            layout.setWidthPercent(100);
            layout.setFlex(1);
        });
        sceneEditor.scene
                .createScene(level)
                .setTickWorld(true)
                .useCacheBuffer();
        this.addChild(sceneEditor);
        this.addChild(fxObjectInfoView);
        this.addChild(fxObjectAnimationView);
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
        protected void renderAfterWorld(MultiBufferSource bufferSource, float partialTicks) {
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
            sceneRangeScroller.headButton.setDisplay(YogaDisplay.NONE);
            sceneRangeScroller.tailButton.setDisplay(YogaDisplay.NONE);
            var sceneSettings = new UIElement().layout(layout -> {
                layout.setHeightPercent(100);
                layout.setFlexDirection(YogaFlexDirection.ROW);
                layout.setGap(YogaGutter.ALL, 1);
                layout.setFlex(1);
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
                                layout.setHeightPercent(100);
                                layout.setFlex(1);
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
                                layout.setHeightPercent(100);
                                layout.setFlex(1);
                            })
                            .style(style -> style.tooltips("editor.draw_mode"))
                            .addEventListener(UIEvents.TICK, event -> {
                                if (event.currentElement instanceof Selector selector) {
                                    if (selector.getValue() != getDrawMode()) {
                                        selector.setValue(getDrawMode(), false);
                                    }
                                }
                            }),
                    sceneRangeScroller.setRange(1, 10).setValue((float) getSceneRange(), false)
                            .setScrollBarSize(10).setOnValueChanged(value -> setSceneRange(Mth.clamp((int) value, 1, 10))).layout(layout -> {
                        layout.setHeightPercent(100);
                        layout.setFlex(1);
                    })
            );
            var rightMost = new UIElement().layout(layout -> {
                layout.setHeightPercent(100);
                layout.setFlexDirection(YogaFlexDirection.ROW_REVERSE);
                layout.setGap(YogaGutter.ALL, 1);
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
                            visible -> fxObjectAnimationView.setDisplay(visible ? YogaDisplay.FLEX : YogaDisplay.NONE))
                            .icon(new TextTexture("A"))
                            .tooltipKey("photon.is_animation_view_visible")
                            .build(),
                    new SceneToggleBuilder(fxObjectInfoView::isDisplayed,
                            visible -> fxObjectInfoView.setDisplay(visible ? YogaDisplay.FLEX : YogaDisplay.NONE))
                            .icon(new TextTexture("S"))
                            .tooltipKey("photon.is_information_view_visible")
                            .build()
            );

            topBar.addChildren(sceneSettings, rightMost);
        }
    }

}
