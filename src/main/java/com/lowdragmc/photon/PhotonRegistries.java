package com.lowdragmc.photon;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.registry.AutoRegistry;
import com.lowdragmc.lowdraglib2.registry.LDLRegistry;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.utils.ReflectionUtils;
import com.lowdragmc.photon.client.gameobject.FXObjectType;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlockTextureSheetMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.IModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.IShape;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.gui.editor.view.timeline.TrackType;

import java.util.function.Supplier;

public class PhotonRegistries {

    // FX objects are type-driven: a registered FXObjectType supplies the creator + type-level apis.
    // each fx-object class declares its own @LDLRegisterClient static TYPE and returns it from
    // IFXObject#getFXObjectType().
    public static LDLRegistry.String<FXObjectType> FX_OBJECTS;

    public static AutoRegistry.LDLibRegisterClient<IMaterial, Supplier<IMaterial>> MATERIALS;

    public static AutoRegistry.LDLibRegisterClient<NumberFunction, Supplier<NumberFunction>> NUMBER_FUNCTIONS;

    public static AutoRegistry.LDLibRegisterClient<IShape, Supplier<IShape>> SHAPES;

    public static AutoRegistry.LDLibRegisterClient<IModelSource, Supplier<IModelSource>> MODEL_SOURCES;

    // these are stateless "type" singletons: each declares a @LDLRegisterClient static instance which we
    // register directly (no per-call creator / cache), populated via findAnnotationStaticField below.
    // a TrackType drives track creation AND supplies the track's UI editor.
    public static LDLRegistry.String<TrackType> TIMELINE_TRACKS;

    public static LDLRegistry.String<AnimatedPropertyType> ANIMATED_PROPERTIES;

    static {
        // 26.1: loaded unconditionally (matching LDLib2's own registries) — no @OnlyIn stripping
        // any more, classes load lazily and none touch GL at registration; this also lets the FX
        // serialization gametests run fully on the headless game-test server.
        Client.load();
    }

    public static void init() {
    }

    private static class Client {
        public static void load() {
            FX_OBJECTS = new LDLRegistry.String<>(Photon.id("fx_object"));
            registerStaticInstances(FX_OBJECTS, FXObjectType.class);
            MATERIALS = AutoRegistry.LDLibRegisterClient
                    .create(Photon.id("material"), IMaterial.class, AutoRegistry::noArgsCreator);
            NUMBER_FUNCTIONS = AutoRegistry.LDLibRegisterClient
                    .create(Photon.id("number_function"), NumberFunction.class, AutoRegistry::noArgsCreator);
            SHAPES = AutoRegistry.LDLibRegisterClient
                    .create(Photon.id("shape"), IShape.class, AutoRegistry::noArgsCreator);
            MODEL_SOURCES = AutoRegistry.LDLibRegisterClient
                    .create(Photon.id("model_source"), IModelSource.class, AutoRegistry::noArgsCreator);
            // LDLib2's LDLibRegisterClient.autoRegister() no-ops on the dedicated server, but the FX
            // serialization codecs dispatch through these registries on every dist — scan ourselves there.
            registerAnnotatedClasses(MATERIALS, IMaterial.class);
            registerAnnotatedClasses(NUMBER_FUNCTIONS, NumberFunction.class);
            registerAnnotatedClasses(SHAPES, IShape.class);
            registerAnnotatedClasses(MODEL_SOURCES, IModelSource.class);
            TIMELINE_TRACKS = new LDLRegistry.String<>(Photon.id("timeline_track"));
            registerStaticInstances(TIMELINE_TRACKS, TrackType.class);
            ANIMATED_PROPERTIES = new LDLRegistry.String<>(Photon.id("animated_property"));
            registerStaticInstances(ANIMATED_PROPERTIES, AnimatedPropertyType.class);
            // 26.1: @LDLRegisterClient lost `manual = true`, so the auto-scan registers these classes
            // itself; override the scanned holders with the singleton-backed ones.
            MATERIALS.registerOrOverride("missing", AutoRegistry.Holder.of(
                    IMaterial.MissingMaterial.class.getAnnotation(LDLRegisterClient.class),
                    IMaterial.MissingMaterial.class,
                    () -> IMaterial.MISSING));
            MATERIALS.registerOrOverride("block_atlas", AutoRegistry.Holder.of(
                    BlockTextureSheetMaterial.class.getAnnotation(LDLRegisterClient.class),
                    BlockTextureSheetMaterial.class,
                    () -> BlockTextureSheetMaterial.INSTANCE));
        }

        /** Server-dist replacement for {@code LDLibRegisterClient.autoRegister()} (which is client-gated
         *  upstream): register every {@code @LDLRegisterClient}-annotated class targeting the registry. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private static <T extends com.lowdragmc.lowdraglib2.registry.ILDLRegisterClient> void registerAnnotatedClasses(
                AutoRegistry.LDLibRegisterClient<T, Supplier<T>> registry, Class<T> baseType) {
            if (LDLib2.isClient()) return; // client dist already auto-scanned on creation
            var registryId = registry.getRegistryName().toString();
            ReflectionUtils.findAnnotationClasses(LDLRegisterClient.class,
                    data -> data.get("registry") instanceof String r && r.equals(registryId),
                    clazz -> {
                        if (baseType.isAssignableFrom(clazz)) {
                            var realClass = (Class<? extends T>) clazz;
                            var annotation = realClass.getAnnotation(LDLRegisterClient.class);
                            registry.registerOrOverride(annotation.name(), AutoRegistry.Holder.of(
                                    annotation, realClass, AutoRegistry.noArgsCreator(annotation, realClass)));
                        }
                    }, () -> {});
        }

        /** Register the {@code @LDLRegisterClient}-annotated {@code static} singleton instances of a type
         *  registry by their annotation name (no per-call creator/cache). */
        private static <T> void registerStaticInstances(LDLRegistry.String<T> registry, Class<T> baseType) {
            var registryId = registry.getRegistryName().toString();
            ReflectionUtils.findAnnotationStaticField(LDLRegisterClient.class,
                    data -> data.get("registry") instanceof String r && r.equals(registryId),
                    (field, obj) -> {
                        if (baseType.isInstance(obj)) {
                            registry.registerOrOverride(field.getAnnotation(LDLRegisterClient.class).name(), baseType.cast(obj));
                        }
                    }, () -> {});
        }
    }
}
