package com.lowdragmc.photon.integration;

import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.runtime.AnnotationDetector;
import com.lowdragmc.lowdraglib.syncdata.IAccessor;
import com.lowdragmc.lowdraglib.syncdata.payload.NbtTagPayload;
import com.lowdragmc.photon.client.data.shape.IShape;
import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import com.lowdragmc.photon.gui.editor.accessor.IShapeAccessor;
import com.lowdragmc.photon.gui.editor.accessor.NumberFunction3Accessor;
import com.lowdragmc.photon.gui.editor.accessor.NumberFunctionAccessor;

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

    public static Map<String, AnnotationDetector.Wrapper<LDLRegister, ? extends IParticleEmitter>> REGISTER_EMITTERS = new HashMap<>();
    public static Map<String, AnnotationDetector.Wrapper<LDLRegister, ? extends IShape>> REGISTER_SHAPES = new HashMap<>();

    public static void init() {
        register(NbtTagPayload.class, NbtTagPayload::new, NUMBER_FUNCTION_ACCESSOR, 1000);
        register(NbtTagPayload.class, NbtTagPayload::new, NUMBER_FUNCTION3_ACCESSOR, 1000);
        register(NbtTagPayload.class, NbtTagPayload::new, SHAPE_ACCESSOR, 1000);

        AnnotationDetector.scanClasses(LDLRegister.class, IParticleEmitter.class, AnnotationDetector::checkNoArgsConstructor, AnnotationDetector::toUINoArgsBuilder, AnnotationDetector::UIWrapperSorter, l -> REGISTER_EMITTERS.putAll(l.stream().collect(Collectors.toMap(w -> w.annotation().name(), w -> w))));
        AnnotationDetector.scanClasses(LDLRegister.class, IShape.class, AnnotationDetector::checkNoArgsConstructor, AnnotationDetector::toUINoArgsBuilder, AnnotationDetector::UIWrapperSorter, l -> REGISTER_SHAPES.putAll(l.stream().collect(Collectors.toMap(w -> w.annotation().name(), w -> w))));

    }

}
