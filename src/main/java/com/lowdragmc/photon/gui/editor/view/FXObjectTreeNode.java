package com.lowdragmc.photon.gui.editor.view;

import com.lowdragmc.lowdraglib2.gui.util.ITreeNode;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import java.util.List;

@EqualsAndHashCode
public class FXObjectTreeNode implements ITreeNode<IFXObject, Void> {
    @Getter
    public final int dimension;
    @Getter
    public final IFXObject key;

    public FXObjectTreeNode(IFXObject root) {
        this(0, root);
    }

    private FXObjectTreeNode(int dimension, IFXObject node) {
        this.dimension = dimension;
        this.key = node;
    }

    @Override
    public @Nullable Void getContent() {
        return null;
    }

    @Override
    @Nonnull
    public List<FXObjectTreeNode> getChildren() {
        return key.children().stream().map(child -> new FXObjectTreeNode(dimension + 1, (IFXObject) child)).toList();
    }
}
