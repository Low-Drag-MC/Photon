package com.lowdragmc.photon.gui.editor.view.scene;

import com.lowdragmc.lowdraglib2.configurator.ConfiguratorParser;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.math.Transform;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.network.chat.Component;
import org.appliedenergistics.yoga.YogaEdge;
import org.appliedenergistics.yoga.YogaGutter;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.function.BiFunction;

public class FXObjectAnimationView extends FloatView {
    @Getter @Setter
    @Configurable(name = "FXObjectAnimationView.shape")
    private Shape shape = Shape.Circle;
    @Getter @Setter
    @Configurable(name = "FXObjectAnimationView.size")
    @ConfigNumber(range = {0.1, Float.MAX_VALUE})
    private float size = 3;
    @Getter @Setter
    @Configurable(name = "FXObjectAnimationView.speed")
    @ConfigNumber(range = {-Float.MAX_VALUE, Float.MAX_VALUE})
    private float speed = 1;
    @Configurable(name = "FXObjectAnimationView.selfRotation")
    @ConfigNumber(range = {Integer.MIN_VALUE, Integer.MAX_VALUE})
    private int selfRotation = 0;
    // runtime
    @Getter
    private boolean isPlaying = false;

    public FXObjectAnimationView(SceneView sceneView) {
        super(sceneView, Component.translatable("photon.animation"));
        getLayout().setPositionPercent(YogaEdge.LEFT, 0);
        getLayout().setPositionPercent(YogaEdge.TOP, 100);

        initBasicInfo();
        hide();
    }

    public void clear() {
        isPlaying = false;
    }

    protected void initBasicInfo() {
        var group = new ConfiguratorGroup();
        ConfiguratorParser.createConfigurators(group, this, false);
        var configurators = new ArrayList<>(group.getConfigurators()).toArray(new UIElement[0]);
        group.removeAllConfigurators();
        contentContainer.addChildren(
                new UIElement().layout(layout -> {
                    layout.setWidthPercent(100);
                    layout.setGap(YogaGutter.ALL, 2);
                }).addChildren(configurators),
                // buttons
                new Button().setText(isPlaying() ?
                        "photon.gui.editor.fx_info.pause" :
                        "photon.gui.editor.fx_info.play").setOnClick(e -> {
                            if (sceneView.fxEditor.runtime == null) {
                                isPlaying = false;
                            } else {
                                isPlaying = !isPlaying;
                            }
                }).layout(layout -> {
                    layout.setHeight(12);
                    layout.setWidthPercent(100);
                }).addEventListener(UIEvents.TICK, event -> ((Button) event.currentElement).text
                        .setText(Component.translatable(isPlaying() ?
                                "photon.gui.editor.fx_info.pause" :
                                "photon.gui.editor.fx_info.play")))
        );
    }

    // called per frame
    public void runFrameAnimation(Transform transform, float gameTime) {
        if (!isPlaying) return;

        float t = (gameTime / 20f * speed) / 2.0f;

        Vector3f position = shape.getPosition(t, size).add(new Vector3f(0.5f, 2f, 0.5f));
        transform.position(position);

        float nextT = t + 0.001f;
        Vector3f target = shape.getPosition(nextT, size).add(new Vector3f(0.5f, 2f, 0.5f));
        Vector3f direction = new Vector3f(target).sub(position).normalize();

        Vector3f baseUp = new Vector3f(0, 1, 0);
        Vector3f rotatedUp = rotateVectorAroundAxis(baseUp, direction, selfRotation * t); // 或者用固定角度

        transform.lookAt(target, rotatedUp);
    }

    private Vector3f rotateVectorAroundAxis(Vector3f vector, Vector3f axis, float angle) {
        return vector.rotateAxis(angle, axis.x, axis.y, axis.z).normalize();
    }

    public enum Shape {
        Circle((t, size) -> {
            var angle = t * 360;
            var x = (float) Math.cos(Math.toRadians(angle)) * size;
            var z = (float) Math.sin(Math.toRadians(angle)) * size;
            return new Vector3f(x, 0, z);
        }),
        Square((t, size) -> {
            float t4 = t * 4;
            int side = (int) t4;
            float progress = t4 - side;
            side = side % 4;

            return switch (side) {
                case 0 -> new Vector3f(size * (progress - 0.5f), 0, size * 0.5f); // top
                case 1 -> new Vector3f(size * 0.5f, 0, size * (0.5f - progress)); // right
                case 2 -> new Vector3f(size * (0.5f - progress), 0, size * -0.5f); // bottom
                default -> new Vector3f(size * -0.5f, 0, size * (-0.5f + progress)); // left
            };
        }),
        SineWave((t, size) -> {
            float y = (float) Math.sin(t * 8 * Math.PI) + 1.0f;
            var angle = t * 360;
            var x = (float) Math.cos(Math.toRadians(angle)) * size;
            var z = (float) Math.sin(Math.toRadians(angle)) * size;
            return new Vector3f(x, y, z);
        })
        ;

        private final BiFunction<Float, Float, Vector3f> positionProvider;

        Shape(BiFunction<Float, Float, Vector3f> positionProvider) {
            this.positionProvider = positionProvider;
        }

        public Vector3f getPosition(float t, float size) {
            return positionProvider.apply(t, size);
        }

        public Vector3f getDirection(float t, float size, float deltaTime) {
            Vector3f currentPos = getPosition(t, size);
            Vector3f nextPos = getPosition(Math.min(t + deltaTime, 1.0f), size);

            Vector3f direction = new Vector3f(nextPos).sub(currentPos);

            if (direction.lengthSquared() < 1e-8f) {
                Vector3f prevPos = getPosition(Math.max(t - deltaTime, 0.0f), size);
                direction = new Vector3f(currentPos).sub(prevPos);
            }

            return direction.lengthSquared() > 1e-8f ? direction.normalize() : new Vector3f(1, 0, 0);
        }

        public Vector3f getDirection(float t, float size) {
            return getDirection(t, size, 0.0001f);
        }

    }
}
