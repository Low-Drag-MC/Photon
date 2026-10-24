package com.lowdragmc.photon.client.collision;

import org.jetbrains.annotations.Nullable;

/** Static world geometry a query can gather boxes from. */
public interface BlockBoxSource {
    int BLOCKS = 1;
    int FLUIDS = 2;

    /**
     * Adds every box of the requested kinds that overlaps the region. Returns false when part of the region
     * could not be read on this thread, in which case the caller must not trust a miss.
     */
    boolean collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                    int mask, @Nullable BlockFilter filter, BoxBuffer out);
}
