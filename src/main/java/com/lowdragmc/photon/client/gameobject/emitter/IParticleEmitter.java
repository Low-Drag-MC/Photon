package com.lowdragmc.photon.client.gameobject.emitter;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Function;


/**
 * @author KilaBash
 * @date 2023/6/2
 * @implNote IParticleEmitter
 */
public interface IParticleEmitter extends IFXObject, IConfigurable {

    default Emitter self() {
        return (Emitter) this;
    }

    /**
     * get amount of existing particle which emitted from it.
     */
    int getParticleAmount();

    Vector3f getVelocity();

    /**
     * Particles of this emitter store position/velocity in <b>simulation space</b>
     * (Local = emitter space, World = world space, Custom = a referenced transform's space).
     * The returned matrix maps simulation space to world space and must be treated as read-only.
     * Defaults describe world space (identity).
     */
    default Matrix4f getSimToWorld() {
        return new Matrix4f();
    }

    /**
     * The matrix mapping world space to this emitter's simulation space. Read-only.
     */
    default Matrix4f getWorldToSim() {
        return new Matrix4f();
    }

    /**
     * The world-space scale of the simulation space (1 for world space).
     */
    default Vector3f getSimSpaceScale() {
        return new Vector3f(1);
    }

    /**
     * The world-space rotation of the simulation space (identity for world space).
     */
    default Quaternionf getSimSpaceRotation() {
        return new Quaternionf();
    }

    /**
     * get the box of cull.
     * <br>
     * return null - culling disabled.
     */
    @Nullable
    default AABB getCullBox(float partialTicks) {
        return null;
    }

    int getAge();

    void setAge(int age);

    boolean isLooping();

    void setRGBAColor(Vector4f color);

    Vector4f getRGBAColor();

    float getT();

    float getT(float partialTicks);

    float getMemRandom(Object object);

    float getMemRandom(Object object, Function<RandomSource, Float> randomFunc);

    int getLightColor(BlockPos pos);

    /**
     * Light lookup with a caller-supplied fallback used when the value cannot be computed right
     * now (particle updates running on worker threads and the position missed the cache — the
     * emitter refreshes it on the game thread, so the correct value arrives next tick).
     */
    default int getLightColor(BlockPos pos, int lastLight) {
        return getLightColor(pos);
    }

    RandomSource getRandomSource();

    @Override
    default void inspectSceneInformation(SceneView sceneView, UIElement container) {
        var progress = new ProgressBar() {
            @Override
            public void drawBackgroundAdditional(@Nonnull com.lowdragmc.lowdraglib2.gui.ui.rendering.IGUIContext guiContext) {
                super.drawBackgroundAdditional(guiContext);
                if (isAlive()) {
                    this.setValue(getT(sceneView.particleManager.isPlaying() ? (guiContext instanceof com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext ctx ? ctx.partialTick : 0) : 0));
                } else {
                    this.setValue(1f);
                }
            }
        };
        progress.label(label -> label.setText(""))
                .progressBarStyle(style -> style.interpolate(false))
                .layout(layout -> {
                    layout.widthPercent(100);
                });
        container.addChildren(
                sceneView.fxObjectInfoView.createInformation(
                        Component.translatable("photon.gui.editor.fx_info.particles"),
                        () -> Component.literal(getParticleAmount() + "")),
                sceneView.fxObjectInfoView.createInformation(
                        Component.translatable("photon.gui.editor.fx_info.age"),
                        () -> Component.literal("%.2f s".formatted(getAge() / 20f))),
                progress
        );
    }
}
