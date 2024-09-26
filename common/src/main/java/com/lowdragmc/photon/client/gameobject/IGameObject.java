package com.lowdragmc.photon.client.gameobject;

public interface IGameObject {

    // Get the layer of this object
    default int getLayer() {
        return 0;
    }

    // Get the transform of this object
    Transform getTransform();

}
