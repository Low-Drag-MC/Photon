package com.lowdragmc.photon.client.gameobject.emitter;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.photon.gui.editor.FXProjectEffect;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.gui.editor.view.SceneView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;
import org.joml.Vector4f;

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

    default boolean isDev() {
        return getEffect() instanceof FXProjectEffect;
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

    default RandomSource getRandomSource() {
        getEffect()
    }

    @Override
    default void inspectSceneInformation(SceneView sceneView, UIElement container) {
        container.addChildren(
                sceneView.fxObjectInfoView.createInformation(
                        Component.translatable("photon.gui.editor.fx_info.particles"),
                        () -> Component.literal(getParticleAmount() + "")),
                sceneView.fxObjectInfoView.createInformation(
                        Component.translatable("photon.gui.editor.fx_info.age"),
                        () -> Component.literal("%.2f s".formatted(getAge() / 20f))),
                new ProgressBar() {
                    @Override
                    public void drawBackgroundAdditional(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                        super.drawBackgroundAdditional(graphics, mouseX, mouseY, partialTicks);
                        if (isAlive()) {
                            this.setValue(getT(partialTicks));
                        } else {
                            this.setValue(1f);
                        }
                    }
                }.label(label -> label.setText("")).progressBarStyle(style -> style.interpolate(false)).layout(layout -> {
                    layout.setWidthPercent(100);
                })
        );
    }
}
