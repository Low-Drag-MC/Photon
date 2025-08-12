package com.lowdragmc.photon.gui.editor.view.scene;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import lombok.Getter;
import net.minecraft.network.chat.Component;
import org.appliedenergistics.yoga.*;
import org.joml.Vector2f;

import java.util.function.Supplier;

public class FloatView extends UIElement {
    public final SceneView sceneView;
    public final UIElement titleBar;
    public final UIElement contentContainer;

    //runtime
    @Getter
    private boolean isHidden;

    public FloatView(SceneView sceneView, Component title) {
        this.sceneView = sceneView;
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
                .setText(title));
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

        addChildren(titleBar, contentContainer);
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
        adaptPositionToElement(sceneView.sceneEditor.scene);
    }

}
