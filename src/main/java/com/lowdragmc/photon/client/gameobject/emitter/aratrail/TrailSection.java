package com.lowdragmc.photon.client.gameobject.emitter.aratrail;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;
import com.lowdragmc.photon.client.gameobject.emitter.data.ToggleGroup;
import dev.vfyjxf.taffy.style.TaffyPosition;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import net.minecraft.util.Mth;
import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class TrailSection extends ToggleGroup {
    @Configurable(name = "TrailSection.vertices")
    public List<Vector2f> vertices = new ArrayList<>();

    public TrailSection() {
        circlePreset(8);
    }

    public void addVertex(Vector2f vertex) {
        vertices.add(vertex);
    }

    public int getSegments() {
        return vertices != null ? vertices.size() - 1 : 0;
    }

    public void circlePreset(int segments) {
        vertices.clear();

        for (int j = 0; j <= segments; ++j) {
            float angle = 2 * (float) Math.PI / segments * j;
            Vector2f right = new Vector2f(1, 0);
            Vector2f up = new Vector2f(0, 1);
            Vector2f point = new Vector2f(right).mul((float) Math.cos(angle))
                    .add(new Vector2f(up).mul((float) Math.sin(angle)));
            vertices.add(point);
        }
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        super.buildConfigurator(father);
        var canvas = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.setAspectRatio(1);
            layout.paddingAll(4);
        }).style(style -> style.backgroundTexture(Sprites.BORDER1_DARK));
        var container = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.heightPercent(100);
        }).style(style -> style.backgroundTexture((com.lowdragmc.photon.utils.LegacyGuiTexture) this::drawCanvas));
        AtomicReference<List<Vector2f>> verticesRef = new AtomicReference<>(new ArrayList<>(vertices));
        reloadCanvas(container, verticesRef.get());
        container.addEventListener(UIEvents.TICK, e -> {
            if (!verticesRef.get().equals(vertices)) {
                verticesRef.set(new ArrayList<>(vertices));
                reloadCanvas(container, verticesRef.get());
            }
        });
        canvas.addChild(container);
        father.addConfigurator(new Configurator().addInlineChild(canvas));
    }

    private void drawCanvas(GUIContext graphics, float mouseX, float mouseY, float x, float y, float width, float height, float partialTicks) {
        if (vertices.size() < 2) return;
        var centerX = x + width / 2;
        var centerY = y + height / 2;
        var points = new ArrayList<Vector2f>();
        for (Vector2f vertex : vertices) {
            points.add(new Vector2f(vertex.x * width / 4  + centerX, vertex.y * height / 4 + centerY));
        }
        DrawerHelperClient.drawLines(graphics, points, -1, -1, 0.5f);
    }

    private void reloadCanvas(UIElement container, List<Vector2f> vertices) {
        container.clearAllChildren();
        for (Vector2f vertex : vertices) {
            var point = new UIElement().layout(layout -> {
                        layout.positionType(TaffyPosition.ABSOLUTE);
                        layout.height(4);
                        layout.width(4);
                        layout.marginLeft(-2);
                        layout.marginTop(-2);
                        layout.leftPercent((vertex.x + 2) / 4 * 100);
                        layout.topPercent((vertex.y + 2) / 4 * 100);
                    }).style(style -> style.backgroundTexture(ColorPattern.GREEN.rectTexture()))
                    .addEventListener(UIEvents.MOUSE_DOWN, event -> {
                        event.currentElement.startDrag(null, null);
                    }).addEventListener(UIEvents.MOUSE_ENTER, event -> {
                        event.currentElement.style(style -> style.overlayTexture(ColorPattern.RED.borderTexture(1)));
                    }).addEventListener(UIEvents.MOUSE_LEAVE, event -> {
                        event.currentElement.style(style -> style.overlayTexture(IGuiTexture.EMPTY));
                    }).addEventListener(UIEvents.DRAG_SOURCE_UPDATE, event -> {
                        var x = event.x - container.getContentX();
                        var y = event.y - container.getContentY();
                        var width = container.getContentWidth();
                        var height = container.getContentHeight();
                        var percentX = Mth.clamp(x / width, 0f, 1f);
                        var percentY = Mth.clamp(y / height, 0f, 1f);
                        event.currentElement.layout(layout -> {
                            layout.leftPercent(percentX * 100);
                            layout.topPercent(percentY * 100);
                        });
                        var pX = percentX * 4 - 2;
                        var pY = percentY * 4 - 2;
                        vertex.set(pX, pY);
                    });
            container.addChild(point);
        }
    }
}