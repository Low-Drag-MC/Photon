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
    /** Which scene edge the panel's horizontal offset is measured from. */
    public enum HAnchor { LEFT, RIGHT }
    /** Which scene edge the panel's vertical offset is measured from. */
    public enum VAnchor { TOP, BOTTOM }

    public final SceneView sceneView;
    public final UIElement titleBar;
    public final UIElement contentContainer;

    //runtime
    @Getter
    private boolean isHidden;
    // Percent/corner anchoring: the panel pins its nearest corner to the matching scene corner and
    // keeps that relative placement across scene resizes. fracX/fracY are the gap from the anchored
    // scene edge to the anchored panel edge, as a fraction of the scene content width/height.
    private HAnchor hAnchor = HAnchor.RIGHT;
    private VAnchor vAnchor = VAnchor.BOTTOM;
    private float fracX = 0;
    private float fracY = 0;
    private boolean isDragging = false;
    private float lastSceneW = -1, lastSceneH = -1;

    public FloatView(SceneView sceneView, Component title) {
        this.sceneView = sceneView;
        getLayout().positionType(TaffyPosition.ABSOLUTE);
        getLayout().width(150);

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
                isDragging = true;
                var newPos = new Vector2f(initialPos).add(event.x - event.dragStartX, event.y - event.dragStartY);
                this.layout(layout -> {
                    layout.left( newPos.x);
                    layout.top( newPos.y);
                });
            }
        });
        // commit the corner anchor once the drag settles: snap to the nearest scene corner and store
        // the relative offset so the panel tracks that corner across later scene resizes.
        titleBar.addEventListener(UIEvents.DRAG_END, event -> {
            isDragging = false;
            captureAnchor();
            applyAnchor();
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

    @Override
    protected void onLayoutChanged(boolean hasGeometryChanged) {
        super.onLayoutChanged(hasGeometryChanged);
        if (!isDragging) applyAnchor();
    }

    /**
     * Sets the anchor corner and relative offsets used to position this panel. Subclasses call this
     * to pick a default corner; {@link #captureAnchor()} recomputes it after a drag.
     */
    protected void setAnchor(HAnchor hAnchor, VAnchor vAnchor, float fracX, float fracY) {
        this.hAnchor = hAnchor;
        this.vAnchor = vAnchor;
        this.fracX = fracX;
        this.fracY = fracY;
    }

    /**
     * Re-derives {@code left}/{@code top} from the stored anchor + fractions against the current
     * scene rect. Idempotent: skips the write when already within ~0.5px so re-entrant layout
     * passes converge (same convergence the old {@code adaptPositionToElement} clamp relied on).
     */
    protected void applyAnchor() {
        var scene = sceneView.sceneEditor.scene;
        float sx = scene.getContentX();
        float sy = scene.getContentY();
        float sw = scene.getContentWidth();
        float sh = scene.getContentHeight();
        if (sw <= 0 || sh <= 0) return;
        float vw = getSizeWidth();
        float vh = getSizeHeight();

        float targetX = hAnchor == HAnchor.LEFT ? sx + fracX * sw : (sx + sw) - fracX * sw - vw;
        float targetY = vAnchor == VAnchor.TOP ? sy + fracY * sh : (sy + sh) - fracY * sh - vh;
        // keep the panel inside the scene rect
        targetX = Math.max(sx, Math.min(targetX, Math.max(sx, sx + sw - vw)));
        targetY = Math.max(sy, Math.min(targetY, Math.max(sy, sy + sh - vh)));

        float dx = targetX - getPositionX();
        float dy = targetY - getPositionY();
        if (Math.abs(dx) < 0.5f && Math.abs(dy) < 0.5f) return;
        float newLeft = getLayoutX() + dx;
        float newTop = getLayoutY() + dy;
        layout(layout -> {
            layout.left(newLeft);
            layout.top(newTop);
        });
    }

    /**
     * Picks the nearest scene corner as the anchor (panel center vs scene center) and records the
     * offset from that corner as a fraction of the scene size.
     */
    protected void captureAnchor() {
        var scene = sceneView.sceneEditor.scene;
        float sx = scene.getContentX();
        float sy = scene.getContentY();
        float sw = scene.getContentWidth();
        float sh = scene.getContentHeight();
        if (sw <= 0 || sh <= 0) return;
        float vx = getPositionX();
        float vy = getPositionY();
        float vw = getSizeWidth();
        float vh = getSizeHeight();
        hAnchor = (vx + vw / 2f) < (sx + sw / 2f) ? HAnchor.LEFT : HAnchor.RIGHT;
        vAnchor = (vy + vh / 2f) < (sy + sh / 2f) ? VAnchor.TOP : VAnchor.BOTTOM;
        fracX = Math.max(0f, hAnchor == HAnchor.LEFT ? (vx - sx) / sw : ((sx + sw) - (vx + vw)) / sw);
        fracY = Math.max(0f, vAnchor == VAnchor.TOP ? (vy - sy) / sh : ((sy + sh) - (vy + vh)) / sh);
    }

    /**
     * Re-applies the anchor when the scene has resized. Called every frame from
     * {@link SceneView#drawContents}: the panel's own layout box may not change when only the
     * sibling scene resizes, so {@link #onLayoutChanged} alone won't catch it.
     */
    public void reflowIfSceneResized() {
        if (isDragging) return;
        var scene = sceneView.sceneEditor.scene;
        float sw = scene.getContentWidth();
        float sh = scene.getContentHeight();
        if (sw != lastSceneW || sh != lastSceneH) {
            lastSceneW = sw;
            lastSceneH = sh;
            applyAnchor();
        }
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
        if (!isDragging) applyAnchor();
    }

}
