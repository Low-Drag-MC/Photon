package com.lowdragmc.photon.client.fx;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.fixer.PhotonFXProjectDataFixer;
import com.lowdragmc.photon.gui.editor.FXProject;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and caches {@link FX} definitions from {@code assets/<ns>/fx/<path>.fx} resources (resource
 * packs, mod jars, or the injected {@code <gameDir>/ldlib2} pack the editor exports into).
 * <p>
 * The id passed to {@link #getFX} omits the {@code fx/} prefix and {@code .fx} suffix:
 * {@code photon:example} → {@code assets/photon/fx/example.fx}.
 */
@ParametersAreNonnullByDefault
public class FXHelper {
    // concurrent: sub-emitter spawns may query the cache while other threads do (never mutate mid-load;
    // loadFX does not re-enter getFX, so computeIfAbsent cannot recurse)
    private final static Map<Identifier, FX> CACHE = new ConcurrentHashMap<>();
    public static final String FX_PATH = "fx/";

    public static int clearCache() {
        var count = CACHE.size();
        CACHE.clear();
        return count;
    }

    @Nullable
    public static FX getFX(Identifier fxLocation) {
        return getFX(fxLocation, true);
    }

    /**
     * Every fx id currently loadable through {@link #getFX} — from mod jars, resource packs and
     * mounted {@code .fxpack}s alike (anything providing {@code assets/<ns>/fx/<name>.fx}).
     */
    public static List<Identifier> listAllFX() {
        var result = new ArrayList<Identifier>();
        Minecraft.getInstance().getResourceManager()
                .listResources("fx", location -> location.getPath().endsWith(FX.SUFFIX))
                .forEach((location, resource) -> {
                    var path = location.getPath(); // fx/<name>.fx
                    result.add(Identifier.fromNamespaceAndPath(location.getNamespace(),
                            path.substring(FX_PATH.length(), path.length() - FX.SUFFIX.length())));
                });
        return result;
    }


    @Nullable
    public static FX getFX(Identifier fxLocation, boolean useCache) {
        return useCache ? CACHE.computeIfAbsent(fxLocation, location -> loadFX(fxLocation)) : loadFX(fxLocation);
    }

    @Nullable
    private static FX loadFX(Identifier fxLocation) {
        Identifier resourceLocation = Identifier.fromNamespaceAndPath(fxLocation.getNamespace(), FX_PATH + fxLocation.getPath() + FX.SUFFIX);
        try (var inputStream = Minecraft.getInstance().getResourceManager().open(resourceLocation);) {
            var tag = NbtIo.readCompressed(inputStream, NbtAccounter.unlimitedHeap());
            tag = applyVersionFixes(tag);
            if (tag.getCompound("resources").isPresent()) {
                // short-lived dev format that embedded assets in the .fx — superseded by .fxpack
                Photon.LOGGER.warn("fx {} carries an embedded 'resources' section, which is no longer "
                        + "supported — re-export it (assets travel in .fxpack files now)", fxLocation);
            }
            var fx = new FX();
            fx.setFxLocation(fxLocation);
            fx.deserializeNBT(Platform.getFrozenRegistry(), tag);
            return fx;
        } catch (Exception e) {
            Photon.LOGGER.warn("Failed to load fx {} ({})", fxLocation, resourceLocation, e);
            return null;
        }
    }

    /**
     * Run the project datafixer on a versioned {@code .fx} root tag. Files without a {@code version}
     * (everything exported before versioning) pass through untouched: they span all historical
     * versions and the deserializers' inline legacy fallbacks already handle them — running the
     * V1→current fixers on data of unknown true version would risk double-migration.
     */
    private static CompoundTag applyVersionFixes(CompoundTag tag) {
        if (!tag.getInt("version").isPresent()) {
            return tag;
        }
        int version = tag.getIntOr("version", 0);
        if (version >= FXProject.VERSION) {
            return tag;
        }
        // the fixers navigate the project "data" shape {fx: {fxData: ...}} — wrap, fix, unwrap
        var wrapped = new CompoundTag();
        wrapped.put("fx", tag);
        var fixed = PhotonFXProjectDataFixer.INSTANCE.applyFixes(version, FXProject.VERSION, wrapped).getCompoundOrEmpty("fx");
        fixed.putInt("version", FXProject.VERSION);
        return fixed;
    }

}
