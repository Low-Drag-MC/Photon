package com.lowdragmc.photon.client.gameobject.emitter.data.number.configurator;

import com.lowdragmc.lowdraglib2.configurator.ui.ValueConfigurator;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Menu;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import lombok.Getter;
import net.minecraft.MethodsReturnNonnullByDefault;
import org.appliedenergistics.yoga.*;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;


/**
 * @author KilaBash
 * @date 2022/12/1
 * @implNote NumberFunctionConfigurator
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class NumberFunctionConfigurator extends ValueConfigurator<NumberFunction> {
    public final Button numberFunctionButton = new Button();
    @Getter
    private NumberFunctionConfig config;

    public NumberFunctionConfigurator(String name, Supplier<NumberFunction> supplier, Consumer<NumberFunction> onUpdate, boolean forceUpdate, NumberFunctionConfig config) {
        super(name, supplier, onUpdate, NumberFunction.constant(config.defaultValue()), forceUpdate);
        this.config = config;
        if (value == null) {
            value = defaultValue;
        }
        value.createConfigurator(this);
        this.lineContainer.addChildAt(numberFunctionButton, 2);
        if (config.types().length <= 1) {
            numberFunctionButton.setDisplay(YogaDisplay.NONE);
        } else {
            numberFunctionButton.noText().setOnClick(e -> {
                var menu = TreeBuilder.Menu.start();
                var types = Arrays.stream(config.types()).collect(Collectors.toSet());
                for (var holder : PhotonRegistries.NUMBER_FUNCTIONS) {
                    var clazz = holder.clazz();
                    if (types.contains(clazz)) {
                        menu.leaf(clazz == value.getClass() ? Icons.CHECK_SPRITE : IGuiTexture.EMPTY, holder.annotation().name(), () -> {
                            var newValue = holder.value().get();
                            newValue.loadConfig(config);
                            updateValue(newValue);
                            clearInlineContainer();
                            value.createConfigurator(this);
                        });
                    }
                }
                var mui = getModularUI();
                if (mui != null) {
                    mui.ui.rootElement.addChild(new Menu<>(menu.build(), TreeBuilder.Menu::uiProvider)
                            .setHoverTextureProvider(TreeBuilder.Menu::hoverTextureProvider)
                            .setOnNodeClicked(TreeBuilder.Menu::handle)
                            .layout(layout -> {
                                layout.setPosition(YogaEdge.LEFT, numberFunctionButton.getPositionX() +
                                        numberFunctionButton.getSizeWidth() - 120 + - mui.ui.rootElement.getPositionX());
                                layout.setPosition(YogaEdge.TOP, numberFunctionButton.getPositionY() +
                                        numberFunctionButton.getSizeHeight() - mui.ui.rootElement.getContentY());
                            }));
                }
            }).layout(layout -> {
                layout.setAspectRatio(1);
            }).addChild(new UIElement().layout(layout -> {
                layout.setWidthPercent(100);
                layout.setHeightPercent(100);
            }).style(style -> style.backgroundTexture(Icons.DOWN_ARROW_NO_BAR)));
        }
        setCopiable(value -> value.copy());
        setPastable(NumberFunction.class::isAssignableFrom, pasted -> {
            if (pasted instanceof NumberFunction function) {
                onPaste(function);
            }
        });
    }

    @Override
    protected void onValueUpdatePassively(@Nullable NumberFunction newValue) {
        if (newValue == null) newValue = defaultValue;
        if (newValue == value || NumberFunction.isEqual(newValue, value)) return;
        super.onValueUpdatePassively(newValue);
        clearInlineContainer();
        newValue.createConfigurator(this);
    }

    private void clearInlineContainer() {
        inlineContainer.clearAllChildren();
        inlineContainer.layout(layout -> {
            layout.setGap(YogaGutter.ALL, 0);
            layout.setMargin(YogaEdge.LEFT, 0);
            layout.setFlexDirection(YogaFlexDirection.COLUMN);
            layout.setWrap(YogaWrap.NO_WRAP);
        });
    }

    @Override
    protected void updateValueActively(NumberFunction newValue) {
        var notifyChange = value != newValue;
        value = newValue;
        if (onUpdate != null) {
            onUpdate.accept(value);
        }
        if (notifyChange) {
            notifyChanges();
        }
    }

    public void updateValue(NumberFunction value) {
        updateValueActively(value);
    }

}
