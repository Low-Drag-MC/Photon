package com.lowdragmc.photon.client.gameobject.emitter;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
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
@OnlyIn(Dist.CLIENT)
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

    RandomSource getRandomSource();

    @Override
    default void inspectSceneInformation(SceneView sceneView, UIElement container) {
        var progress = new ProgressBar() {
            @Override
            public void drawBackgroundAdditional(@Nonnull GUIContext guiContext) {
                super.drawBackgroundAdditional(guiContext);
                if (isAlive()) {
                    this.setValue(getT(sceneView.particleManager.isPlaying() ? guiContext.partialTick : 0));
                } else {
                    this.setValue(1f);
                }
            }
        };
        progress.label(label -> label.setText(""))
                .progressBarStyle(style -> style.interpolate(false))
                .layout(layout -> {
                    layout.setWidthPercent(100);
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
