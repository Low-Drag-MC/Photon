package com.lowdragmc.photon.client.postfx.runtime;

import com.lowdragmc.photon.Photon;

import java.util.HashMap;
import java.util.Map;

/**
 * The CustomMask group registry: authors name groups with STRINGS (emitter renderers write a
 * group, effects/clips filter by one), and this session-scoped table maps each name to the 8-bit
 * id actually encoded in the mask target (auto-assigned 1-255 on first use, same idiom as
 * {@code PhotonGpuChannels}). Ids never persist — every stored form is the string — so
 * assignment order doesn't matter across sessions. Filtering by a name nothing wrote simply
 * matches nowhere.
 */
public final class MaskGroups {

    /** The group an empty/blank writer name falls back to. */
    public static final String DEFAULT_GROUP = "default";

    private static final int MAX_GROUPS = 255;
    private static final Map<String, Integer> IDS = new HashMap<>();
    private static boolean overflowLogged = false;

    private MaskGroups() {}

    /**
     * Drop every assignment (resource reload, next to the shader caches): ids only need to stay
     * stable WITHIN a frame — writers and readers both resolve names live each frame — so a reload
     * compacts away ids leaked by renamed/deleted groups. Debug-view colors may shift, nothing else.
     */
    public static synchronized void clearAll() {
        IDS.clear();
        overflowLogged = false;
    }

    /** The session id (1-255) of {@code group}, assigning one on first use; blank = "default". */
    public static synchronized int idOf(String group) {
        var name = group == null || group.isBlank() ? DEFAULT_GROUP : group;
        var existing = IDS.get(name);
        if (existing != null) return existing;
        if (IDS.size() >= MAX_GROUPS) {
            if (!overflowLogged) {
                overflowLogged = true;
                Photon.LOGGER.warn("more than {} distinct CustomMask groups this session — '{}' aliases id {}",
                        MAX_GROUPS, name, MAX_GROUPS);
            }
            return MAX_GROUPS;
        }
        int id = IDS.size() + 1;
        IDS.put(name, id);
        return id;
    }
}
