package com.lowdragmc.photon.client.fx;

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
    public final FX fx;
    public final FXData fxData;
    public final Map<UUID, IFXObject> objects = new LinkedHashMap<>();
    public final IFXObject root;
    private final boolean isCopy;

    public FXRuntime(FX fx, FXData fxData, boolean copy, boolean deepCopy) {
        this.fx = fx;
        this.fxData = fxData;
        this.isCopy = copy;
        addSceneObjectInternal(root = new EmptyFXObject());
        root.setName("root");
        for (var fxObject : fxData.objects()) {
            if (copy) {
                var copied = fxObject.copy(deepCopy);
                addSceneObjectInternal(copied);
            } else {
                addSceneObjectInternal(fxObject);
            }
        }
        awake();
        for (var fxObject : objects.values()) {
            if (fxObject != root && fxObject.transform().parent() == null) {
                fxObject.transform().parent(root.transform(), false);
            }
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
    public void addSceneObject(ISceneObject sceneObject) {
        IScene.super.addSceneObject(sceneObject);
        if (!isCopy && sceneObject instanceof IFXObject fxObject) {
            fxData.objects().add(fxObject);
        }
    }

    @Override
    public void removeSceneObject(ISceneObject sceneObject) {
        IScene.super.removeSceneObject(sceneObject);
        if (!isCopy && sceneObject instanceof IFXObject fxObject) {
            fxData.objects().remove(fxObject);
        }
    }

    @Override
    public void addSceneObjectInternal(ISceneObject sceneObject) {
        if (sceneObject instanceof IFXObject fxObject) {
            fxObject.setScene(this);
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
            if (!isCopy) {
                fxData.objects().remove(fxObject);
            }
        } else {
            throw new IllegalArgumentException("%s is not an instance of IFXObject".formatted(sceneObject));
        }
    }

    public void emmit(IEffect effect) {
        for (var fxObject : objects.values()) {
            fxObject.reset();
            fxObject.emmit(effect);
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
}
