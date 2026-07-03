package com.lowdragmc.photon.client.gameobject;

import com.lowdragmc.photon.client.fx.timeline.property.ConfigValueType;

import java.util.function.Function;

/**
 * Binds a timeline-animatable config value to its named runtime slot (a {@link RuntimeValue} on the
 * object's runtime layer, e.g. {@code ParticleRuntime}). Each {@code FXObjectType} declares a list of
 * these ({@link FXObjectType#runtimeBindings()}); the editor lists them (menu label / value type) and the
 * timeline drives them: {@code apply} does {@code slot(target).set(value)}, {@code restore} does
 * {@code slot(target).clear()} — direct typed field access, no map lookup and no reflection.
 */
public class RuntimeBinding {
    /** Config-relative dotted identity (e.g. {@code "physics.friction"}). Also the serialized key. */
    public final String path;
    /** i18n key for the display label (reuses the field's {@code @Configurable} name). */
    public final String labelKey;
    public final ConfigValueType type;
    private final Function<FXObject, RuntimeValue<?>> slot;

    public RuntimeBinding(String path, String labelKey, ConfigValueType type, Function<FXObject, RuntimeValue<?>> slot) {
        this.path = path;
        this.labelKey = labelKey;
        this.type = type;
        this.slot = slot;
    }

    /** Resolve the target object's runtime slot for this value (typed navigation, no map/reflection). */
    public RuntimeValue<?> slot(FXObject target) {
        return slot.apply(target);
    }
}
