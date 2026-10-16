package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.TransformRefConfigurator;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.IScene;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.TransformRef;
import com.lowdragmc.photon.gui.editor.view.FXHierarchyView;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/** Shared by every {@link ValueSpace#Custom}: resolving the picked transform and its hierarchy-droppable picker. */
@OnlyIn(Dist.CLIENT)
public final class CustomSpace {

    private CustomSpace() {
    }

    /** A direction given in the picked transform's axes, re-expressed in world axes (in place). */
    public static Vector3f dirToWorld(TransformRef ref, @Nullable IScene scene, Vector3f direction) {
        var transform = ref.getTransform(scene);
        return transform == null ? direction : transform.localToWorldMatrix().transformDirection(direction);
    }

    /** Transform caches are lazy and unsynchronized: build them on the game thread before a parallel update reads them. */
    public static void warm(TransformRef ref, @Nullable IScene scene) {
        var transform = ref.getTransform(scene);
        if (transform != null) {
            transform.localToWorldMatrix();
            transform.worldToLocalMatrix();
        }
    }

    public static Configurator configurator(String name, TransformRef ref, String tips) {
        return new TransformRefConfigurator(name, () -> ref,
                value -> ref.setTransformId(value.getTransformId()), new TransformRef(), true) {
            @Override
            protected boolean canDropObject(@NotNull Object object) {
                return object instanceof FXHierarchyView.DraggingNode || super.canDropObject(object);
            }

            @Override
            protected void onDropObject(@NotNull Object object) {
                if (object instanceof FXHierarchyView.DraggingNode(var draggedNode)) {
                    onValueUpdatePassively(new TransformRef(draggedNode.key.transform()));
                    updateValue();
                } else {
                    super.onDropObject(object);
                }
            }
        }.setTips(tips);
    }
}
