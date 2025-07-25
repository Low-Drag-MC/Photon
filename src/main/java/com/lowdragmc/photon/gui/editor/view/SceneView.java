package com.lowdragmc.photon.gui.editor.view;

import com.lowdragmc.lowdraglib2.client.utils.RenderBufferUtils;
import com.lowdragmc.lowdraglib2.configurator.ui.NumberConfigurator;
import com.lowdragmc.lowdraglib2.editor.ui.View;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.SceneEditor;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
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
import com.lowdragmc.photon.client.gameobject.IFXObject;
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
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.appliedenergistics.yoga.*;
import org.joml.Random;
import org.joml.Vector2f;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Supplier;

public class SceneView extends View {
    public enum SceneMode {
        PLATFORM("scene_mode.platform"),
        REAL_WORLD("scene_mode.real_world");

        public final String translateKey;

        SceneMode(String translateKey) {
            this.translateKey = translateKey;
        }
    }
    public final FXEditor fxEditor;
    public final ParticleSceneEditor sceneEditor;
    public final TrackedDummyWorld level = new TrackedDummyWorld();
    public final PhotonParticleManager particleManager = new PhotonParticleManager();
    public final FXProjectEffectExecutor effect = new FXProjectEffectExecutor(level);
    public final FXObjectInfoView fxObjectInfoView = new FXObjectInfoView();
    @Getter @Setter
    private boolean isShapeVisible = true;
    @Getter @Setter
    private boolean isCullBoxVisible = true;
    @Getter
    private SceneMode sceneMode = SceneMode.PLATFORM;
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
    }

    public void clearScene() {
        level.clear();
        reset();
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
        clearScene();
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
            if (fxObjectInfoView.inspected != null) {
                fxObjectInfoView.inspected.drawEditorAfterWorld(this, bufferSource, partialTicks);
                if (isCullBoxVisible && fxObjectInfoView.inspected instanceof FXObject fxObject) {
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
                            .style(style -> style.setTooltips("editor.scene_mode"))
                            .addEventListener(UIEvents.TICK, event -> {
                                if (event.currentElement instanceof Selector selector) {
                                    if (selector.getValue() != getSceneMode()) {
                                        selector.setValue(getSceneMode(), false);
                                    }
                                }
                            }),
                    sceneRangeScroller.setRange(1, 10).setValue((float) getSceneRange(), false)
                            .setScrollBarSize(10).setOnValueChanged(value -> setSceneRange(Mth.clamp((int) value, 1, 10))).layout(layout -> {
                        layout.setHeightPercent(100);
                        layout.setFlex(1);
                    })
            );
            var leftMost = new UIElement().layout(layout -> {
                layout.setHeightPercent(100);
                layout.setFlexDirection(YogaFlexDirection.ROW_REVERSE);
                layout.setGap(YogaGutter.ALL, 1);
            });
            var shapeVisibleToggle = new Toggle()
                    .setText("")
                    .setOn(isShapeVisible(), false)
                    .toggleButton(button -> button.layout(layout -> {
                        layout.setWidthPercent(100);
                        layout.setHeightPercent(100);
                    }))
                    .setOnToggleChanged(SceneView.this::setShapeVisible)
                    .toggleStyle(style -> {
                        style.baseTexture(Sprites.BORDER1_RT1_DARK);
                        style.hoverTexture(Sprites.BORDER1_RT1);
                        style.unmarkTexture(SHAPE_OUTLINE.copy().setColor(ColorPattern.GRAY.color).scale(0.6f));
                        style.markTexture(SHAPE_OUTLINE.copy().scale(0.6f));
                    })
                    .layout(layout -> {
                        layout.setPadding(YogaEdge.ALL, 0);
                        layout.setHeightPercent(100);
                        layout.setAspectRatio(1f);
                    }).addEventListener(UIEvents.TICK, event -> {
                        if (event.currentElement instanceof Toggle toggle) {
                            if (toggle.getValue() != isShapeVisible()) {
                                toggle.setValue(isShapeVisible(), false);
                            }
                        }
                    }).style(style -> style.setTooltips("photon.is_shape_visible"));
            var cullVisibleToggle = new Toggle()
                    .setText("")
                    .setOn(isCullBoxVisible(), false)
                    .toggleButton(button -> button.layout(layout -> {
                        layout.setWidthPercent(100);
                        layout.setHeightPercent(100);
                    }))
                    .setOnToggleChanged(SceneView.this::setCullBoxVisible)
                    .toggleStyle(style -> {
                        style.baseTexture(Sprites.BORDER1_RT1_DARK);
                        style.hoverTexture(Sprites.BORDER1_RT1);
                        style.unmarkTexture(CULL_BOX.copy().setColor(ColorPattern.GRAY.color).scale(0.6f));
                        style.markTexture(CULL_BOX.copy().scale(0.6f));
                    })
                    .layout(layout -> {
                        layout.setPadding(YogaEdge.ALL, 0);
                        layout.setHeightPercent(100);
                        layout.setAspectRatio(1f);
                    }).addEventListener(UIEvents.TICK, event -> {
                        if (event.currentElement instanceof Toggle toggle) {
                            if (toggle.getValue() != isCullBoxVisible()) {
                                toggle.setValue(isCullBoxVisible(), false);
                            }
                        }
                    }).style(style -> style.setTooltips("photon.is_cull_visible"));
            leftMost.addChildren(
                    shapeVisibleToggle, cullVisibleToggle
            );
            topBar.addChildren(sceneSettings, leftMost);
        }
    }

    public class FXObjectInfoView extends UIElement {
        public final UIElement titleBar;
        public final UIElement contentContainer;
        public final UIElement inspector;
        //runtime
        @Getter
        private boolean isHidden;
        @Nullable
        @Getter
        private IFXObject inspected;

        public FXObjectInfoView() {
            getLayout().setPositionType(YogaPositionType.ABSOLUTE);
            getLayout().setWidth(150);
            getLayout().setPositionPercent(YogaEdge.LEFT, 100);
            getLayout().setPositionPercent(YogaEdge.TOP, 100);

            this.titleBar = new UIElement();
            this.contentContainer = new UIElement();

            this.titleBar.layout(layout -> {
                layout.setWidthPercent(100);
                layout.setAlignItems(YogaAlign.CENTER);
                layout.setPadding(YogaEdge.ALL, 5);
            }).style(style -> style.backgroundTexture(Sprites.BORDER1_RT1));
            titleBar.addChild(new Label()
                    .textStyle(style -> style
                            .textAlignVertical(Vertical.CENTER)
                            .textAlignHorizontal(Horizontal.CENTER)
                            .adaptiveWidth(true))
                    .setText("photon.scene_information"));
            // drag movement
            titleBar.addEventListener(UIEvents.MOUSE_DOWN, event -> {
                titleBar.startDrag(new Vector2f(this.getLayoutX(), this.getLayoutY()), null);
            });
            titleBar.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, event -> {
                if (event.currentElement == titleBar && event.dragHandler.draggingObject instanceof Vector2f initialPos) {
                    var newPos = new Vector2f(initialPos).add(event.x - event.dragStartX, event.y - event.dragStartY);
                    this.layout(layout -> {
                        layout.setPosition(YogaEdge.LEFT, newPos.x);
                        layout.setPosition(YogaEdge.TOP, newPos.y);
                    });
                }
            });
            // hide and show
            titleBar.addEventListener(UIEvents.DOUBLE_CLICK, event -> {
                if (isHidden()) show();
                else hide();
            });

            this.contentContainer.layout(layout -> {
                layout.setWidthPercent(100);
                layout.setAlignItems(YogaAlign.CENTER);
                layout.setJustifyContent(YogaJustify.CENTER);
                layout.setPadding(YogaEdge.ALL, 4);
                layout.setGap(YogaGutter.ALL, 2);
            }).style(style -> style.backgroundTexture(Sprites.RECT_SOLID));

            inspector = new UIElement().layout(layout -> {
                layout.setWidthPercent(100);
                layout.setAlignItems(YogaAlign.CENTER);
                layout.setJustifyContent(YogaJustify.CENTER);
                layout.setGap(YogaGutter.ALL, 2);
            });
            inspector.setDisplay(YogaDisplay.NONE);
            addChildren(titleBar, contentContainer);
            stopInteractionEventsPropagation();
            initBasicInfo();
        }

        public void show() {
            if (isHidden) {
                isHidden = false;
                contentContainer.setDisplay(YogaDisplay.FLEX);
            }
        }

        public void hide() {
            if (!isHidden) {
                isHidden = true;
                contentContainer.setDisplay(YogaDisplay.NONE);
            }
        }

        private void initBasicInfo() {
            contentContainer.addChildren(
                    // buttons
                    new UIElement().layout(layout -> {
                        layout.setWidthPercent(100);
                        layout.setFlexDirection(YogaFlexDirection.ROW);
                        layout.setGap(YogaGutter.ALL, 2);
                    }).addChildren(
                            new Button().setText("photon.gui.editor.fx_info.restart").setOnClick(e -> {
                                fxEditor.reloadEffect();
                            }).layout(layout -> {
                                layout.setHeight(12);
                                layout.setFlex(1);
                            }),
                            new Button().setText("photon.gui.editor.fx_info.pause").setOnClick(e -> {
                                if (particleManager.isPlaying()) {
                                    particleManager.pause();
                                } else if (fxEditor.runtime != null){
                                    particleManager.play();
                                }
                            }).layout(layout -> {
                                layout.setHeight(12);
                                layout.setFlex(1);
                            }).addEventListener(UIEvents.TICK, event -> ((Button) event.currentElement).text
                                    .setText(Component.translatable(particleManager.isPlaying() ?
                                            "photon.gui.editor.fx_info.pause" :
                                            "photon.gui.editor.fx_info.play")))
                    ),
                    // playback
                    new NumberConfigurator("photon.gui.editor.fx_info.playback_time",
                            particleManager::getTime,
                            time -> simulateTo(time.longValue()), 0, false) {
                        @Override
                        public void screenTick() {
                            if (!textField.isFocused()) {
                                onValueUpdatePassively(supplier.get());
                            }
                        }
                    }.setRange(0, 500 * 20).layout(layout -> layout.setWidthPercent(100)),
                    new UIElement().layout(layout -> {
                        layout.setWidthPercent(100);
                        layout.setFlexDirection(YogaFlexDirection.ROW);
                        layout.setGap(YogaGutter.ALL, 2);
                    }).addChildren(
                            new NumberConfigurator("photon.gui.editor.fx_info.seed",
                                    effect::getSeed,
                                    seed -> {
                                        var curTime = particleManager.getTime();
                                        effect.setSeed(seed.longValue());
                                        particleManager.setTimeOffset(Math.abs(seed.longValue()));
                                        simulateTo(curTime);
                                    }, effect.getSeed(), true)
                                    .layout(layout -> layout.setFlex(1)),

                            new Button().setText("random").setOnClick(e -> {
                                var curTime = particleManager.getTime();
                                var newSeed = Random.newSeed();
                                particleManager.setTimeOffset(Math.abs(newSeed));
                                effect.setSeed(newSeed);
                                simulateTo(curTime);
                            })
                    ),
                    // cpu time
                    createInformation(
                            Component.translatable("photon.gui.editor.fx_info.cpu_time"),
                            () ->  Component.literal("%d us".formatted(particleManager.getCPUTime()))
                    ),
                    // frame time
                    createInformation(
                            Component.translatable("photon.gui.editor.fx_info.frame_time"),
                            () -> Component.literal("%d us".formatted(particleManager.getFrameTime()))
                    ),
                    // fps
                    createInformation(
                            Component.literal("FPS"),
                            () -> Component.literal(Minecraft.getInstance().getFps() + " fps")
                    ),
                    // inspector
                    inspector
        );
        }

        public UIElement createInformation(Component title, Supplier<Component> info) {
            return new UIElement().layout(layout -> {
                layout.setWidthPercent(100);
                layout.setFlexDirection(YogaFlexDirection.ROW);
                layout.setHeight(9);
            }).addChildren(
                    new Label().setText(title).textStyle(style -> style
                            .adaptiveWidth(true)
                            .textAlignVertical(Vertical.CENTER)),
                    new Label().setText(info.get()).textStyle(style -> style
                            .adaptiveWidth(true)
                            .textAlignVertical(Vertical.CENTER)
                            .textAlignHorizontal(Horizontal.RIGHT)).layout(layout -> {
                                layout.setFlex(1);
                    }).addEventListener(UIEvents.TICK, event -> ((Label) event.currentElement).setText(info.get()))
            );
        }

        @Override
        protected void onLayoutChanged() {
            super.onLayoutChanged();
            adaptPositionToElement(SceneView.this.sceneEditor.scene);
        }

        public void clear() {
            inspect(null);
        }

        public void inspect(@Nullable IFXObject fxObject) {
            if (this.inspected == fxObject) return;
            inspector.clearAllChildren();
            this.inspected = fxObject;
            if (inspected != null) {
                inspector.setDisplay(YogaDisplay.FLEX);
                inspected.inspectSceneInformation(SceneView.this, inspector);
            } else {
                inspector.setDisplay(YogaDisplay.NONE);
            }
        }
    }
}
