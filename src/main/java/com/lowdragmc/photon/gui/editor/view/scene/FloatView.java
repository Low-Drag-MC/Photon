package com.lowdragmc.photon.gui.editor.view.scene;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import lombok.Getter;
import net.minecraft.network.chat.Component;
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
        getLayout().positionType(TaffyPosition.ABSOLUTE);
        getLayout().width(150);
        getLayout().leftPercent(100);
        getLayout().topPercent(100);

        this.titleBar = new UIElement();
        this.contentContainer = new UIElement();

        this.titleBar.layout(layout -> {
            layout.widthPercent(100);
            layout.alignItems(AlignItems.CENTER);
            layout.paddingAll(5);
        }).addClass("preview_bg");
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
                    layout.left( newPos.x);
                    layout.top( newPos.y);
                });
            }
        });
        // hide and show
        titleBar.addEventListener(UIEvents.DOUBLE_CLICK, event -> {
            if (isHidden()) show();
            else hide();
        });

        this.contentContainer.layout(layout -> {
            layout.widthPercent(100);
            layout.alignItems(AlignItems.CENTER);
            layout.justifyContent(AlignContent.CENTER);
            layout.paddingAll(4);
            layout.gapAll(2);
        }).addClass("panel_bg");

        addChildren(titleBar, contentContainer);
    }

    public void show() {
        if (isHidden) {
            isHidden = false;
            contentContainer.setDisplay(true);
        }
    }

    public void hide() {
        if (!isHidden) {
            isHidden = true;
            contentContainer.setDisplay(false);
        }
    }

    public UIElement createInformation(Component title, Supplier<Component> info) {
        return new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.flexDirection(FlexDirection.ROW);
            layout.height(9);
        }).addChildren(
                new Label().setText(title).textStyle(style -> style
                        .adaptiveWidth(true)
                        .textAlignVertical(Vertical.CENTER)),
                new Label().setText(info.get()).textStyle(style -> style
                        .adaptiveWidth(true)
                        .textAlignVertical(Vertical.CENTER)
                        .textAlignHorizontal(Horizontal.RIGHT)).layout(layout -> {
                    layout.flex(1);
                }).addEventListener(UIEvents.TICK, event -> ((Label) event.currentElement).setText(info.get()))
        );
    }

    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        adaptPositionToElement(sceneView.sceneEditor.scene);
    }

}
