package com.lowdragmc.photon.client.gameobject;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.ISceneObject;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.registry.ILDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.utils.LDLibExtraCodecs;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.DummyWorld;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import com.mojang.serialization.Codec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * FXObject is a scene object that is used for FXRuntime.
 * <br>
 * e.g. {@link Emitter}
 */
public interface IFXObject extends ISceneObject, IPersistedSerializable, IConfigurable, ILDLRegisterClient<IFXObject, Supplier<IFXObject>> {

    Codec<IFXObject> CODEC = PhotonRegistries.FX_OBJECTS.optionalCodec().dispatch(ILDLRegisterClient::getRegistryHolderOptional,
            optional -> optional.map(holder -> PersistedParser.createCodec(holder.value()).fieldOf("data"))
                    .orElseGet(LDLibExtraCodecs::errorDecoder));


    @Nullable
    default CompoundTag serializeWrapper() {
        return (CompoundTag) CODEC.encodeStart(NbtOps.INSTANCE, this).result().orElse(null);
    }

    @Nullable
    static IFXObject deserializeWrapper(Tag tag) {
        return CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(null);
    }

    default IGuiTexture getIcon() {
        return IGuiTexture.EMPTY;
    }

    /**
     * emitter name unique for one project
     */
    String getName();

    void setName(String name);

    void setDelay(int delay);

    Level getLevel();

    void setLevel(Level level);

    boolean isAlive();

    float getDeltaTime();

    @Override
    default String getConfigurableName() {
        return getName();
    }

    /**
     * should render particle
     */
    default boolean isVisible() {
        if (!isSelfVisible()) return false;
        var parent = transform().parent();
        if (parent != null && parent.sceneObject() instanceof IFXObject ifxObject) {
            return ifxObject.isVisible();
        }
        return true;
    }

    /**
     * set fx self visible
     */
    void setSelfVisible(boolean visible);

    /**
     * is fx self visible
     */
    boolean isSelfVisible();

    void setEffect(IEffectExecutor effect);

    @Nullable
    IEffectExecutor getEffectExecutor();

    /**
     * force - remove without waiting.
     */
    void remove(boolean force);

    /**
     * reset runtime data
     */
    void reset();

    /**
     * copy this object
     */
    default IFXObject deepCopy() {
        return CODEC.encodeStart(NbtOps.INSTANCE, this).result().flatMap((tag) -> CODEC.parse(NbtOps.INSTANCE, tag).result()).orElse(null);
    }

    default IFXObject shallowCopy() {
        return deepCopy();
    }

    /**
     * deep copy this object
     */
    default IFXObject copy(boolean deep) {
        return deep ? deepCopy() : shallowCopy();
    }

    /**
     * emit to a given level.
     */
    default void emmit(@Nonnull IEffectExecutor effect) {
        emmit(effect, null, null, null);
    }

    default void emmit(@Nonnull IEffectExecutor effect, @Nullable Vector3f position, @Nullable Quaternionf rotation, @Nullable Vector3f scale) {
        setEffect(effect);
        reset();
        if (position != null) {
            updatePos(position);
        }
        if (rotation != null) {
            updateRotation(rotation);
        }
        if (scale != null) {
            updateScale(scale);
        }
        setLevel(effect.getLevel());
        if (this instanceof Particle particle) {
            if (effect.getLevel() instanceof DummyWorld dummyWorld) {
                dummyWorld.getParticleManager().addParticle(particle);
            } else {
                Minecraft.getInstance().particleEngine.add(particle);
            }
        }
    }

    // transform
    default void updatePos(Vector3f newPos) {
        transform().position(newPos);
    }

    default void updateRotation(Quaternionf newRot) {
        transform().rotation(newRot);
    }

    default void updateRotation(Vector3f newRot) {
        transform().rotation(new Quaternionf().rotationXYZ(newRot.x, newRot.y, newRot.z));
    }

    default void updateScale(Vector3f newScale) {
        transform().scale(newScale);
    }

    default void copyTransformFrom(IFXObject fxObject) {
        transform().copyTransformFrom(fxObject.transform(), true, true);
        transform()._setInternalID(fxObject.transform().id());
    }

    // information inspection
    default void inspectSceneInformation(SceneView sceneView, UIElement container) {

    }

    // editor rendering
    default void drawEditorAfterWorld(SceneView.ParticleSceneEditor scene, MultiBufferSource bufferSource, float partialTicks) {

    }
}
