//package com.lowdragmc.photon.gui.editor.configurator;
//
//import com.lowdragmc.lowdraglib2.configurator.ui.ValueConfigurator;
//import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
//import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
//import com.lowdragmc.lowdraglib2.gui.widget.ButtonWidget;
//import com.lowdragmc.lowdraglib2.gui.widget.WidgetGroup;
//import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
//import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
//import lombok.Getter;
//import net.minecraft.MethodsReturnNonnullByDefault;
//
//import javax.annotation.ParametersAreNonnullByDefault;
//import java.util.function.Consumer;
//import java.util.function.Supplier;
//
//
///**
// * @author KilaBash
// * @date 2022/12/1
// * @implNote NumberFunctionConfigurator
// */
//@ParametersAreNonnullByDefault
//@MethodsReturnNonnullByDefault
//public class NumberFunctionConfigurator extends ValueConfigurator<NumberFunction> {
//    @Getter
//    private NumberFunctionConfig config;
//    private WidgetGroup group;
//
//    public NumberFunctionConfigurator(String name, Supplier<NumberFunction> supplier, Consumer<NumberFunction> onUpdate, boolean forceUpdate, NumberFunctionConfig config) {
//        super(name, supplier, onUpdate, NumberFunction.constant(config.defaultValue()), forceUpdate);
//        this.config = config;
//        if (value == null) {
//            value = defaultValue;
//        }
//    }
//    // TODO configurator
//
////
////    @Override
////    protected void onValueUpdate(NumberFunction newValue) {
////        if (newValue == null) newValue = defaultValue;
////        if (newValue == value || newValue.equals(value)) return;
////        super.onValueUpdate(newValue);
////    }
////
////    @Override
////    public void init(int width) {
////        super.init(width);
////        var w = width - leftWidth - 6 - rightWidth - 9;
////        group = new WidgetGroup(leftWidth, 0, w, 15);
////        this.addWidget(group);
////        this.addWidget(new ButtonWidget(width - (tips.length > 0 ? 24 : 12), 2, 9, 9,
////                Icons.DOWN,
////                cd -> {
////                    if (Editor.INSTANCE != null) {
////                        var menu = TreeBuilder.Menu.start();
////                        for (Class<? extends NumberFunction> type : config.types()) {
////                            menu.leaf(type == value.getClass() ?Icons.CHECK : IGuiTexture.EMPTY, type.getSimpleName(), () -> {
////                                if (type == value.getClass()) return;
////                                try {
////                                    var newValue = type.getConstructor(NumberFunctionConfig.class).newInstance(config);
////                                    updateValue(newValue);
////                                    group.clearAllWidgets();
////                                    group.setSize(new Size(w, 15));
////                                    value.createConfigurator(group, this);
////                                    computeLayout();
////                                } catch (Throwable ignored) {
////                                }
////                            });
////                        }
////                        Editor.INSTANCE.openMenu(group.getPosition().x + width, group.getPosition().y, menu);
////                    }
////                }).setHoverTooltips("ldlib.gui.editor.tips.other"));
////        assert value != null;
////        value.createConfigurator(group, this);
////    }
////
////    @Override
////    public void computeHeight() {
////        super.computeHeight();
////        setSize(new Size(getSize().width, group.getSize().height));
////    }
////
////    public void updateValue(NumberFunction value) {
////        onValueUpdate(value);
////        super.updateValue();
////    }
//}
