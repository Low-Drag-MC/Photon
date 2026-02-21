package com.lowdragmc.photon.gui.editor.view.scene;

import com.lowdragmc.lowdraglib2.configurator.ui.NumberConfigurator;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.joml.Random;

import javax.annotation.Nullable;

public class FXObjectInfoView extends FloatView {
    public final UIElement inspector;
    //runtime
    @Nullable
    @Getter
    private IFXObject inspected;

    public FXObjectInfoView(SceneView sceneView) {
        super(sceneView, Component.translatable("photon.scene_information"));
        inspector = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.alignItems(AlignItems.CENTER);
            layout.justifyContent(AlignContent.CENTER);
            layout.gapAll(2);
        });
        inspector.setDisplay(false);
        stopInteractionEventsPropagation();
        initBasicInfo();
    }

    protected void initBasicInfo() {
        contentContainer.addChildren(
                // buttons
                new UIElement().layout(layout -> {
                    layout.widthPercent(100);
                    layout.flexDirection(FlexDirection.ROW);
                    layout.gapAll(2);
                }).addChildren(
                        new Button().setText("photon.gui.editor.fx_info.restart").setOnClick(e -> {
                            sceneView.fxEditor.reloadEffect();
                        }).layout(layout -> {
                            layout.height(12);
                            layout.flex(1);
                        }),
                        new Button().setText("photon.gui.editor.fx_info.pause").setOnClick(e -> {
                            if (sceneView.particleManager.isPlaying()) {
                                sceneView.particleManager.pause();
                            } else if (sceneView.fxEditor.runtime != null) {
                                sceneView.particleManager.play();
                            }
                        }).layout(layout -> {
                            layout.height(12);
                            layout.flex(1);
                        }).addEventListener(UIEvents.TICK, event -> ((Button) event.currentElement).text
                                .setText(Component.translatable(sceneView.particleManager.isPlaying() ?
                                        "photon.gui.editor.fx_info.pause" :
                                        "photon.gui.editor.fx_info.play")))
                ),
                // playback
                new NumberConfigurator("photon.gui.editor.fx_info.playback_time",
                        sceneView.particleManager::getTime,
                        time -> sceneView.simulateTo(time.longValue()), 0, false) {
                    @Override
                    public void screenTick() {
                        if (!textField.isFocused()) {
                            onValueUpdatePassively(supplier.get());
                        }
                    }
                }.setRange(0, 500 * 20).layout(layout -> layout.widthPercent(100)),
                new UIElement().layout(layout -> {
                    layout.widthPercent(100);
                    layout.flexDirection(FlexDirection.ROW);
                    layout.gapAll(2);
                }).addChildren(
                        new NumberConfigurator("photon.gui.editor.fx_info.seed",
                                sceneView.effect::getSeed,
                                seed -> {
                                    var curTime = sceneView.particleManager.getTime();
                                    sceneView.effect.setSeed(seed.longValue());
                                    sceneView.particleManager.setTimeOffset(Math.abs(seed.longValue()));
                                    sceneView.simulateTo(curTime);
                                }, sceneView.effect.getSeed(), true)
                                .layout(layout -> layout.flex(1)),

                        new Button().setText("random").setOnClick(e -> {
                            var curTime = sceneView.particleManager.getTime();
                            var newSeed = Random.newSeed();
                            sceneView.particleManager.setTimeOffset(Math.abs(newSeed));
                            sceneView.effect.setSeed(newSeed);
                            sceneView.simulateTo(curTime);
                        })
                ),
                // cpu time
                createInformation(
                        Component.translatable("photon.gui.editor.fx_info.cpu_time"),
                        () -> Component.literal("%d us".formatted(sceneView.particleManager.getCPUTime()))
                ),
                // frame time
                createInformation(
                        Component.translatable("photon.gui.editor.fx_info.frame_time"),
                        () -> Component.literal("%d us".formatted(sceneView.particleManager.getFrameTime()))
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

    public void clear() {
        inspect(null);
    }

    public void inspect(@Nullable IFXObject fxObject) {
        if (this.inspected == fxObject) return;
        inspector.clearAllChildren();
        this.inspected = fxObject;
        if (inspected != null) {
            inspector.setDisplay(true);
            inspected.inspectSceneInformation(sceneView, inspector);
        } else {
            inspector.setDisplay(false);
        }
    }
}
