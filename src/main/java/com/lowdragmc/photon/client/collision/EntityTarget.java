package com.lowdragmc.photon.client.collision;

/** An entity frozen for one tick: its box at the end of the tick and how far it moved during it. */
public record EntityTarget(Object entity, int id,
                           double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                           double motionX, double motionY, double motionZ, boolean trigger) {
}
