package com.lowdragmc.photon.client.fx;

import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.IScene;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.ISceneObject;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.EmptyFXObject;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * FXRuntime is a runtime of FX.
 */
@Getter
public class FXRuntime implements IScene {
    public final static UUID ROOT_UUID = new UUID(0, 0);
    public final FXData fxData;
    public final Map<UUID, IFXObject> objects = new LinkedHashMap<>();
    public final IFXObject root;

    public FXRuntime(FXData fxData) {
        this.fxData = fxData;
        this.root = new EmptyFXObject();
        this.root.transform()._setInternalID(ROOT_UUID);
        addSceneObject(root);
        root.setName("root");
        initRuntime();
    }

    private void initRuntime() {
        for (var fxObject : fxData.objects()) {
            addSceneObjectInternal(fxObject);
        }
        for (var fxObject : fxData.objects()) {
            fxObject.setScene(this);
        }
        for (var fxObject : fxData.objects()) {
            if (fxObject.transform().parent() == null) {
                fxObject.transform().parent(root.transform(), false);
            }
            fxObject.transform().rebuildChildOrder();
        }
    }

    @Nullable
    @Override
    public ISceneObject getSceneObject(UUID uuid) {
        // if we cannot find the object, return the root
        return objects.getOrDefault(uuid, root);
    }

    @Override
    public Collection<ISceneObject> getAllSceneObjects() {
        return objects.values().stream().map(ISceneObject.class::cast).toList();
    }

    @Override
    public void addSceneObjectInternal(ISceneObject sceneObject) {
        if (sceneObject instanceof IFXObject fxObject) {
            var previous = objects.put(fxObject.id(), fxObject);
            if (previous != null) {
                if (previous != fxObject) {
                    Photon.LOGGER.warn("Duplicate fx runtime object id %s is replaced".formatted(fxObject.id()));
                }
            }
        } else {
            throw new IllegalArgumentException("%s is not an instance of IFXObject".formatted(sceneObject));
        }
    }

    @Override
    public void removeSceneObjectInternal(ISceneObject sceneObject) {
        if (sceneObject instanceof IFXObject fxObject) {
            objects.remove(fxObject.id());
        } else {
            throw new IllegalArgumentException("%s is not an instance of IFXObject".formatted(sceneObject));
        }
    }

    public void emmit(IEffectExecutor effect) {
        emmit(effect, 0);
    }

    public void emmit(IEffectExecutor effect, int delay) {
        for (var fxObject : objects.values()) {
            fxObject.emmit(effect);
            fxObject.setDelay(delay);
        }
    }

    public boolean isAlive() {
        return objects.values().stream().anyMatch(IFXObject::isAlive);
    }

    public void destroy(boolean force) {
        for (var fxObject : objects.values()) {
            fxObject.remove(force);
        }
    }

    @Nullable
    public IFXObject findObject(String name) {
        for (var fxObject : objects.values()) {
            if (fxObject.getName().equals(name)) {
                return fxObject;
            }
        }
        return null;
    }

    public List<IFXObject> findObjects(String name) {
        var list = new ArrayList<IFXObject>();
        for (var fxObject : objects.values()) {
            if (fxObject.getName().equals(name)) {
                list.add(fxObject);
            }
        }
        return list;
    }
//
//    @Override
//    public @UnknownNullability CompoundTag serializeNBT(@Nonnull HolderLookup.Provider provider) {
//        var runtimeData = new CompoundTag();
//        runtimeData.put("fxData", fxData.serializeNBT(provider));
//        runtimeData.put("root", root.serializeNBT(provider));
//        return runtimeData;
//    }
//
//    @Override
//    public void deserializeNBT(@Nonnull HolderLookup.Provider provider, @Nonnull CompoundTag nbt) {
//        root.transform().children().clear();
//        objects.clear();
//        fxData.deserializeNBT(provider, nbt.getCompound("fxData"));
//        root.deserializeNBT(provider, nbt.getCompound("root"));
//        initRuntime();
//    }
}
