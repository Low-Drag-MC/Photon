package com.lowdragmc.photon.integration;

import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib.gui.editor.runtime.AnnotationDetector;
import com.lowdragmc.lowdraglib.syncdata.IAccessor;
import com.lowdragmc.lowdraglib.syncdata.payload.NbtTagPayload;
import com.lowdragmc.photon.client.emitter.data.shape.IShape;
import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import com.lowdragmc.photon.gui.editor.accessor.IShapeAccessor;
import com.lowdragmc.photon.gui.editor.accessor.NumberFunction3Accessor;
import com.lowdragmc.photon.gui.editor.accessor.NumberFunctionAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static com.lowdragmc.lowdraglib.syncdata.TypedPayloadRegistries.register;

/**
 * @author KilaBash
 * @date 2023/6/4
 * @implNote LDLibPlugin
 */
public class LDLibPlugin {
    public static final IAccessor NUMBER_FUNCTION_ACCESSOR = new NumberFunctionAccessor();
    public static final IAccessor NUMBER_FUNCTION3_ACCESSOR = new NumberFunction3Accessor();
    public static final IAccessor SHAPE_ACCESSOR = new IShapeAccessor();

    @Environment(EnvType.CLIENT)
    public static Map<String, AnnotationDetector.Wrapper<LDLRegisterClient, ? extends IParticleEmitter>> REGISTER_EMITTERS;
    public static Map<String, AnnotationDetector.Wrapper<LDLRegister, ? extends IShape>> REGISTER_SHAPES;

    public static void init() {
        register(NbtTagPayload.class, NbtTagPayload::new, NUMBER_FUNCTION_ACCESSOR, 1000);
        register(NbtTagPayload.class, NbtTagPayload::new, NUMBER_FUNCTION3_ACCESSOR, 1000);
        register(NbtTagPayload.class, NbtTagPayload::new, SHAPE_ACCESSOR, 1000);

        if (LDLib.isClient()) {
            REGISTER_EMITTERS = new HashMap<>();
            AnnotationDetector.scanClasses(LDLRegisterClient.class, IParticleEmitter.class, AnnotationDetector::checkNoArgsConstructor, LDLibPlugin::toUINoArgsBuilder, LDLibPlugin::UIWrapperSorter, l -> REGISTER_EMITTERS.putAll(l.stream().collect(Collectors.toMap(w -> w.annotation().name(), w -> w))));
        }
        REGISTER_SHAPES = new HashMap<>();
        AnnotationDetector.scanClasses(LDLRegister.class, IShape.class, AnnotationDetector::checkNoArgsConstructor, AnnotationDetector::toUINoArgsBuilder, AnnotationDetector::UIWrapperSorter, l -> REGISTER_SHAPES.putAll(l.stream().collect(Collectors.toMap(w -> w.annotation().name(), w -> w))));

    }

    public static <T> AnnotationDetector.Wrapper<LDLRegisterClient, T> toUINoArgsBuilder(Class<? extends T> clazz) {
        return new AnnotationDetector.Wrapper<>(clazz.getAnnotation(LDLRegisterClient.class), clazz, () -> AnnotationDetector.createNoArgsInstance(clazz));
    }

    public static int UIWrapperSorter(AnnotationDetector.Wrapper<LDLRegisterClient, ?> a, AnnotationDetector.Wrapper<LDLRegisterClient, ?> b) {
        return b.annotation().priority() - a.annotation().priority();
    }

}
