package com.lowdragmc.photon.gui.editor.view;

import com.lowdragmc.lowdraglib2.editor.ui.View;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.SceneEditor;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProjectEffect;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import org.appliedenergistics.yoga.*;
import org.joml.Vector2f;

import javax.annotation.Nullable;
import java.util.function.Supplier;

public class SceneView extends View {
    public final FXEditor fxEditor;
    public final SceneEditor sceneEditor = new SceneEditor();
    public final TrackedDummyWorld level = new TrackedDummyWorld();
    public final PhotonParticleManager particleManager = new PhotonParticleManager();
    public final FXProjectEffect effect = new FXProjectEffect(level);
    public final FXObjectInfoView fxObjectInfoView = new FXObjectInfoView();

    public SceneView(FXEditor fxEditor) {
        super("editor.scene", Icons.CAMERA);
        this.getLayout().setWidthPercent(100.0F);
        this.getLayout().setHeightPercent(100.0F);
        this.fxEditor = fxEditor;
        level.setParticleManager(particleManager);

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
        clearParticles();
        fxObjectInfoView.clear();
    }

    public void clearParticles() {
        particleManager.clearAllParticles();
    }

    public void loadScene() {
        clearScene();
        var i = 0;
        for (int x = -5; x < 6; x++) {
            for (int z = -5; z < 6; z++) {
                var blockState = (i % 2 == 0 ? Blocks.GRAY_CONCRETE : Blocks.LIGHT_GRAY_CONCRETE).defaultBlockState();
                level.setBlockAndUpdate(new BlockPos(x, 0, z), blockState);
                i++;
            }
        }
        sceneEditor.scene.setRenderedCore(level.getFilledBlocks().longStream().mapToObj(BlockPos::of).toList());
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
            getLayout().setWidth(200);
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
                    new Button().setText("photon.gui.editor.fx_info.restart").setOnClick(e -> {
                        fxEditor.reloadEffect();
                    }).layout(layout -> {
                        layout.setWidthPercent(100);
                    }),
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
                    }).addEventListener(UIEvents.TICK, event -> {
                        if (event.currentElement instanceof Label label) {
                            var cur = label.getText();
                            var latest = info.get();
                            if (!cur.equals(latest)) {
                                label.setText(latest);
                            }
                        }
                    })
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
